package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatListDefaultSelectionTest {
    @Test
    fun `newest visible conversation is selected deterministically`() {
        val rows = listOf(row(1, 100), row(2, 300), row(3, 300))

        assertEquals(3L, defaultChatBufferId(rows))
    }

    @Test
    fun `empty list has no default conversation`() {
        assertNull(defaultChatBufferId(emptyList()))
    }

    private fun row(
        id: Long,
        time: Long?,
    ) = ChatListRow(
        bufferId = id,
        networkId = 1,
        networkName = "network",
        displayName = "chat-$id",
        type = BufferType.CHANNEL,
        pinned = false,
        muted = false,
        lastMessageText = null,
        lastMessageSender = null,
        lastMessageTime = time,
        unreadCount = 0,
        mentionCount = 0,
    )
}
