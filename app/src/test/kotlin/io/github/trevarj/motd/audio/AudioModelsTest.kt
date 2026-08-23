package io.github.trevarj.motd.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioModelsTest {
    @Test fun parsesAudioLinksByExtensionAndSkipsInlineCode() {
        val attachments =
            parseAudioAttachments(
                "listen https://cdn.example/a/show.mp3 and `https://cdn.example/a/hidden.opus` plus https://cdn.example/a/take.opus?dl=1",
            )

        assertEquals(
            listOf("https://cdn.example/a/show.mp3", "https://cdn.example/a/take.opus?dl=1"),
            attachments.map { it.url },
        )
        assertEquals("audio/mpeg", attachments[0].mimeType)
        assertEquals("audio/ogg", attachments[1].mimeType)
    }

    @Test fun parsesVoiceFallbackMetadata() {
        val attachment =
            parseAudioAttachments(
                "[voice encrypted 1:02 audio/ogg expires=72h] https://files.example/voice.motdvoice#motd-key=abc",
            ).single()

        assertTrue(attachment.voice)
        assertTrue(attachment.encrypted)
        assertEquals(62_000L, attachment.durationMs)
        assertEquals("audio/ogg", attachment.mimeType)
        assertEquals("72h", attachment.expiry)
        assertEquals("Voice message", attachment.title)
    }

    @Test fun hidesPureVoiceFallbackText() {
        val text = "[voice 0:03 audio/mp4] https://files.example/voice.m4a"
        val attachments = parseAudioAttachments(text)

        assertEquals("", displayTextForAudioMessage(text, attachments))
        assertEquals("before $text", displayTextForAudioMessage("before $text", attachments))
    }

    @Test fun findsOnlyExtensionlessHttpsHeadCandidates() {
        val candidates =
            extensionlessAudioCandidates(
                "https://files.example/abc http://files.example/def https://files.example/a.mp3 https://files.example/path/",
            )

        assertEquals(listOf("https://files.example/abc"), candidates)
        assertFalse(candidates.any { it.startsWith("http://") })
    }

    @Test fun downloadProgressUsesActualCachedBytes() {
        assertEquals(0.25f, audioDownloadFraction(cachedBytes = 250, totalBytes = 1_000))
        assertEquals(0f, audioDownloadFraction(cachedBytes = -1, totalBytes = 1_000))
        assertEquals(1f, audioDownloadFraction(cachedBytes = 2_000, totalBytes = 1_000))
        assertNull(audioDownloadFraction(cachedBytes = 250, totalBytes = -1))
    }
}
