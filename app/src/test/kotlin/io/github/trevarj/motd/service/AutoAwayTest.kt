package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.prefs.AUTO_AWAY_MINUTE_CHOICES
import org.junit.Assert.assertEquals
import org.junit.Test

/** Decision matrix for the pure auto-away helpers. */
class AutoAwayTest {
    @Test
    fun away_targets_skip_networks_that_are_already_away() {
        assertEquals(
            setOf(2L, 3L),
            autoAwayTargets(readyNetworkIds = setOf(1L, 2L, 3L), awayNetworkIds = setOf(1L)),
        )
    }

    @Test
    fun away_targets_skip_networks_that_are_not_ready() {
        assertEquals(emptySet<Long>(), autoAwayTargets(readyNetworkIds = emptySet(), awayNetworkIds = setOf(1L)))
    }

    @Test
    fun markers_survive_only_while_their_network_is_still_away() {
        assertEquals(setOf(1L), retainedMarkers(markedNetworkIds = setOf(1L, 2L), awayNetworkIds = setOf(1L, 9L)))
    }

    @Test
    fun back_targets_are_marked_and_still_away() {
        // 2 came back by itself (manual /back or another client), 9 was never ours.
        assertEquals(setOf(1L), autoBackTargets(markedNetworkIds = setOf(1L, 2L), awayNetworkIds = setOf(1L, 9L)))
    }

    @Test
    fun back_targets_are_empty_without_markers() {
        assertEquals(emptySet<Long>(), autoBackTargets(markedNetworkIds = emptySet(), awayNetworkIds = setOf(1L, 2L)))
    }

    @Test
    fun pending_requests_are_dropped_when_their_network_leaves_ready() {
        assertEquals(setOf(1L), retainedAwayRequests(requestedNetworkIds = setOf(1L, 2L), readyNetworkIds = setOf(1L, 3L)))
    }

    @Test
    fun blank_message_falls_back_to_the_localized_default() {
        assertEquals("Away (auto)", autoAwayText("", "Away (auto)"))
        assertEquals("Away (auto)", autoAwayText("   ", "Away (auto)"))
        assertEquals("brb", autoAwayText("  brb  ", "Away (auto)"))
    }

    @Test
    fun delay_is_minutes_and_never_zero() {
        assertEquals(60_000L, autoAwayDelayMillis(1))
        assertEquals(10 * 60_000L, autoAwayDelayMillis(10))
        assertEquals(60_000L, autoAwayDelayMillis(0))
        assertEquals(60_000L, autoAwayDelayMillis(-5))
    }

    @Test
    fun offered_delays_are_the_documented_choices() {
        assertEquals(listOf(1, 5, 10, 15, 30, 60), AUTO_AWAY_MINUTE_CHOICES)
    }
}
