package io.github.trevarj.motd.data.repo

import android.util.LruCache
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.di.IoDispatcher
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Declared web/text/media link preview. HttpURLConnection GET, 5s connect/read timeouts, HTML body
// capped at 512 KB, text body capped at 16 KB, and Wikipedia summaries capped at 128 KB.
// Completed negative results live in a bounded process cache, while concurrent callers for the
// same URL await one shared request. The OG parser remains dependency-free; Wikipedia summaries
// use the already-pinned kotlinx.serialization JSON parser.
@Singleton
class LinkPreviewRepositoryImpl @Inject constructor(
    private val contentPreviewPrefs: ContentPreviewPrefs,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LinkPreviewRepository {
    // LruCache does not permit null values, so wrap results in an Optional-ish holder.
    private val cache = LruCache<String, Holder>(CACHE_SIZE)
    private val inFlight = ConcurrentHashMap<String, Deferred<Holder>>()

    override fun cachedPreview(url: String): CachedLinkPreview? =
        synchronized(cache) {
            cache.get(url)?.let { CachedLinkPreview(it.value) }
        }

    override suspend fun preview(url: String): LinkPreview? {
        // Gate before even consulting cached metadata: disabled means neither network nor render.
        if (!contentPreviewPrefs.config.first().showLinkPreviews) return null
        cachedPreview(url)?.let { return it.preview }
        return sharedFetch(url).await().value
    }

    /**
     * The process-owned request survives one lazy row leaving composition: other rows (or a later
     * recycle of the same row) can join it, and its completed positive/negative value is retained.
     */
    private fun sharedFetch(url: String): Deferred<Holder> {
        val created = applicationScope.async(ioDispatcher, start = CoroutineStart.LAZY) {
            val result = try {
                fetch(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            Holder(result).also { holder ->
                synchronized(cache) {
                    cache.put(url, holder)
                }
            }
        }
        val existing = inFlight.putIfAbsent(url, created)
        if (existing != null) {
            created.cancel()
            return existing
        }
        created.invokeOnCompletion { inFlight.remove(url, created) }
        created.start()
        return created
    }

    private suspend fun fetch(url: String): LinkPreview? = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        val worker = AtomicReference<Job?>()
        continuation.invokeOnCancellation {
            // HttpURLConnection reads do not reliably honor interruption. Detach the caller
            // immediately, close asynchronously because disconnect itself may block, and bound
            // any reluctant worker by the existing five-second socket timeout.
            worker.get()?.cancel()
            connection.get()?.let { conn -> applicationScope.launch(ioDispatcher) { conn.disconnect() } }
        }
        val job = applicationScope.launch(ioDispatcher) {
            try {
                // Wikimedia's summary response is purpose-built for link previews. Fall back to
                // the ordinary HTML parser when a page or language edition does not expose it.
                val summaryUrl = wikipediaSummaryUrl(url)
                val summary = if (summaryUrl == null) {
                    null
                } else {
                    try {
                        fetchWikipediaSummary(url, summaryUrl, connection)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A summary outage must not suppress metadata available from the page.
                        if (!isActive) return@launch
                        null
                    }
                }
                if (!isActive) return@launch
                val result = summary ?: fetchGenericPreview(url, connection)
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
        connection: AtomicReference<HttpURLConnection?>,
    ): LinkPreview? = request(summaryUrl, WIKIPEDIA_ACCEPT, connection) { conn ->
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
        connection: AtomicReference<HttpURLConnection?>,
    ): LinkPreview? = request(url, GENERIC_ACCEPT, connection) { conn ->
        val contentType = conn.getHeaderField("Content-Type")
        when (val kind = responseKind(contentType)) {
            LinkPreviewKind.WEB -> parseOgTags(
                conn.url.toString(),
                conn.inputStream.readCapped(HTML_MAX_BYTES, Charsets.UTF_8),
            )
            LinkPreviewKind.TEXT -> parseTextPreview(
                conn.url.toString(),
                conn.inputStream.readCapped(
                    TEXT_MAX_BYTES,
                    charsetFromContentType(conn.getHeaderField("Content-Type")),
                ),
            )
            LinkPreviewKind.VIDEO, LinkPreviewKind.FILE -> filePreview(
                url = conn.url.toString(),
                contentType = contentType,
                kind = kind,
            )
            LinkPreviewKind.WIKIPEDIA, null -> null
        }
    }

    private fun <T> request(
        url: String,
        accept: String,
        connection: AtomicReference<HttpURLConnection?>,
        read: (HttpURLConnection) -> T?,
    ): T? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", USER_AGENT)
        }
        connection.set(conn)
        return try {
            conn.connect()
            if (conn.responseCode in 200..299) read(conn) else null
        } finally {
            connection.compareAndSet(conn, null)
            conn.disconnect()
        }
    }

    private fun InputStream.readCapped(max: Int, charset: Charset): String {
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
        private const val GENERIC_ACCEPT = "text/html, text/*, application/json, application/xml, video/*, application/*;q=0.1"
        private const val WIKIPEDIA_ACCEPT = "application/json"
        private const val USER_AGENT = "motd-Android (https://github.com/trevarj/motd)"
        private const val WIKIPEDIA_SITE_NAME = "Wikipedia"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val WIKIPEDIA_HOST = Regex("""(?:^|[.])wikipedia[.]org$""", RegexOption.IGNORE_CASE)
        private val WIKIPEDIA_WHITESPACE = Regex("""\s+""")

        internal fun responseKind(contentType: String?): LinkPreviewKind? {
            val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
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
            val value = Regex("(?:^|;)\\s*charset\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|([^;\\s]*))", RegexOption.IGNORE_CASE)
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
            val segment = parsed.path.split('/').lastOrNull { it.isNotBlank() }
                ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
            return segment?.takeIf(String::isNotBlank) ?: parsed.host
        }

        internal fun parseTextPreview(url: String, text: String): LinkPreview? {
            val body = sanitizeText(text) ?: return null
            val host = URL(url).host
            return LinkPreview(url, textTitle(url), body, null, host, LinkPreviewKind.TEXT)
        }

        internal fun filePreview(
            url: String,
            contentType: String?,
            kind: LinkPreviewKind,
        ): LinkPreview = LinkPreview(
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
            val title = when {
                parsed.path.startsWith("/wiki/") -> parsed.path.removePrefix("/wiki/")
                parsed.path == "/w/index.php" -> parsed.query
                    ?.split('&')
                    ?.firstOrNull { it.startsWith("title=") }
                    ?.substringAfter('=')
                    ?.replace("+", "%20")
                else -> null
            }?.takeIf(String::isNotBlank) ?: return null
            val canonicalHost = parsed.host.replace(".m.wikipedia.org", ".wikipedia.org")
            return "https://$canonicalHost/api/rest_v1/page/summary/$title"
        }

        internal fun parseWikipediaSummary(url: String, rawJson: String): LinkPreview? =
            runCatching {
                val root = JSON.parseToJsonElement(rawJson) as? JsonObject
                    ?: return@runCatching null
                val title = root.string("title")?.cleanWikipediaText()
                val extract = (root.string("extract") ?: root.string("description"))
                    ?.cleanWikipediaText()
                val image = (root["thumbnail"] as? JsonObject)?.string("source")
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

        private fun JsonObject.string(name: String): String? =
            get(name)?.jsonPrimitive?.contentOrNull

        private fun String.cleanWikipediaText(): String? =
            sanitizeText(this)?.replace(WIKIPEDIA_WHITESPACE, " ")?.trim()?.takeIf(String::isNotEmpty)

        private fun isHttpUrl(value: String): Boolean = runCatching {
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
        private val POPULAR_VIDEO_HOSTS = setOf(
            "youtube.com",
            "youtu.be",
            "vimeo.com",
            "dailymotion.com",
            "twitch.tv",
            "streamable.com",
            "tiktok.com",
        )
        private val TITLE_TAG = Regex(
            "<title[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

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
            return (m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                ?: m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() })
                ?.let(::decodeEntities)?.trim()?.takeIf { it.isNotEmpty() }
        }

        // Minimal HTML entity decode for the handful common in OG text.
        private fun decodeEntities(s: String): String =
            s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")

        internal fun isPopularVideoUrl(url: String): Boolean = runCatching {
            val host = URL(url).host.lowercase().trimEnd('.')
            POPULAR_VIDEO_HOSTS.any { root -> host == root || host.endsWith(".$root") }
        }.getOrDefault(false)

        /** Pure OG/title extractor — unit-tested directly against fixture HTML. */
        fun parseOgTags(url: String, html: String): LinkPreview? {
            val title = OG_TITLE.firstGroup(html)
                ?: TITLE_TAG.find(html)?.groupValues?.getOrNull(1)?.let(::decodeEntities)?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val description = OG_DESCRIPTION.firstGroup(html)
            val image = OG_IMAGE.firstGroup(html)
            val siteName = OG_SITE_NAME.firstGroup(html)
            val video = isPopularVideoUrl(url) ||
                OG_TYPE.firstGroup(html)?.startsWith("video", ignoreCase = true) == true ||
                OG_VIDEO.firstGroup(html) != null
            // Nothing extractable → treat as no preview (negative-cacheable).
            if (title == null && description == null && image == null && siteName == null && !video) {
                return null
            }
            return LinkPreview(
                url = url,
                title = title ?: URL(url).host.takeIf { video },
                description = description,
                imageUrl = image,
                siteName = siteName,
                kind = if (video) LinkPreviewKind.VIDEO else LinkPreviewKind.WEB,
            )
        }
    }

    private class Holder(val value: LinkPreview?)
}
