package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ChatSoundDecisionTest {
    @Test
    fun `receive melody follows the configured interval sequence and wraps`() {
        val melody = ChatReceiveMelody()
        val rates =
            listOf(
                1f,
                2.0.pow(2.0 / 12.0).toFloat(),
                2.0.pow(4.0 / 12.0).toFloat(),
                2.0.pow(7.0 / 12.0).toFloat(),
                1f,
            )

        rates.forEachIndexed { index, expected ->
            assertEquals(
                expected,
                melody.playbackRate(ChatSoundCue.RECEIVE, bufferId = 7, nowNanos = index.toLong()),
                0f,
            )
        }
    }

    @Test
    fun `receive melody resets after silence or an eligible buffer change`() {
        val melody = ChatReceiveMelody()
        assertEquals(1f, melody.playbackRate(ChatSoundCue.RECEIVE, 7, nowNanos = 0), 0f)
        assertEquals(
            2.0.pow(2.0 / 12.0).toFloat(),
            melody.playbackRate(ChatSoundCue.RECEIVE, 7, nowNanos = 1),
            0f,
        )
        assertEquals(1f, melody.playbackRate(ChatSoundCue.RECEIVE, 7, nowNanos = 2_000_000_001L), 0f)
        assertEquals(1f, melody.playbackRate(ChatSoundCue.RECEIVE, 8, nowNanos = 2_000_000_002L), 0f)
    }

    @Test
    fun `send cue neither advances nor resets the receive melody`() {
        val melody = ChatReceiveMelody()
        assertEquals(1f, melody.playbackRate(ChatSoundCue.RECEIVE, 7, nowNanos = 0), 0f)
        assertEquals(1f, melody.playbackRate(ChatSoundCue.SEND, 8, nowNanos = 1_000_000_000L), 0f)
        assertEquals(
            2.0.pow(2.0 / 12.0).toFloat(),
            melody.playbackRate(ChatSoundCue.RECEIVE, 7, nowNanos = 1_500_000_000L),
            0f,
        )
    }

    @Test
    fun `sound readiness queues a cue until its sample loads`() {
        val queue = ChatSoundReadinessQueue()

        assertNull(queue.request(ChatSoundCue.RECEIVE, playbackRate = 1.25f, ready = false))

        assertEquals(
            PendingChatSoundPlayback(ChatSoundCue.RECEIVE, playbackRate = 1.25f),
            queue.markLoaded(ChatSoundCue.RECEIVE),
        )
        assertNull(queue.markLoaded(ChatSoundCue.RECEIVE))
    }

    @Test
    fun `sound readiness keeps the latest queued playback rate`() {
        val queue = ChatSoundReadinessQueue()

        assertNull(queue.request(ChatSoundCue.RECEIVE, playbackRate = 1f, ready = false))
        assertNull(queue.request(ChatSoundCue.RECEIVE, playbackRate = 1.5f, ready = false))

        assertEquals(
            PendingChatSoundPlayback(ChatSoundCue.RECEIVE, playbackRate = 1.5f),
            queue.markLoaded(ChatSoundCue.RECEIVE),
        )
    }

    @Test
    fun `sound readiness plays ready samples immediately`() {
        val queue = ChatSoundReadinessQueue()

        assertEquals(
            PendingChatSoundPlayback(ChatSoundCue.SEND, playbackRate = 1f),
            queue.request(ChatSoundCue.SEND, playbackRate = 1f, ready = true),
        )
        assertNull(queue.markLoaded(ChatSoundCue.SEND))
    }

    @Test
    fun `sound readiness drops queued playback after load failure`() {
        val queue = ChatSoundReadinessQueue()

        assertNull(queue.request(ChatSoundCue.SEND, playbackRate = 1f, ready = false))
        queue.markLoadFailed(ChatSoundCue.SEND)

        assertNull(queue.markLoaded(ChatSoundCue.SEND))
    }

    @Test
    fun `send and receive select distinct cues`() {
        assertEquals(
            ChatSoundCue.SEND,
            outgoingChatSoundCue(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                muted = false,
            ),
        )
        assertEquals(
            ChatSoundCue.RECEIVE,
            incomingChatSoundCue(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.CHANNEL,
                muted = false,
                senderIsFool = false,
            ),
        )
    }

    @Test
    fun `incoming sound is limited to the open foreground chat`() {
        assertTrue(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.CHANNEL,
                muted = false,
                senderIsFool = false,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 8,
                bufferId = 7,
                type = BufferType.CHANNEL,
                muted = false,
                senderIsFool = false,
            ),
        )
    }

    @Test
    fun `incoming sound respects master mute fools and server buffers`() {
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = false,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderIsFool = false,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = true,
                senderIsFool = false,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderIsFool = true,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.SERVER,
                muted = false,
                senderIsFool = false,
            ),
        )
    }

    @Test
    fun `outgoing sound requires enabled open unmuted chat`() {
        assertTrue(
            shouldPlayOutgoingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                muted = false,
            ),
        )
        assertFalse(
            shouldPlayOutgoingChatSound(
                enabled = true,
                foregroundBufferId = null,
                bufferId = 7,
                muted = false,
            ),
        )
        assertFalse(
            shouldPlayOutgoingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                muted = true,
            ),
        )
        assertNull(
            outgoingChatSoundCue(
                enabled = true,
                foregroundBufferId = null,
                bufferId = 7,
                muted = false,
            ),
        )
    }

    @Test
    fun `sound fool suppression follows the networks casemapping`() {
        val configured = setOf("listener~")
        val rfc = IrcIdentityRules(caseMapping = IrcCaseMapping.Rfc1459)
        val strict = IrcIdentityRules(caseMapping = IrcCaseMapping.Rfc1459Strict)

        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderIsFool = rfc.matchesConfiguredNick("listener^", configured),
            ),
        )
        assertTrue(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderIsFool = strict.matchesConfiguredNick("listener^", configured),
            ),
        )
    }

    @Test
    fun `sound fool suppression follows canonical account across nick changes`() {
        val rules = IrcIdentityRules(caseMapping = IrcCaseMapping.Ascii)

        assertTrue(
            isFoolForChatSound(
                fools = setOf("stable-account"),
                identityRules = rules,
                senderAccount = "stable-account",
                normalizedActor = rules.normalize("new-nick"),
            ),
        )
        assertFalse(
            isFoolForChatSound(
                fools = setOf("old-nick"),
                identityRules = rules,
                senderAccount = null,
                normalizedActor = rules.normalize("new-nick"),
            ),
        )
    }
}
