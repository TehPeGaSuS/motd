package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfAwayStateTest {
    private val normalize: (String) -> String = { it.lowercase() }

    private fun apply(
        current: Map<Long, String?>,
        event: IrcEvent,
        pendingMessage: String? = null,
        selfNick: String? = "me",
    ) = selfAwayAfterEvent(
        current = current,
        networkId = 1L,
        event = event,
        pendingMessage = pendingMessage,
        selfNick = selfNick,
        normalize = normalize,
    )

    @Test fun `306 marks away and attaches the message this device wrote`() {
        assertEquals(
            mapOf(1L to "lunch"),
            apply(emptyMap(), IrcEvent.SelfAwayChanged(isAway = true, text = "away"), pendingMessage = "lunch"),
        )
    }

    @Test fun `306 without a local write records away with an unknown message`() {
        assertEquals(
            mapOf(1L to null),
            apply(emptyMap(), IrcEvent.SelfAwayChanged(isAway = true, text = "away")),
        )
    }

    @Test fun `305 clears the network only`() {
        assertEquals(
            mapOf(2L to "elsewhere"),
            apply(
                mapOf(1L to "lunch", 2L to "elsewhere"),
                IrcEvent.SelfAwayChanged(isAway = false, text = "back"),
            ),
        )
    }

    @Test fun `disconnect clears the network because nothing is confirmed any more`() {
        assertEquals(
            mapOf(2L to null),
            apply(mapOf(1L to "lunch", 2L to null), IrcEvent.Disconnected("reset")),
        )
    }

    @Test fun `self away-notify echo from another bouncer client attaches its message`() {
        assertEquals(
            mapOf(1L to "afk"),
            apply(emptyMap(), IrcEvent.AwayChanged("ME", "afk")),
        )
        assertEquals(
            emptyMap<Long, String?>(),
            apply(mapOf(1L to "afk"), IrcEvent.AwayChanged("ME", null)),
        )
    }

    @Test fun `another user's away-notify never touches our state`() {
        assertEquals(
            mapOf(1L to "lunch"),
            apply(mapOf(1L to "lunch"), IrcEvent.AwayChanged("alice", null)),
        )
        assertEquals(
            emptyMap<Long, String?>(),
            apply(emptyMap(), IrcEvent.AwayChanged("alice", "afk")),
        )
    }

    @Test fun `unknown self nick leaves away-notify unapplied`() {
        assertEquals(
            emptyMap<Long, String?>(),
            apply(emptyMap(), IrcEvent.AwayChanged("me", "afk"), selfNick = null),
        )
    }

    @Test fun `unrelated events are inert and skipped by the hot-path guard`() {
        val current = mapOf(1L to "lunch")
        val quit =
            IrcEvent.Quit(
                ctx = MessageContext(null, 0L, null, null, null),
                nick = "me",
                reason = null,
            )
        assertEquals(current, apply(current, quit))
        assertFalse(affectsSelfAway(quit))
    }

    @Test fun `the guard names exactly the events the fold can move`() {
        assertTrue(affectsSelfAway(IrcEvent.SelfAwayChanged(isAway = true, text = "away")))
        assertTrue(affectsSelfAway(IrcEvent.AwayChanged("me", "afk")))
        assertTrue(affectsSelfAway(IrcEvent.Disconnected(null)))
    }
}
