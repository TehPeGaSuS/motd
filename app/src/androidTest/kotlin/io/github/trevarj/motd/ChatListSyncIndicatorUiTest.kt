package io.github.trevarj.motd

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.ui.chatlist.ChatListRowItem
import io.github.trevarj.motd.ui.chatlist.ChatListSyncIndicator
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Per-row sync badge (plans task 2): [ChatListRowItem] overlays the avatar's top-right corner. */
class ChatListSyncIndicatorUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun syncing_rendersSpinnerBadge() {
        setRow(ChatListSyncIndicator.SYNCING)

        compose.onNodeWithTag("chatlist_row_sync_syncing", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun queued_rendersDimmedRingBadge() {
        setRow(ChatListSyncIndicator.QUEUED)

        compose.onNodeWithTag("chatlist_row_sync_queued", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun error_rendersDotBadge() {
        setRow(ChatListSyncIndicator.ERROR)

        compose.onNodeWithTag("chatlist_row_sync_error", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun none_rendersNoSyncBadge() {
        setRow(ChatListSyncIndicator.NONE)

        listOf("chatlist_row_sync_syncing", "chatlist_row_sync_queued", "chatlist_row_sync_error").forEach { tag ->
            assertEquals(0, compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    private fun setRow(indicator: ChatListSyncIndicator) {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListRowItem(
                    row = queryRow(),
                    showNetworkChip = false,
                    onClick = {},
                    onLongClick = {},
                    syncIndicator = indicator,
                )
            }
        }
    }

    private fun queryRow() = ChatListRow(
        bufferId = 1,
        networkId = 1,
        networkName = "Libera",
        displayName = "alice",
        type = BufferType.QUERY,
        pinned = false,
        muted = false,
        lastMessageText = "hello",
        lastMessageSender = "alice",
        lastMessageTime = 1L,
        unreadCount = 0,
        mentionCount = 0,
    )
}
