package io.github.trevarj.motd.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.trevarj.motd.irc.format.IRC_BOLD
import io.github.trevarj.motd.irc.format.IrcTextStyle
import io.github.trevarj.motd.irc.format.ircStateAtRawOffset
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
        val edit = toggleIrcStyle(raw, raw.length, raw.length, IrcTextStyle.BOLD)
        assertTrue(ircStateAtRawOffset(edit.text, edit.selectionStart).bold)
        assertEquals(raw, plainIrcText(edit.text))
    }

    @Test
    fun `formatting controls alone are not sendable content`() {
        assertTrue(plainIrcText("$IRC_BOLD$IRC_BOLD").isBlank())
        assertFalse(plainIrcText("${IRC_BOLD}x$IRC_BOLD").isBlank())
    }
}
