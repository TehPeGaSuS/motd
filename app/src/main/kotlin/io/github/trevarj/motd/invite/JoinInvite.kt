package io.github.trevarj.motd.invite

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val INVITE_VERSION = 1
private const val MAX_ENCODED_PAYLOAD = 2_048
private const val FALLBACK_URL = "https://github.com/trevarj/motd/releases/latest"
private val PIN_PATTERN = Regex("[0-9a-fA-F]{64}")
private val CHANNEL_PREFIXES = setOf('#', '&', '+', '!')

@Serializable
data class JoinInviteV1(
    val v: Int = INVITE_VERSION,
    val networkName: String,
    val host: String,
    val port: Int,
    val tls: Boolean = true,
    val channel: String,
    val channelKey: String? = null,
    val certSha256: String? = null,
)

class InvalidJoinInviteException(
    message: String,
) : IllegalArgumentException(message)

/** Strict codec for QR/deep-link data. No field can contain IRC commands or line breaks. */
object JoinInviteCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(invite: JoinInviteV1): String {
        val valid = validate(invite)
        val encoded =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                json.encodeToString(valid).toByteArray(StandardCharsets.UTF_8),
            )
        require(encoded.length <= MAX_ENCODED_PAYLOAD) { "invite is too large" }
        return encoded
    }

    fun decode(encoded: String): JoinInviteV1 {
        if (encoded.isEmpty() || encoded.length > MAX_ENCODED_PAYLOAD) invalid("invalid invite size")
        val bytes =
            try {
                Base64.getUrlDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                invalid("invalid invite encoding")
            }
        val invite =
            try {
                json.decodeFromString<JoinInviteV1>(bytes.toString(StandardCharsets.UTF_8))
            } catch (_: Exception) {
                invalid("invalid invite data")
            }
        return validate(invite)
    }

    fun appUri(invite: JoinInviteV1): String = "motd://invite?v=${encode(invite)}"

    /** HTTPS works in generic QR readers; fragment stays local to browser and carries invite for second scan. */
    fun installUri(invite: JoinInviteV1): String = "$FALLBACK_URL#motd-invite=${encode(invite)}"

    /** Scanner/paste fallback also accepts the compact payload code by itself. */
    fun parseScanned(raw: String): JoinInviteV1 = runCatching { parse(raw) }.getOrElse { decode(raw.trim()) }

    /** Accepts canonical motd URI or exact GitHub Releases HTTPS envelope emitted by [installUri]. */
    fun parse(raw: String): JoinInviteV1 {
        val trimmed = raw.trim()
        if (trimmed.length > MAX_ENCODED_PAYLOAD + 512) invalid("invite link is too large")
        val uri = runCatching { URI(trimmed) }.getOrElse { invalid("invalid invite link") }
        return when {
            uri.scheme.equals("motd", ignoreCase = true) && uri.host.equals("invite", ignoreCase = true) -> {
                val values = parseQuery(uri.rawQuery)
                if (values.keys != setOf("v") || uri.rawFragment != null) invalid("invalid invite parameters")
                decode(values.getValue("v"))
            }

            uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true) -> {
                if (uri.rawPath != "/trevarj/motd/releases/latest" || uri.rawQuery != null || uri.userInfo != null || uri.port != -1) {
                    invalid("not a motd invite")
                }
                val marker = "motd-invite="
                val fragment = uri.rawFragment ?: invalid("missing invite payload")
                if (!fragment.startsWith(marker)) invalid("invalid invite parameters")
                decode(fragment.removePrefix(marker))
            }

            else -> {
                invalid("not a motd invite")
            }
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) invalid("missing invite payload")
        val pairs =
            raw.split('&').map { item ->
                val key = item.substringBefore('=', "")
                if (key.isEmpty() || '=' !in item) invalid("invalid invite parameters")
                key to URLDecoder.decode(item.substringAfter('='), StandardCharsets.UTF_8.name())
            }
        if (pairs.map { it.first }.distinct().size != pairs.size) invalid("invalid invite parameters")
        return pairs.toMap()
    }

    private fun validate(invite: JoinInviteV1): JoinInviteV1 {
        if (invite.v != INVITE_VERSION) invalid("unsupported invite version")
        val networkName = cleanText(invite.networkName, 80, "network name")
        val host = validateHost(invite.host)
        if (invite.port !in 1..65535) invalid("invalid server port")
        val channel = cleanToken(invite.channel, 200, "channel")
        if (channel.firstOrNull() !in CHANNEL_PREFIXES) invalid("invalid channel")
        val key = invite.channelKey?.takeIf(String::isNotEmpty)?.let { cleanToken(it, 300, "channel key") }
        val pin = invite.certSha256?.lowercase()?.also { if (!PIN_PATTERN.matches(it)) invalid("invalid certificate pin") }
        if (!invite.tls && pin != null) invalid("plaintext invite cannot carry a certificate pin")
        return invite.copy(
            networkName = networkName,
            host = host,
            channel = channel,
            channelKey = key,
            certSha256 = pin,
        )
    }

    private fun validateHost(raw: String): String {
        val host = cleanToken(raw.trim().removePrefix("[").removeSuffix("]"), 253, "server host")
        if (host.any { it in "/@?#" }) invalid("invalid server host")
        if (':' !in host) {
            val ascii = runCatching { IDN.toASCII(host) }.getOrElse { invalid("invalid server host") }
            if (ascii.isEmpty() || ascii.length > 253 || ascii.split('.').any { it.isEmpty() || it.length > 63 }) {
                invalid("invalid server host")
            }
        } else if (!host.matches(Regex("[0-9A-Fa-f:.%]+"))) {
            invalid("invalid server host")
        }
        return host
    }

    private fun cleanText(
        raw: String,
        maxBytes: Int,
        label: String,
    ): String {
        val value = raw.trim()
        if (value.isEmpty() || value.hasControls() || value.toByteArray().size > maxBytes) invalid("invalid $label")
        return value
    }

    private fun cleanToken(
        raw: String,
        maxBytes: Int,
        label: String,
    ): String {
        val value = raw.trim()
        if (value.isEmpty() || value.hasControls() || value.any(Char::isWhitespace) || ',' in value || value.toByteArray().size > maxBytes) {
            invalid("invalid $label")
        }
        return value
    }

    private fun String.hasControls(): Boolean = any { it.isISOControl() || it == '\r' || it == '\n' || it == '\u0000' }

    private fun invalid(message: String): Nothing = throw InvalidJoinInviteException(message)
}
