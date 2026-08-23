package io.github.trevarj.motd.data.repo

import android.util.LruCache
import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production fetch limits for link previews. The single relaxation flag exists for unit tests,
 * which exercise MockWebServer over cleartext loopback — exactly what the destination policy
 * forbids in production.
 */
data class LinkPreviewFetchPolicy(
    /** HTTPS-only plus loopback/private/link-local/ULA destination blocking, on every hop. */
    val enforceDestinationPolicy: Boolean = true,
    /** Whole-fetch deadline; connect/read timeouts alone cannot bound a byte-dripping server. */
    val fetchDeadlineMs: Long = 15_000,
    /** Bound on concurrently in-flight preview fetches across the process. */
    val maxConcurrentFetches: Int = 4,
    /** Manual redirect-hop cap; each hop is validated like the original URL. */
    val maxRedirects: Int = 4,
)

// Declared web/text/media link preview. HttpURLConnection GET, 5s connect/read timeouts, HTML body
// capped at 512 KB, text body capped at 16 KB, and Wikipedia summaries capped at 128 KB.
// Completed negative results live in a bounded process cache, while concurrent callers for the
// same network+URL await one shared request. The OG parser remains dependency-free; Wikipedia
// summaries use the already-pinned kotlinx.serialization JSON parser.
//
// Every request is opened through the owning network's media route (never authenticated), so a
// preview for a proxied network traverses that network's proxy and fails closed — no direct
// fallback — when the proxy cannot be established or the network identity is unknown.
@Singleton
class LinkPreviewRepositoryImpl
    @Inject
    constructor(
        private val contentPreviewPrefs: ContentPreviewPrefs,
        private val routeResolver: MediaRouteResolver,
        private val fetchPolicy: LinkPreviewFetchPolicy,
        @ApplicationScope private val applicationScope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : LinkPreviewRepository {
        // LruCache does not permit null values, so wrap results in an Optional-ish holder.
        private val cache = LruCache<String, Holder>(CACHE_SIZE)
        private val inFlight = ConcurrentHashMap<String, Deferred<Holder>>()
        private val fetchPermits = Semaphore(fetchPolicy.maxConcurrentFetches)

        override fun cachedPreview(
            url: String,
            networkId: Long?,
        ): CachedLinkPreview? =
            synchronized(cache) {
                cache.get(cacheKey(url, networkId))?.let { CachedLinkPreview(it.value) }
            }

        override suspend fun preview(
            url: String,
            networkId: Long?,
        ): LinkPreview? {
            // Gate before even consulting cached metadata: disabled means neither network nor render.
            if (!contentPreviewPrefs.config.first().showLinkPreviews) return null
            cachedPreview(url, networkId)?.let { return it.preview }
            return sharedFetch(url, networkId).await().value
        }

        /**
         * The process-owned request survives one lazy row leaving composition: other rows (or a later
         * recycle of the same row) can join it, and its completed positive/negative value is retained.
         * Keyed per network as well as per URL because the route (and therefore the answer) differs.
         */
        private fun sharedFetch(
            url: String,
            networkId: Long?,
        ): Deferred<Holder> {
            val key = cacheKey(url, networkId)
            val created =
                applicationScope.async(ioDispatcher, start = CoroutineStart.LAZY) {
                    val result =
                        try {
                            // The permit bounds concurrent in-flight fetches; the deadline bounds each whole
                            // fetch including redirects and body reads.
                            fetchPermits.withPermit {
                                withTimeout(fetchPolicy.fetchDeadlineMs) { fetchRouted(url, networkId) }
                            }
                        } catch (_: TimeoutCancellationException) {
                            // The deadline is a completed negative result, not a caller cancellation.
                            null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    Holder(result).also { holder ->
                        synchronized(cache) {
                            cache.put(key, holder)
                        }
                    }
                }
            val existing = inFlight.putIfAbsent(key, created)
            if (existing != null) {
                created.cancel()
                return existing
            }
            created.invokeOnCompletion { inFlight.remove(key, created) }
            created.start()
            return created
        }

        private suspend fun fetchRouted(
            url: String,
            networkId: Long?,
        ): LinkPreview? {
            // Fail closed: without a network identity there is no way to know whether this content
            // belongs to a proxied network, so no request is made at all. The null-keyed negative
            // result cannot shadow a later fetch made once the identity is known.
            if (networkId == null) return null
            val route = routeResolver.routeForNetwork(networkId) ?: return null
            return try {
                // Fail closed: a proxied network whose proxy cannot be established must not fall back
                // to a direct fetch that would reveal the client address.
                if (route.proxyError != null) null else fetch(url, route)
            } finally {
                route.close()
            }
        }

        private suspend fun fetch(
            url: String,
            route: NetworkMediaRoute,
        ): LinkPreview? =
            suspendCancellableCoroutine { continuation ->
                val connection = AtomicReference<HttpURLConnection?>()
                val worker = AtomicReference<Job?>()
                continuation.invokeOnCancellation {
                    // HttpURLConnection reads do not reliably honor interruption. Detach the caller
                    // immediately, close asynchronously because disconnect itself may block, and bound
                    // any reluctant worker by the existing five-second socket timeout.
                    worker.get()?.cancel()
                    connection.get()?.let { conn -> applicationScope.launch(ioDispatcher) { conn.disconnect() } }
                }
                val job =
                    applicationScope.launch(ioDispatcher) {
                        try {
                            // Wikimedia's summary response is purpose-built for link previews. Fall back to
                            // the ordinary HTML parser when a page or language edition does not expose it.
                            val summaryUrl = wikipediaSummaryUrl(url)
                            val summary =
                                if (summaryUrl == null) {
                                    null
                                } else {
                                    try {
                                        fetchWikipediaSummary(url, summaryUrl, route, connection)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        // A summary outage must not suppress metadata available from the page.
                                        if (!isActive) return@launch
                                        null
                                    }
                                }
                            if (!isActive) return@launch
                            val result = summary ?: fetchGenericPreview(url, route, connection)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                worker.set(job)
                if (!continuation.isActive) {
                    job.cancel()
                    connection.get()?.let { conn -> applicationScope.launch(ioDispatcher) { conn.disconnect() } }
                }
            }

        private fun fetchWikipediaSummary(
            articleUrl: String,
            summaryUrl: String,
            route: NetworkMediaRoute,
            connection: AtomicReference<HttpURLConnection?>,
        ): LinkPreview? =
            request(summaryUrl, WIKIPEDIA_ACCEPT, route, connection) { conn ->
                val contentType = conn.getHeaderField("Content-Type")
                if (contentType?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) != true) {
                    return@request null
                }
                parseWikipediaSummary(
                    articleUrl,
                    conn.inputStream.readCapped(WIKIPEDIA_MAX_BYTES, charsetFromContentType(contentType)),
                )
            }

        private fun fetchGenericPreview(
            url: String,
            route: NetworkMediaRoute,
            connection: AtomicReference<HttpURLConnection?>,
        ): LinkPreview? =
            request(url, GENERIC_ACCEPT, route, connection) { conn ->
                val contentType = conn.getHeaderField("Content-Type")
                when (val kind = responseKind(contentType)) {
                    LinkPreviewKind.WEB -> {
                        parseOgTags(
                            conn.url.toString(),
                            conn.inputStream.readCapped(HTML_MAX_BYTES, Charsets.UTF_8),
                        )
                    }

                    LinkPreviewKind.TEXT -> {
                        parseTextPreview(
                            conn.url.toString(),
                            conn.inputStream.readCapped(
                                TEXT_MAX_BYTES,
                                charsetFromContentType(conn.getHeaderField("Content-Type")),
                            ),
                        )
                    }

                    LinkPreviewKind.VIDEO, LinkPreviewKind.FILE -> {
                        filePreview(
                            url = conn.url.toString(),
                            contentType = contentType,
                            kind = kind,
                        )
                    }

                    LinkPreviewKind.WIKIPEDIA, null -> {
                        null
                    }
                }
            }

        private fun <T> request(
            url: String,
            accept: String,
            route: NetworkMediaRoute,
            connection: AtomicReference<HttpURLConnection?>,
            read: (HttpURLConnection) -> T?,
        ): T? {
            var current = url
            var redirects = 0
            while (true) {
                val parsed = runCatching { URL(current) }.getOrNull() ?: return null
                // Validate every hop, not only the first URL — redirect targets are equally
                // attacker-controlled. DNS-based checks stay off when a proxy is in use: a local
                // lookup would leak exactly what the proxy exists to hide, and the proxy end resolves
                // remotely. Literal-address and scheme checks always apply.
                if (fetchPolicy.enforceDestinationPolicy &&
                    !isAllowedDestination(parsed, resolveDns = route.proxy == null)
                ) {
                    return null
                }
                // The route can attach a Basic SASL header; previews are always opened
                // unauthenticated so credentials can never travel to an arbitrary host.
                val conn =
                    route.open(current).apply {
                        requestMethod = "GET"
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                        instanceFollowRedirects = false
                        setRequestProperty("Accept", accept)
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                connection.set(conn)
                val next: String
                try {
                    conn.connect()
                    val code = conn.responseCode
                    when {
                        code in 200..299 -> {
                            return read(conn)
                        }

                        code in REDIRECT_CODES -> {
                            val location = conn.getHeaderField("Location") ?: return null
                            next = runCatching { URL(parsed, location).toString() }.getOrNull() ?: return null
                        }

                        else -> {
                            return null
                        }
                    }
                } finally {
                    connection.compareAndSet(conn, null)
                    conn.disconnect()
                }
                if (++redirects > fetchPolicy.maxRedirects) return null
                current = next
            }
        }

        private fun InputStream.readCapped(
            max: Int,
            charset: Charset,
        ): String {
            val buf = ByteArray(8 * 1024)
            val out = ByteArray(max)
            var total = 0
            while (total < max) {
                val read = read(buf, 0, minOf(buf.size, max - total))
                if (read == -1) break
                System.arraycopy(buf, 0, out, total, read)
                total += read
            }
            return String(out, 0, total, charset)
        }

        companion object {
            private const val CACHE_SIZE = 256
            private const val TIMEOUT_MS = 5_000
            private const val HTML_MAX_BYTES = 512 * 1024
            private const val TEXT_MAX_BYTES = 16 * 1024
            private const val WIKIPEDIA_MAX_BYTES = 128 * 1024
            private const val TEXT_MAX_CODE_POINTS = 2_048

            // OG/title metadata lives in <head>; bounding the scanned region keeps the tag regexes
            // linear on attacker-sized documents.
            private const val HEAD_SCAN_MAX_CHARS = 64 * 1024
            private const val GENERIC_ACCEPT = "text/html, text/*, application/json, application/xml, video/*, application/*;q=0.1"
            private const val WIKIPEDIA_ACCEPT = "application/json"
            private const val USER_AGENT = "motd-Android (https://github.com/trevarj/motd)"
            private const val WIKIPEDIA_SITE_NAME = "Wikipedia"
            private val JSON = Json { ignoreUnknownKeys = true }
            private val WIKIPEDIA_HOST = Regex("""(?:^|[.])wikipedia[.]org$""", RegexOption.IGNORE_CASE)
            private val WIKIPEDIA_WHITESPACE = Regex("""\s+""")
            private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
            private val IPV4_LITERAL = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")

            private fun cacheKey(
                url: String,
                networkId: Long?,
            ): String = "$networkId|$url"

            /**
             * Per-hop SSRF policy: HTTPS only, and never a destination inside the local machine or
             * private network. [resolveDns] performs the lookup locally for direct connections; via a
             * proxy the literal checks still apply but the hostname is left for the proxy to resolve.
             */
            internal fun isAllowedDestination(
                url: URL,
                resolveDns: Boolean,
            ): Boolean {
                if (!url.protocol.equals("https", ignoreCase = true)) return false
                val host =
                    url.host
                        .orEmpty()
                        .removePrefix("[")
                        .removeSuffix("]")
                        .trimEnd('.')
                if (host.isEmpty()) return false
                ipLiteralOrNull(host)?.let { return !isDisallowedAddress(it) }
                if (!resolveDns) return true
                return try {
                    InetAddress.getAllByName(host).none(::isDisallowedAddress)
                } catch (_: Exception) {
                    // Unresolvable hosts cannot be classified, so they are not connected to either.
                    false
                }
            }

            /** Literal-only parse — [InetAddress.getByName] never queries DNS for address literals. */
            private fun ipLiteralOrNull(host: String): InetAddress? {
                val looksLikeLiteral = host.contains(':') || IPV4_LITERAL.matches(host)
                if (!looksLikeLiteral) return null
                return runCatching { InetAddress.getByName(host) }.getOrNull()
            }

            internal fun isDisallowedAddress(address: InetAddress): Boolean =
                address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                    address.isAnyLocalAddress || address.isMulticastAddress ||
                    // IPv6 unique-local fc00::/7, which isSiteLocalAddress does not cover.
                    (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC)

            internal fun responseKind(contentType: String?): LinkPreviewKind? {
                val mediaType =
                    contentType
                        ?.substringBefore(';')
                        ?.trim()
                        ?.lowercase()
                        .orEmpty()
                return when {
                    mediaType == "text/html" -> LinkPreviewKind.WEB

                    mediaType.startsWith("text/") -> LinkPreviewKind.TEXT

                    mediaType == "application/json" || mediaType == "application/xml" ||
                        (mediaType.startsWith("application/") && (mediaType.endsWith("+json") || mediaType.endsWith("+xml"))) -> LinkPreviewKind.TEXT

                    mediaType.startsWith("video/") -> LinkPreviewKind.VIDEO

                    mediaType.startsWith("audio/") || mediaType.startsWith("image/") || mediaType.isBlank() -> null

                    else -> LinkPreviewKind.FILE
                }
            }

            internal fun charsetFromContentType(contentType: String?): Charset {
                val value =
                    Regex("(?:^|;)\\s*charset\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|([^;\\s]*))", RegexOption.IGNORE_CASE)
                        .find(contentType.orEmpty())
                        ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                return runCatching { value?.takeIf(String::isNotBlank)?.let(Charset::forName) ?: Charsets.UTF_8 }
                    .getOrDefault(Charsets.UTF_8)
            }

            internal fun sanitizeText(text: String): String? {
                val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                val out = StringBuilder()
                var kept = 0
                var index = 0
                while (index < normalized.length && kept < TEXT_MAX_CODE_POINTS) {
                    val codePoint = normalized.codePointAt(index)
                    index += Character.charCount(codePoint)
                    val type = Character.getType(codePoint)
                    if (codePoint == '\n'.code || codePoint == '\t'.code ||
                        (type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt())
                    ) {
                        out.appendCodePoint(codePoint)
                        kept++
                    }
                }
                return out.toString().takeIf { it.isNotBlank() }
            }

            internal fun textTitle(url: String): String {
                val parsed = URL(url)
                val segment =
                    parsed.path
                        .split('/')
                        .lastOrNull { it.isNotBlank() }
                        ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
                return segment?.takeIf(String::isNotBlank) ?: parsed.host
            }

            internal fun parseTextPreview(
                url: String,
                text: String,
            ): LinkPreview? {
                val body = sanitizeText(text) ?: return null
                val host = URL(url).host
                return LinkPreview(url, textTitle(url), body, null, host, LinkPreviewKind.TEXT)
            }

            internal fun filePreview(
                url: String,
                contentType: String?,
                kind: LinkPreviewKind,
            ): LinkPreview =
                LinkPreview(
                    url = url,
                    title = textTitle(url),
                    description = contentType?.substringBefore(';')?.trim()?.takeIf(String::isNotBlank),
                    imageUrl = null,
                    siteName = URL(url).host,
                    kind = kind,
                )

            internal fun wikipediaSummaryUrl(url: String): String? {
                val parsed = runCatching { URL(url) }.getOrNull() ?: return null
                if (parsed.protocol != "http" && parsed.protocol != "https") return null
                if (!WIKIPEDIA_HOST.containsMatchIn(parsed.host)) return null
                val title =
                    when {
                        parsed.path.startsWith("/wiki/") -> {
                            parsed.path.removePrefix("/wiki/")
                        }

                        parsed.path == "/w/index.php" -> {
                            parsed.query
                                ?.split('&')
                                ?.firstOrNull { it.startsWith("title=") }
                                ?.substringAfter('=')
                                ?.replace("+", "%20")
                        }

                        else -> {
                            null
                        }
                    }?.takeIf(String::isNotBlank) ?: return null
                val canonicalHost = parsed.host.replace(".m.wikipedia.org", ".wikipedia.org")
                return "https://$canonicalHost/api/rest_v1/page/summary/$title"
            }

            internal fun parseWikipediaSummary(
                url: String,
                rawJson: String,
            ): LinkPreview? =
                runCatching {
                    val root =
                        JSON.parseToJsonElement(rawJson) as? JsonObject
                            ?: return@runCatching null
                    val title = root.string("title")?.cleanWikipediaText()
                    val extract =
                        (root.string("extract") ?: root.string("description"))
                            ?.cleanWikipediaText()
                    val image =
                        (root["thumbnail"] as? JsonObject)
                            ?.string("source")
                            ?.takeIf(::isHttpUrl)
                    if (title == null && extract == null && image == null) {
                        null
                    } else {
                        LinkPreview(
                            url = url,
                            title = title,
                            description = extract,
                            imageUrl = image,
                            siteName = WIKIPEDIA_SITE_NAME,
                            kind = LinkPreviewKind.WIKIPEDIA,
                        )
                    }
                }.getOrNull()

            private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

            private fun String.cleanWikipediaText(): String? = sanitizeText(this)?.replace(WIKIPEDIA_WHITESPACE, " ")?.trim()?.takeIf(String::isNotEmpty)

            private fun isHttpUrl(value: String): Boolean =
                runCatching {
                    val protocol = URL(value).protocol
                    protocol == "http" || protocol == "https"
                }.getOrDefault(false)

            // <meta property="og:*" content="..."> in either attribute order, single or double quotes.
            private val OG_TITLE = ogRegex("og:title")
            private val OG_DESCRIPTION = ogRegex("og:description")
            private val OG_IMAGE = ogRegex("og:image")
            private val OG_SITE_NAME = ogRegex("og:site_name")
            private val OG_TYPE = ogRegex("og:type")
            private val OG_VIDEO = ogRegex("og:video")
            private val POPULAR_VIDEO_HOSTS =
                setOf(
                    "youtube.com",
                    "youtu.be",
                    "vimeo.com",
                    "dailymotion.com",
                    "twitch.tv",
                    "streamable.com",
                    "tiktok.com",
                )

            // [^<]* instead of a DOT_MATCHES_ALL lazy span: the old form backtracked quadratically on
            // attacker HTML; a title's visible text cannot contain '<' anyway.
            private val TITLE_TAG = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)

            private fun ogRegex(property: String): Regex {
                val p = Regex.escape(property)
                // content-before-property and property-before-content variants.
                val pattern =
                    "<meta[^>]*?property\\s*=\\s*[\"']$p[\"'][^>]*?content\\s*=\\s*[\"'](.*?)[\"'][^>]*?>" +
                        "|<meta[^>]*?content\\s*=\\s*[\"'](.*?)[\"'][^>]*?property\\s*=\\s*[\"']$p[\"'][^>]*?>"
                return Regex(pattern, RegexOption.IGNORE_CASE)
            }

            private fun Regex.firstGroup(html: String): String? {
                val m = find(html) ?: return null
                return (
                    m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                        ?: m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
                )
                    // Same sanitizer as the TEXT and Wikipedia paths: strips CONTROL/FORMAT code
                    // points (e.g. U+202E spoofing) and caps the retained length.
                    ?.let(::decodeEntities)
                    ?.let(::sanitizeText)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }

            // Minimal HTML entity decode for the handful common in OG text.
            private fun decodeEntities(s: String): String =
                s
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&#x27;", "'")

            internal fun isPopularVideoUrl(url: String): Boolean =
                runCatching {
                    val host = URL(url).host.lowercase().trimEnd('.')
                    POPULAR_VIDEO_HOSTS.any { root -> host == root || host.endsWith(".$root") }
                }.getOrDefault(false)

            /** The region OG metadata may live in: up to the first `</head>` capped at 64 KB. */
            internal fun headRegion(html: String): String {
                val headEnd = html.indexOf("</head", ignoreCase = true).let { if (it < 0) html.length else it }
                return html.take(minOf(headEnd, HEAD_SCAN_MAX_CHARS))
            }

            /** Pure OG/title extractor — unit-tested directly against fixture HTML. */
            fun parseOgTags(
                url: String,
                html: String,
            ): LinkPreview? {
                val head = headRegion(html)
                val title =
                    OG_TITLE.firstGroup(head)
                        ?: TITLE_TAG
                            .find(head)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.let(::decodeEntities)
                            ?.let(::sanitizeText)
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                val description = OG_DESCRIPTION.firstGroup(head)
                // Same scheme guard as the Wikipedia thumbnail: the OG image reaches the image
                // stack, so file://, content:// and javascript: sources must never be serviced.
                val image = OG_IMAGE.firstGroup(head)?.takeIf(::isHttpUrl)
                val declaredSiteName = OG_SITE_NAME.firstGroup(head)
                val video =
                    isPopularVideoUrl(url) ||
                        OG_TYPE.firstGroup(head)?.startsWith("video", ignoreCase = true) == true ||
                        OG_VIDEO.firstGroup(head) != null
                // Nothing extractable → treat as no preview (negative-cacheable).
                if (title == null && description == null && image == null && declaredSiteName == null && !video) {
                    return null
                }
                // og:site_name is self-declared, so a hostile page could brand itself as any trusted
                // site. Provenance is the host the transport actually fetched from (post-redirect).
                val host = runCatching { URL(url).host }.getOrNull()
                return LinkPreview(
                    url = url,
                    title = title ?: host.takeIf { video },
                    description = description,
                    imageUrl = image,
                    siteName = host,
                    kind = if (video) LinkPreviewKind.VIDEO else LinkPreviewKind.WEB,
                )
            }
        }

        private class Holder(
            val value: LinkPreview?,
        )
    }
