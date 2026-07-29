package io.github.trevarj.motd.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerPanelTest {
    @Test
    fun `voice gesture locks on a dominant upward swipe`() {
        assertEquals(
            VoiceGestureTarget.LOCK,
            voiceGestureTarget(
                holdActivated = true,
                pointerPressed = true,
                deltaX = -30f,
                deltaY = -90f,
                cancelThreshold = 72f,
                lockThreshold = 72f,
            ),
        )
    }

    @Test
    fun `voice gesture cannot lock before hold activates`() {
        assertEquals(
            VoiceGestureTarget.NONE,
            voiceGestureTarget(
                holdActivated = false,
                pointerPressed = true,
                deltaX = -30f,
                deltaY = -90f,
                cancelThreshold = 72f,
                lockThreshold = 72f,
            ),
        )
    }

    @Test
    fun `voice gesture cannot lock after the pointer is released`() {
        assertEquals(
            VoiceGestureTarget.NONE,
            voiceGestureTarget(
                holdActivated = true,
                pointerPressed = false,
                deltaX = -30f,
                deltaY = -90f,
                cancelThreshold = 72f,
                lockThreshold = 72f,
            ),
        )
    }

    @Test
    fun `voice recording requires at least half a second hold`() {
        assertEquals(500L, voiceRecordHoldDelay(longPressTimeoutMillis = 300L))
        assertEquals(700L, voiceRecordHoldDelay(longPressTimeoutMillis = 700L))
    }

    @Test
    fun `voice gesture cancels on a left swipe`() {
        assertEquals(
            VoiceGestureTarget.CANCEL,
            voiceGestureTarget(
                holdActivated = true,
                pointerPressed = true,
                deltaX = -90f,
                deltaY = -20f,
                cancelThreshold = 72f,
                lockThreshold = 72f,
            ),
        )
    }

    @Test
    fun `voice gesture ignores movement below both thresholds`() {
        assertEquals(
            VoiceGestureTarget.NONE,
            voiceGestureTarget(
                holdActivated = true,
                pointerPressed = true,
                deltaX = -40f,
                deltaY = -40f,
                cancelThreshold = 72f,
                lockThreshold = 72f,
            ),
        )
    }

    @Test
    fun autocomplete_popup_is_placed_above_the_composer_without_layout_height() {
        assertEquals(
            IntOffset(12, 456),
            autocompletePopupPosition(
                anchorBounds = IntRect(left = 12, top = 600, right = 412, bottom = 668),
                popupContentSize = IntSize(width = 400, height = 144),
                layoutDirection = LayoutDirection.Ltr,
            ),
        )
    }

    @Test fun emojiTakesPriorityOverAutocomplete() {
        assertEquals(ComposerPanel.EMOJI, composerPanel(showEmoji = true, hasAutocomplete = true))
    }

    @Test fun autocompleteShowsWhenEmojiIsClosed() {
        assertEquals(ComposerPanel.AUTOCOMPLETE, composerPanel(showEmoji = false, hasAutocomplete = true))
    }

    @Test fun noPanelWhenBothAreClosed() {
        assertEquals(ComposerPanel.NONE, composerPanel(showEmoji = false, hasAutocomplete = false))
    }

    @Test
    fun `emoji query follows the token at the cursor`() {
        assertEquals(
            EmojiQuery(6, 12, "smile"),
            activeEmojiQuery(TextFieldValue("hello :smile", selection = TextRange(12))),
        )
        assertEquals(null, activeEmojiQuery(TextFieldValue("hello:smile", selection = TextRange(11))))
    }

    @Test
    fun `emoji selection replaces only the active query`() {
        val value = TextFieldValue("hello :smile world", selection = TextRange(12))

        assertEquals("hello 😄 world", replaceEmojiQuery(value, EmojiQuery(6, 12, "smile"), "😄").text)
    }

    @Test
    fun `emoji picker captures a visible ime and restores it when dismissed`() {
        val session = openEmojiPickerSession(
            imeHeightPx = 320,
            lastVisibleImeHeightPx = 320,
            inputFocused = true,
            compactPickerHeightPx = 250,
        )

        assertEquals(
            EmojiPickerSession(
                capturedImeHeightPx = 320,
                restoresKeyboard = true,
                phase = EmojiPickerPhase.OPEN,
            ),
            session,
        )
        assertEquals(
            session.copy(phase = EmojiPickerPhase.RESTORING_IME),
            closeEmojiPickerSession(session),
        )
    }

    @Test
    fun `reopening during ime restoration preserves the captured keyboard height`() {
        val session = openEmojiPickerSession(
            imeHeightPx = 320,
            lastVisibleImeHeightPx = 320,
            inputFocused = true,
            compactPickerHeightPx = 250,
        )
        val restoringSession = closeEmojiPickerSession(session)!!

        assertEquals(session, reopenEmojiPickerSession(restoringSession))
    }

    @Test
    fun `emoji picker opened without an ime uses compact panel and does not restore keyboard`() {
        val session = openEmojiPickerSession(
            imeHeightPx = 0,
            lastVisibleImeHeightPx = 320,
            inputFocused = false,
            compactPickerHeightPx = 250,
        )

        assertEquals(
            EmojiPickerSession(capturedImeHeightPx = 250, restoresKeyboard = false),
            session,
        )
        assertEquals(null, closeEmojiPickerSession(session))
    }

    @Test
    fun `tap-time inset race still restores the last visible keyboard`() {
        assertEquals(
            EmojiPickerSession(capturedImeHeightPx = 320, restoresKeyboard = true),
            openEmojiPickerSession(
                imeHeightPx = 0,
                lastVisibleImeHeightPx = 320,
                inputFocused = true,
                compactPickerHeightPx = 250,
            ),
        )
    }

    @Test
    fun `ime replacement height keeps the composer row at the captured keyboard position`() {
        val capturedImeHeightPx = 320

        listOf(320, 240, 160, 80, 0).forEach { currentImeHeightPx ->
            val replacementHeightPx = emojiPickerReplacementHeight(capturedImeHeightPx, currentImeHeightPx)

            assertEquals(capturedImeHeightPx, currentImeHeightPx + replacementHeightPx)
        }
    }

    @Test
    fun `ime replacement height never becomes negative`() {
        assertEquals(0, emojiPickerReplacementHeight(capturedImeHeightPx = 320, currentImeHeightPx = 400))
        assertEquals(0, emojiPickerReplacementHeight(capturedImeHeightPx = -1, currentImeHeightPx = 0))
    }

    @Test
    fun `ime inset tracker follows explicit insets and remembers the largest visible height`() {
        val tracker = ImeInsetTracker()

        assertEquals(0, tracker.update(0))
        assertEquals(400, tracker.update(400))
        assertEquals(885, tracker.update(885))
        assertEquals(885, tracker.lastVisibleImeHeightPx)
        assertEquals(400, tracker.update(400))
        assertEquals(0, tracker.update(0))
        assertEquals(885, tracker.lastVisibleImeHeightPx)
    }
}
