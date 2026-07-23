package io.github.trevarj.motd

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.chatlist.ChatListContent
import io.github.trevarj.motd.ui.chatlist.ChatListState
import io.github.trevarj.motd.ui.chatlist.ChatListRowItem
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListSelectionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selected_row_exposes_selected_semantics_on_the_full_row() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListRowItem(row(), false, {}, {}, selected = true)
            }
        }

        compose.onNodeWithTag("chatlist_row_1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test fun collapsing_fools_clears_their_selection_and_contextual_actions() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(rows = listOf(row().copy(displayName = "fool")), fools = setOf("fool"), loading = false),
                    onOpenBuffer = {}, onOpenSettings = {}, onOpenSearch = {},
                    onSetPinned = { _, _ -> }, onSetMuted = { _, _ -> },
                    onJoinChannel = { _, _ -> }, onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("FOOLS (1)").performClick()
        assertEquals(1, compose.onAllNodesWithTag("chatlist_row_surface_1").fetchSemanticsNodes().size)
        compose.onNodeWithTag("chatlist_row_1").performTouchInput { longClick() }
        assertEquals(1, compose.onAllNodesWithTag("chatlist_selection_top_app_bar").fetchSemanticsNodes().size)

        compose.onNodeWithText("FOOLS (1)").performClick()
        assertEquals(0, compose.onAllNodesWithTag("chatlist_row_1").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithTag("chatlist_selection_top_app_bar").fetchSemanticsNodes().size)
    }

    @Test fun unscoped_title_uses_lowercase_text() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(loading = false),
                    onOpenBuffer = {}, onOpenSettings = {}, onOpenSearch = {},
                    onSetPinned = { _, _ -> }, onSetMuted = { _, _ -> },
                    onJoinChannel = { _, _ -> }, onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("motd").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("/motd").fetchSemanticsNodes().size)
    }

    @Test fun empty_archive_uses_archive_specific_copy_without_connection_prompt() {
        val state = mutableStateOf(
            ChatListState(
                archivedRows = listOf(row().copy(archived = true)),
                networks = listOf(network()),
                loading = false,
            ),
        )
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = state.value,
                    onOpenBuffer = {}, onOpenSettings = {}, onOpenSearch = {},
                    onSetPinned = { _, _ -> }, onSetMuted = { _, _ -> },
                    onJoinChannel = { _, _ -> }, onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Archived Chats (1)").performClick()
        compose.runOnIdle { state.value = state.value.copy(archivedRows = emptyList()) }

        compose.onNodeWithText("No archived chats yet").assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText("Connect to a network to start chatting.")
                .fetchSemanticsNodes().size,
        )
    }

    private fun row() = ChatListRow(
        bufferId = 1, networkId = 1, networkName = "network", displayName = "alice",
        type = BufferType.QUERY, pinned = false, muted = false, lastMessageText = "hello",
        lastMessageSender = "alice", lastMessageTime = 1, unreadCount = 0, mentionCount = 0,
    )

    private fun network() = NetworkEntity(
        id = 1,
        name = "network",
        role = NetworkRole.DIRECT,
        host = "irc.example.test",
        port = 6697,
        nick = "me",
        username = "me",
        realname = "Me",
    )
}
