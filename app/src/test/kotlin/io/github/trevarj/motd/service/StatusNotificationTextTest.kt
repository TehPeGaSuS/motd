package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
        val states =
            mapOf(
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

    /**
     * The service used to notify() on every connectionStates emission. That flow republishes on
     * every per-network transition and every lag reading, so a stable two-network session reposted
     * an identical notification continuously. Only a change in the three fields the wording is built
     * from may reach the shade.
     */
    @Test
    fun only_a_change_in_the_wording_reposts_the_status_notification() =
        runTest {
            val two = mapOf(1L to ready("me"), 2L to ready("me"))
            val shapes =
                statusNotificationShapes(
                    flowOf(
                        emptyMap(),
                        // Both networks come up, one at a time.
                        mapOf(1L to IrcClientState.Connecting),
                        mapOf(1L to ready("me"), 2L to IrcClientState.Connecting),
                        two,
                        // Republications that do not change the wording: an identical map, then a Ready
                        // snapshot mutated by a runtime CAP ACK.
                        two,
                        mapOf(1L to ready("me"), 2L to IrcClientState.Ready("me", setOf("batch"), emptyMap())),
                        // One network drops: the count changes, so this one must be posted.
                        mapOf(1L to ready("me"), 2L to IrcClientState.Connecting),
                    ),
                ).toList()

            assertEquals(
                listOf(
                    StatusNotificationShape(connectedCount = 0, reconnecting = false, starting = true),
                    StatusNotificationShape(connectedCount = 0, reconnecting = true, starting = false),
                    StatusNotificationShape(connectedCount = 1, reconnecting = false, starting = false),
                    StatusNotificationShape(connectedCount = 2, reconnecting = false, starting = false),
                    StatusNotificationShape(connectedCount = 1, reconnecting = false, starting = false),
                ),
                shapes,
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
