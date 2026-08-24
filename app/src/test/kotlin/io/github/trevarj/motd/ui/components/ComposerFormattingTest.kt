package io.github.trevarj.motd.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IrcColor
import io.github.trevarj.motd.irc.format.IrcTextStyle
import io.github.trevarj.motd.irc.format.applyIrcColors
import io.github.trevarj.motd.irc.format.clearIrcFormatting
import io.github.trevarj.motd.irc.format.ircStateAtRawOffset
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.irc.format.plainIrcText
import io.github.trevarj.motd.irc.format.toggleIrcStyle
import io.github.trevarj.motd.ui.chat.messageFormattingRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerFormattingTest {
    @Test
    fun `emoji query maps through hidden formatting controls`() {
        val raw = "$IRC_BOLD:smile$IRC_BOLD"
        val query = activeEmojiQuery(TextFieldValue(raw, TextRange(raw.length - 1)))!!
        val replaced = replaceEmojiQuery(TextFieldValue(raw), query, "😀")

        assertEquals("😀", plainIrcText(replaced.text))
        assertTrue(replaced.text.startsWith(IRC_BOLD))
        assertTrue(replaced.text.endsWith(IRC_BOLD))
    }

    @Test
    fun `message range excludes command and target controls`() {
        val raw = "/${IRC_BOLD}msg$IRC_BOLD ${IRC_BOLD}alice$IRC_BOLD ${IRC_BOLD}hello$IRC_BOLD"
        val range = messageFormattingRange(raw)!!

        assertEquals("${IRC_BOLD}hello$IRC_BOLD", raw.substring(range.first, range.last + 1))
    }

    @Test
    fun `empty drafts and blank lines accept collapsed formatting`() {
        assertEquals(0 until 0, messageFormattingRange(""))
        assertEquals(0 until 2, messageFormattingRange(" \n"))

        val raw = "first\n"
        assertEquals(0 until raw.length, messageFormattingRange(raw))
        val edit = toggleIrcStyle(raw, raw.length, raw.length, IrcTextStyle.BOLD)
        assertTrue(ircStateAtRawOffset(edit.text, edit.selectionStart).bold)
        assertEquals(raw, plainIrcText(edit.text))
    }

    @Test
    fun `applying color preserves collapsed visible cursor`() {
        val raw = "first\n"
        val before = parseIrcFormatting(raw).visibleOffset(raw.length)
        val edit = applyIrcColors(raw, raw.length, raw.length, foreground = 4, background = null)
        val after = parseIrcFormatting(edit.text)

        assertEquals(before, after.visibleOffset(edit.selectionStart))
        assertEquals(IrcColor.Numeric(4), ircStateAtRawOffset(edit.text, edit.selectionStart).foreground)
    }

    @Test
    fun `strikethrough and clear work for collapsed and selected ranges`() {
        val collapsed = toggleIrcStyle("first\n", 6, 6, IrcTextStyle.STRIKETHROUGH)
        assertTrue(ircStateAtRawOffset(collapsed.text, collapsed.selectionStart).strikethrough)

        val raw = "hello\n"
        val selected = toggleIrcStyle(raw, 0, raw.length, IrcTextStyle.STRIKETHROUGH)
        assertTrue(parseIrcFormatting(selected.text).runs.all { it.state.strikethrough })

        val range = messageFormattingRange(selected.text)!!
        val cleared = clearIrcFormatting(selected.text, range.first, range.last + 1)
        assertEquals(raw, plainIrcText(cleared.text))
        assertTrue(parseIrcFormatting(cleared.text).runs.all { it.state.isDefault })

        val colored = applyIrcColors(raw, 0, raw.length, foreground = 4, background = 1)
        val coloredRange = messageFormattingRange(colored.text)!!
        val colorCleared = clearIrcFormatting(colored.text, coloredRange.first, coloredRange.last + 1)
        assertEquals(raw, plainIrcText(colorCleared.text))
        assertTrue(parseIrcFormatting(colorCleared.text).runs.all { it.state.isDefault })
    }

    @Test
    fun `formatting controls alone are not sendable content`() {
        assertTrue(plainIrcText("$IRC_BOLD$IRC_BOLD").isBlank())
        assertFalse(plainIrcText("${IRC_BOLD}x$IRC_BOLD").isBlank())
    }
}
