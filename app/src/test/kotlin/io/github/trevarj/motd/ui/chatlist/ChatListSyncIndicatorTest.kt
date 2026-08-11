package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.service.HistorySyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListSyncIndicatorTest {
    @Test
    fun syncing_maps_to_spinner_and_queued_stays_invisible() {
        val statuses = mapOf(
            1L to HistorySyncStatus.Queued,
            2L to HistorySyncStatus.Syncing,
        )

        // Queued is scheduler internals; painting it made the whole list churn on reconnect.
        assertEquals(
            mapOf(2L to ChatListSyncIndicator.SYNCING),
            chatListSyncIndicators(statuses),
        )
    }

    @Test
    fun partial_and_failed_both_map_to_error() {
        val statuses = mapOf(
            1L to HistorySyncStatus.Partial("boundary saturated"),
            2L to HistorySyncStatus.Failed("timed out"),
        )

        assertEquals(
            mapOf(1L to ChatListSyncIndicator.ERROR, 2L to ChatListSyncIndicator.ERROR),
            chatListSyncIndicators(statuses),
        )
    }

    @Test
    fun idle_and_unavailable_are_dropped_from_the_result_map() {
        val statuses = mapOf(
            1L to HistorySyncStatus.Idle,
            2L to HistorySyncStatus.Unavailable,
        )

        assertEquals(emptyMap<Long, ChatListSyncIndicator>(), chatListSyncIndicators(statuses))
    }

    @Test
    fun a_settled_buffer_absent_from_the_input_stays_absent_from_the_result() {
        assertEquals(emptyMap<Long, ChatListSyncIndicator>(), chatListSyncIndicators(emptyMap()))
    }
}
