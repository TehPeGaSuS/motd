package io.github.trevarj.motd

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import io.github.trevarj.motd.ui.chatlist.CHAT_LIST_TITLE_CONNECTING_TAG
import io.github.trevarj.motd.ui.chatlist.ChatListContent
import io.github.trevarj.motd.ui.chatlist.ChatListState
import io.github.trevarj.motd.ui.chatlist.ChatListTitleConnectingSpinner
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The title-bar connectivity cue: a trailing spinner beside the chat-list title while sockets are
 * being (re-)established. It renders only for a presenter-resolved true, carries its content
 * description for TalkBack, and leaves the title alone when everything is settled.
 */
class ChatListTitleConnectivityUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connecting_rendersSpinnerWithContentDescription() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListTitleConnectingSpinner(visible = true)
            }
        }

        compose.onNode(hasTestTag(CHAT_LIST_TITLE_CONNECTING_TAG), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Connecting")
    }

    @Test
    fun settled_rendersNothing() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListTitleConnectingSpinner(visible = false)
            }
        }

        assertEquals(
            0,
            compose.onAllNodesWithTag(CHAT_LIST_TITLE_CONNECTING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun defaultTitle_showsSpinnerBesideAppNameWithoutReplacingIt() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListContent(
                    state = ChatListState(loading = false),
                    titleConnecting = true,
                    onOpenBuffer = {},
                    onOpenSettings = {},
                    onOpenSearch = {},
                    onSetPinned = { _, _ -> },
                    onSetMuted = { _, _ -> },
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        // The cue is additive: the app-name title stays, the spinner trails it inside the bar.
        compose.onNode(
            hasText("motd") and hasAnyAncestor(hasTestTag("chatlist_top_app_bar")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNode(
            hasTestTag(CHAT_LIST_TITLE_CONNECTING_TAG) and hasAnyAncestor(hasTestTag("chatlist_top_app_bar")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }
}
