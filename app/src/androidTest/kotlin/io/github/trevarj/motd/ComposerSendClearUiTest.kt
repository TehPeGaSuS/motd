package io.github.trevarj.motd

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.chat.ChatContent
import io.github.trevarj.motd.ui.chat.ChatState
import io.github.trevarj.motd.ui.chat.ComposerDraftState
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The composer must empty on the frame the send is tapped, and must get its text back when the
 * ViewModel republishes a draft the send never consumed.
 *
 * Both halves were unasserted while the field's only path to empty was an accepted send winning a
 * Room write and a wire round-trip, which is how a send that silently failed could leave the text
 * sitting in the box with nothing reported.
 */
class ComposerSendClearUiTest {
    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    private val buffer = BufferEntity(
        id = 1,
        networkId = 1,
        name = "#kotlin",
        displayName = "#kotlin",
        type = BufferType.CHANNEL,
    )

    /** Renders the real chat surface over an empty timeline, with the draft under test control. */
    private fun setContent(
        draft: () -> ComposerDraftState,
        onSubmit: (String) -> Unit,
    ) {
        compose.setContent {
            val items = flowOf(PagingData.from(emptyList<MessageEntity>())).collectAsLazyPagingItems()
            MotdTheme {
                ChatContent(
                    state = ChatState(
                        buffer = buffer,
                        connState = IrcClientState.Ready("me", emptySet(), emptyMap()),
                    ),
                    items = items,
                    composerEnabled = true,
                    onBack = {},
                    onOpenChannelInfo = {},
                    onOpenSearch = {},
                    onOpenImage = {},
                    nickNormalizer = { it.lowercase() },
                    onSubmit = onSubmit,
                    onTyping = {},
                    onSetReply = {},
                    onReact = { _, _ -> },
                    onRetry = {},
                    loadPreview = { _, _ -> null },
                    composerDraft = draft(),
                )
            }
        }
    }

    @Test
    fun send_emptiesTheFieldWithoutWaitingForTheSendToLand() {
        val submitted = mutableListOf<String>()
        // Nothing acknowledges the send: no accepted result, no cleared draft comes back.
        setContent(draft = { ComposerDraftState("hello", hydrated = true, revision = 1) }) {
            submitted += it
        }

        compose.onNodeWithText("hello").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.waitForIdle()

        assertEquals(listOf("hello"), submitted)
        compose.onNodeWithText("hello").assertDoesNotExist()
    }

    @Test
    fun republishedDraft_returnsTextTheSendNeverConsumed() {
        var draft by mutableStateOf(ComposerDraftState("hello", hydrated = true, revision = 1))
        setContent(draft = { draft }) {}

        compose.onNodeWithTag("chat_composer_send").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("hello").assertDoesNotExist()

        // What the ViewModel does when a send is rejected or its draft went stale: the same text
        // under a fresh revision, which is the screen's only signal to restore the field.
        draft = draft.copy(revision = draft.revision + 1)
        compose.waitForIdle()

        compose.onNodeWithText("hello").assertIsDisplayed()
    }
}
