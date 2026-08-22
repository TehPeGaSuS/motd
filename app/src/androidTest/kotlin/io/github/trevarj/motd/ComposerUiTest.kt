package io.github.trevarj.motd

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.components.ComposerReply
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposerUiTest {
    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    @Test
    fun emojiPicker_opensAlongsideTheComposerInput() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
    }

    @Test
    fun emojiPicker_toggle_keepsTheComposerInputAvailable() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_input_row").assertIsDisplayed()
        // The picker stays inflated but reports no height, so closing it frees the space without
        // throwing away the populated emoji grid.
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsNotDisplayed()
        assertEquals(
            0,
            compose.onNodeWithTag("chat_composer_emoji_panel").fetchSemanticsNode().size.height,
        )
    }

    @Test
    fun emojiPicker_reopensWithTheRetainedPicker() {
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_grid").assertExists()

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()
        // Retained across the close: reopening reveals the same view instead of re-inflating it and
        // re-running the async category load that made the picker flash blank.
        compose.onNodeWithTag("chat_composer_emoji_grid").assertExists()

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.onNodeWithTag("chat_composer_emoji_grid").assertExists()
    }

    @Test
    fun emojiPicker_panelHeightComplementsTheImeThroughoutTheAnimation() {
        val imeHeightPx = mutableStateOf(200)
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("draft"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    imeHeightPx = imeHeightPx.value,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.waitForIdle()

        // Simulated keyboard fall and rise. The panel is measured from the same inset value the
        // ancestor imePadding() consumes, so their sum — the space below the input row — never moves.
        var expectedSumPx = -1
        listOf(200, 150, 100, 40, 0, 80, 160, 200).forEach { currentImeHeightPx ->
            compose.runOnIdle { imeHeightPx.value = currentImeHeightPx }
            compose.waitForIdle()
            val panelHeightPx = compose.onNodeWithTag("chat_composer_emoji_panel")
                .fetchSemanticsNode()
                .size
                .height
            val sumPx = panelHeightPx + currentImeHeightPx
            if (expectedSumPx < 0) expectedSumPx = sumPx else assertEquals(expectedSumPx, sumPx)
        }
    }

    @Test
    fun autocompletePopup_rowIsClickableOutsideComposerBounds() {
        var picked: String? = null
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("ali"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    autocomplete = {
                        AutocompletePanel(
                            candidates = listOf("alice"),
                            onPick = { picked = it },
                        )
                    },
                )
            }
        }

        compose.onNodeWithText("alice").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("alice", picked)
        }
    }

    @Test
    fun replyBanner_keepsItsContentWhileSendExitRuns() {
        val replyVisible = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue("reply"),
                    onValueChange = {},
                    onSend = {},
                    enabled = true,
                    reply = ComposerReply("alice", "original"),
                    replyVisible = replyVisible.value,
                )
            }
        }
        compose.onNodeWithText("original").assertIsDisplayed()

        compose.runOnUiThread { replyVisible.value = false }
        compose.mainClock.advanceTimeByFrame()

        // Send starts the exit immediately, while the old quote remains mounted for the fade.
        compose.onNodeWithText("original").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("original").assertDoesNotExist()
    }

    @Test
    fun semanticVoiceActivation_startsOneLockedRecordingAndStopsWhenActive() {
        var starts = 0
        var stops = 0
        val recording = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val voiceEnabled = mutableStateOf(true)
        compose.setContent {
            MotdTheme {
                Composer(
                    value = TextFieldValue(),
                    onValueChange = {},
                    onSend = {},
                    enabled = enabled.value,
                    voiceEnabled = voiceEnabled.value,
                    voiceRecording = recording.value,
                    onVoiceAccessibilityStart = {
                        starts++
                        recording.value = true
                    },
                    onVoiceHoldStop = {
                        stops++
                        recording.value = false
                    },
                )
            }
        }
        compose.onNodeWithTag("chat_composer_voice").performTouchInput { click() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, starts) }

        compose.onNodeWithTag("chat_composer_voice")
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, starts) }

        compose.onNodeWithTag("chat_composer_voice").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, stops)
        }

        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("chat_composer_voice")
            .assertIsNotEnabled()

        compose.runOnIdle {
            enabled.value = true
            voiceEnabled.value = false
        }
        compose.onAllNodesWithTag("chat_composer_voice").assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, starts) }
    }
}
