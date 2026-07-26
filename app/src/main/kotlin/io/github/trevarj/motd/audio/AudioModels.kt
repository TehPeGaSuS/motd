package io.github.trevarj.motd.audio

import io.github.trevarj.motd.ui.chat.InlineTextSegment
import io.github.trevarj.motd.ui.chat.extractUrls
import io.github.trevarj.motd.ui.chat.parseInlineCode
import io.github.trevarj.motd.ui.chat.trimUrl
import java.net.URI
import java.util.Locale

private val AUDIO_EXTENSIONS = setOf(
    "mp3",
    "opus",
    "ogg",
    "oga",
    "m4a",
    "aac",
    "wav",
    "flac",
    "webm",
)

private val AUDIO_MIME_BY_EXTENSION = mapOf(
    "mp3" to "audio/mpeg",
    "opus" to "audio/ogg",
    "ogg" to "audio/ogg",
    "oga" to "audio/ogg",
    "m4a" to "audio/mp4",
    "aac" to "audio/aac",
    "wav" to "audio/wav",
    "flac" to "audio/flac",
    "webm" to "audio/webm",
)

private val VOICE_FALLBACK = Regex(
    """\[voice( encrypted)?\s+([0-9]+(?::[0-9]{2}){0,2})\s+([^\]\s]+)(?:\s+expires=([^\]\s]+))?]\s+(https?://[^\s<>]+)""",
    RegexOption.IGNORE_CASE,
)

data class AudioAttachment(
    val url: String,
    val displayUrl: String = url,
    val title: String = audioTitle(url),
    val mimeType: String? = audioMimeTypeForUrl(url),
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val voice: Boolean = false,
    val encrypted: Boolean = false,
    val expiry: String? = null,
    val cleartextHttp: Boolean = url.startsWith("http://", ignoreCase = true),
    val discoveredByHead: Boolean = false,
    val waveform: AudioWaveform? = audioWaveformFromUrl(url),
) {
    val playbackId: String = "${if (voice) "voice" else "audio"}:$url"
}

data class AudioMetadata(
    val url: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val durationMs: Long? = null,
)

fun parseAudioAttachments(text: String): List<AudioAttachment> {
    val attachments = LinkedHashMap<String, AudioAttachment>()
    for (segment in parseInlineCode(text)) {
        if (segment !is InlineTextSegment.Plain) continue
        parseVoiceFallbacks(segment.text).forEach { attachment ->
            attachments[attachment.url] = attachment
        }
    }
    for (url in extractUrls(text)) {
        if (!isImmediateAudioUrl(url)) continue
        attachments.putIfAbsent(
            url,
            AudioAttachment(
                url = url,
                mimeType = audioMimeTypeForUrl(url),
            ),
        )
    }
    return attachments.values.toList()
}

fun extensionlessAudioCandidates(text: String): List<String> =
    extractUrls(text).filter(::isExtensionlessHttpsAudioCandidate)

fun displayTextForAudioMessage(text: String, attachments: List<AudioAttachment>): String {
    if (attachments.size != 1 || !attachments.first().voice) return text
    for (segment in parseInlineCode(text)) {
        if (segment !is InlineTextSegment.Plain) return text
    }
    return if (VOICE_FALLBACK.matches(text.trim())) "" else text
}

fun AudioMetadata.toAttachment(voice: Boolean = false): AudioAttachment = AudioAttachment(
    url = url,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    durationMs = durationMs,
    voice = voice,
    discoveredByHead = true,
)

fun isImmediateAudioUrl(url: String): Boolean = audioExtension(url) in AUDIO_EXTENSIONS

fun isExtensionlessHttpsAudioCandidate(url: String): Boolean {
    if (!url.startsWith("https://", ignoreCase = true)) return false
    val path = runCatching { URI(url).path.orEmpty() }.getOrElse { url.substringBefore('?').substringBefore('#') }
    val last = path.substringAfterLast('/', "")
    return last.isNotBlank() && '.' !in last
}

fun audioMimeTypeForUrl(url: String): String? = AUDIO_MIME_BY_EXTENSION[audioExtension(url)]

fun audioTitle(url: String): String {
    val parsed = runCatching { URI(url) }.getOrNull()
    val path = parsed?.path.orEmpty()
    val name = path.substringAfterLast('/').takeIf { it.isNotBlank() }
    return name ?: parsed?.host?.takeIf { it.isNotBlank() } ?: "Audio"
}

fun formatAudioDuration(durationMs: Long?): String =
    durationMs?.takeIf { it >= 0 }?.let { millis ->
        val total = millis / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        if (hours > 0) {
            "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.ROOT, minutes, seconds)
        }
    } ?: "--:--"

internal fun parseAudioDuration(value: String): Long? {
    val parts = value.split(':').mapNotNull { it.toLongOrNull() }
    if (parts.isEmpty() || parts.size > 3) return null
    val seconds = when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60 + parts[1]
        else -> parts[0] * 3600 + parts[1] * 60 + parts[2]
    }
    return seconds.takeIf { it >= 0 }?.let { it * 1000 }
}

private fun parseVoiceFallbacks(text: String): List<AudioAttachment> =
    VOICE_FALLBACK.findAll(text).mapNotNull { match ->
        val encrypted = match.groupValues[1].isNotBlank()
        val duration = parseAudioDuration(match.groupValues[2])
        val mime = match.groupValues[3].takeIf { it.contains('/') }
        val expiry = match.groupValues[4].takeIf(String::isNotBlank)
        val url = trimUrl(match.groupValues[5])
        AudioAttachment(
            url = url,
            displayUrl = url,
            title = "Voice message",
            mimeType = mime ?: audioMimeTypeForUrl(url),
            durationMs = duration,
            voice = true,
            encrypted = encrypted,
            expiry = expiry,
        )
    }.toList()

private fun audioExtension(url: String): String {
    val path = runCatching { URI(url).path.orEmpty() }.getOrElse {
        url.substringBefore('?').substringBefore('#')
    }
    return path.substringAfterLast('.', "").lowercase(Locale.ROOT)
}
