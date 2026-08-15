package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.event.IrcClientState
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusNotificationTextTest {
    private fun ready(nick: String) = IrcClientState.Ready(nick, emptySet(), emptyMap())

    @Test
    fun a_live_session_is_never_reported_as_starting() {
        // The service is entered again on a session that is already connected (every foreground
        // re-arms the keeper under PERSISTENT_SOCKET, and START_STICKY redelivers after a kill).
        // Reporting that as "starting" is what reverted a truthful count to the generic text, with
        // nothing left to repaint it: connectionStates is conflated and emits nothing further.
        val states = mapOf(
            1L to ready("me"),
            2L to ready("me"),
            3L to ready("me"),
        )

        assertEquals(
            StatusNotificationShape(connectedCount = 3, reconnecting = false, starting = false),
            statusNotificationShape(states),
        )
        assertEquals(
            "Connected to 3 networks",
            statusNotificationText(connectedCount = 3, reconnecting = false, starting = false),
        )
    }

    @Test
    fun no_actors_at_all_is_the_only_starting_shape() {
        assertEquals(
            StatusNotificationShape(connectedCount = 0, reconnecting = false, starting = true),
            statusNotificationShape(emptyMap()),
        )
    }

    @Test
    fun reconnecting_is_reported_only_while_nothing_is_connected() {
        assertEquals(
            StatusNotificationShape(connectedCount = 0, reconnecting = true, starting = false),
            statusNotificationShape(mapOf(1L to IrcClientState.Connecting)),
        )
        // One flapping network must not hide the ones that are up.
        assertEquals(
            StatusNotificationShape(connectedCount = 1, reconnecting = false, starting = false),
            statusNotificationShape(mapOf(1L to ready("me"), 2L to IrcClientState.Registering)),
        )
    }

    @Test
    fun initial_service_state_is_neutral() {
        assertEquals(
            "Keeping chats connected",
            statusNotificationText(connectedCount = 0, reconnecting = false, starting = true),
        )
    }

    @Test
    fun sustained_reconnect_and_connected_states_remain_explicit() {
        assertEquals(
            "Reconnecting…",
            statusNotificationText(connectedCount = 0, reconnecting = true, starting = false),
        )
        assertEquals(
            "Connected to 2 networks",
            statusNotificationText(connectedCount = 2, reconnecting = false, starting = false),
        )
    }
}
