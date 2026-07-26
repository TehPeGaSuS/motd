package io.github.trevarj.motd.audio

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioWaveformTest {
    @Test
    fun `five-bit waveform round trips with fixed display peak count`() {
        val waveform = AudioWaveform(List(AudioWaveform.DISPLAY_PEAKS) { it % 32 })

        val decoded = AudioWaveform.decode(waveform.encode())

        assertEquals(waveform, decoded)
    }

    @Test
    fun `invalid waveform metadata is ignored`() {
        assertNull(AudioWaveform.decode("not-base64!"))
        assertNull(audioWaveformFromUrl("https://files.example/voice.ogg#motd-wave=broken"))
    }

    @Test
    fun `voice fallback carries waveform beside encryption key`() {
        val waveform = AudioWaveform.fromAmplitudes(List(96) { index -> index * 300 })
        val url = appendAudioWaveform(
            "https://files.example/voice.motdvoice#motd-key=secret",
            waveform,
        )

        val attachment = parseAudioAttachments("[voice encrypted 0:12 audio/ogg] $url").single()

        assertTrue(attachment.encrypted)
        assertEquals(waveform, attachment.waveform)
        assertTrue(attachment.url.contains("motd-key=secret&motd-wave="))
    }

    @Test
    fun `legacy voice config upgrades missing quality fields`() {
        val config = Json.decodeFromString<VoiceConfig>(
            """{"encryptionDefault":true,"rememberedDestination":null}""",
        )

        assertTrue(config.encryptionDefault)
        assertEquals(VoiceRecordingQuality.BALANCED, config.quality)
        assertTrue(config.noiseReduction)
    }

    @Test
    fun `recording quality maps codec bitrates`() {
        assertEquals(24_000, VoiceRecordingQuality.DATA_SAVER.bitRate(opus = true))
        assertEquals(32_000, VoiceRecordingQuality.DATA_SAVER.bitRate(opus = false))
        assertEquals(48_000, VoiceRecordingQuality.BALANCED.bitRate(opus = true))
        assertEquals(64_000, VoiceRecordingQuality.BALANCED.bitRate(opus = false))
        assertEquals(64_000, VoiceRecordingQuality.HIGH.bitRate(opus = true))
        assertEquals(96_000, VoiceRecordingQuality.HIGH.bitRate(opus = false))
    }

    @Test
    fun `origin labels handle self and direct messages`() {
        val direct = origin(isSelf = false, direct = true, sender = "alice", conversation = "alice")
        val outgoing = origin(isSelf = true, direct = true, sender = "me", conversation = "bob")
        val channel = origin(isSelf = false, direct = false, sender = "alice", conversation = "#motd")

        assertEquals("alice · Direct message", direct.contextLabel())
        assertEquals("You · bob", outgoing.contextLabel())
        assertEquals("alice · #motd · Libera", channel.contextLabel("Libera", includeNetwork = true))
    }

    private fun origin(
        isSelf: Boolean,
        direct: Boolean,
        sender: String,
        conversation: String,
    ) = AudioPlaybackOrigin(
        bufferId = 1,
        networkId = 2,
        conversation = conversation,
        sender = sender,
        isSelf = isSelf,
        directMessage = direct,
        eventId = 3,
        msgid = "message",
        serverTime = 4,
    )
}
