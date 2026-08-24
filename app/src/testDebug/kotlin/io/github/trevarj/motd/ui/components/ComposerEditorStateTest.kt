package io.github.trevarj.motd.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IRC_COLOR
import io.github.trevarj.motd.irc.format.IRC_RESET
import io.github.trevarj.motd.irc.format.IrcColor
import io.github.trevarj.motd.irc.format.ircStateAtRawOffset
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerEditorStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun applyingColorKeepsVisibleCursorInPlace() {
        val draft = mutableStateOf(TextFieldValue("first\n", TextRange(6)))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_color").performClick()
        compose.onNodeWithTag("chat_composer_color_sheet").assertIsDisplayed()
        compose.onNodeWithTag("chat_color_4").performClick()
        compose.onNodeWithTag("chat_composer_color_apply").performClick()

        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals(6, parsed.visibleOffset(draft.value.selection.start))
        }
        compose.onNodeWithTag("chat_composer_field").performTextInput("x")
        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("first\nx", parsed.visibleText)
            assertEquals(7, parsed.visibleOffset(draft.value.selection.start))
            assertEquals(IrcColor.Numeric(4), parsed.stateAtVisible(parsed.visibleText.lastIndex).foreground)
        }
    }

    @Test
    fun applyingColorKeepsSelectedRangeAndText() {
        val draft = mutableStateOf(TextFieldValue("abcdef\nx", TextRange(2, 5)))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_color").performClick()
        compose.onNodeWithTag("chat_color_4").performClick()
        compose.onNodeWithTag("chat_composer_color_apply").performClick()

        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("abcdef\nx", parsed.visibleText)
            assertEquals(2, parsed.visibleOffset(draft.value.selection.start))
            assertEquals(5, parsed.visibleOffset(draft.value.selection.end))
            assertTrue(
                parsed.runs
                    .filter { it.end > 2 && it.start < 5 }
                    .all { it.state.foreground == IrcColor.Numeric(4) },
            )
        }
    }

    @Test
    fun clearingFormattingNeverDeletesSelectedText() {
        val raw = "$IRC_BOLD${IRC_COLOR}04,01hello\nthere$IRC_RESET"
        val draft = mutableStateOf(TextFieldValue(raw, TextRange(0, raw.length)))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Composer(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    onSend = {},
                    enabled = true,
                    ircFormattingEnabled = true,
                )
            }
        }

        compose.onNodeWithTag("chat_composer_format_expand").performClick()
        compose.onNodeWithTag("chat_format_clear").performClick()

        compose.runOnIdle {
            val parsed = parseIrcFormatting(draft.value.text)
            assertEquals("hello\nthere", parsed.visibleText)
            assertTrue(parsed.runs.all { it.state.isDefault })
        }
    }
}
