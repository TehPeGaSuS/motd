package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListSelectionTest {
    private fun row(
        id: Long,
        pinned: Boolean = false,
        muted: Boolean = false,
    ) = ChatListRow(
        bufferId = id,
        networkId = 1,
        networkName = "network",
        displayName = "row$id",
        type = BufferType.QUERY,
        pinned = pinned,
        muted = muted,
        lastMessageText = null,
        lastMessageSender = null,
        lastMessageTime = null,
        unreadCount = 0,
        mentionCount = 0,
    )

    @Test fun selection_prunes_and_resolves_in_visible_order() {
        val rows = listOf(row(3), row(1), row(2))
        assertEquals(listOf(3L, 2L), pruneSelectedIds(listOf(3, 9, 2, 3), rows))
        assertEquals(listOf(3L, 2L), orderedSelectedRows(rows, listOf(2, 3)).map { it.bufferId })
    }

    @Test fun aggregate_targets_pin_and_mute_unless_every_row_already_has_value() {
        assertEquals(true, aggregateToggleTarget(listOf(row(1), row(2, pinned = true)), ChatListRow::pinned))
        assertEquals(false, aggregateToggleTarget(listOf(row(1, pinned = true), row(2, pinned = true)), ChatListRow::pinned))
        assertEquals(true, aggregateToggleTarget(listOf(row(1), row(2, muted = true)), ChatListRow::muted))
        assertEquals(false, aggregateToggleTarget(listOf(row(1, muted = true), row(2, muted = true)), ChatListRow::muted))
    }

    @Test fun removal_counts_keep_all_compatibility_types() {
        val counts = removalCounts(listOf(row(1).copy(type = BufferType.CHANNEL), row(2), row(3).copy(type = BufferType.SERVER)))
        assertEquals(ChatRemovalCounts(channels = 1, queries = 1, servers = 1), counts)
    }
}
