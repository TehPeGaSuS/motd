package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.service.HistorySyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListSyncIndicatorTest {
    @Test
    fun queued_and_syncing_map_to_their_matching_indicator() {
        val statuses = mapOf(
            1L to HistorySyncStatus.Queued,
            2L to HistorySyncStatus.Syncing,
        )

        assertEquals(
            mapOf(1L to ChatListSyncIndicator.QUEUED, 2L to ChatListSyncIndicator.SYNCING),
            chatListSyncIndicators(statuses, queuedCuesVisible = true),
        )
    }

    @Test
    fun awaiting_connection_maps_to_the_waiting_ring() {
        assertEquals(
            mapOf(1L to ChatListSyncIndicator.WAITING),
            chatListSyncIndicators(mapOf(1L to HistorySyncStatus.AwaitingConnection), queuedCuesVisible = true),
        )
    }

    @Test
    fun a_closed_chrome_gate_hides_queued_and_waiting_but_not_work_in_flight() {
        // The gate is the anti-flash window: a pass that resolves inside it must never have painted
        // optimistic rings across the list, but a request genuinely on the wire still shows.
        val statuses = mapOf(
            1L to HistorySyncStatus.Queued,
            2L to HistorySyncStatus.AwaitingConnection,
            3L to HistorySyncStatus.Syncing,
            4L to HistorySyncStatus.Failed("timed out"),
        )

        assertEquals(
            mapOf(3L to ChatListSyncIndicator.SYNCING, 4L to ChatListSyncIndicator.ERROR),
            chatListSyncIndicators(statuses, queuedCuesVisible = false),
        )
    }

    @Test
    fun failed_is_an_error_dot_and_partial_is_downgraded_to_nothing() {
        val statuses = mapOf(
            1L to HistorySyncStatus.Partial("boundary saturated"),
            2L to HistorySyncStatus.Failed("timed out"),
        )

        // Partial is carried by the in-chat stale chip; a list dot identical to a hard failure's
        // overstated it.
        assertEquals(
            mapOf(2L to ChatListSyncIndicator.ERROR),
            chatListSyncIndicators(statuses, queuedCuesVisible = true),
        )
    }

    @Test
    fun unavailable_gets_its_own_terminal_cue_and_idle_is_dropped() {
        val statuses = mapOf(
            1L to HistorySyncStatus.Idle,
            2L to HistorySyncStatus.Unavailable,
        )

        assertEquals(
            mapOf(2L to ChatListSyncIndicator.UNAVAILABLE),
            chatListSyncIndicators(statuses, queuedCuesVisible = false),
        )
    }

    @Test
    fun a_settled_buffer_absent_from_the_input_stays_absent_from_the_result() {
        assertEquals(
            emptyMap<Long, ChatListSyncIndicator>(),
            chatListSyncIndicators(emptyMap(), queuedCuesVisible = true),
        )
    }
}
