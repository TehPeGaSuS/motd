package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatBackNavigationTest {
    @Test
    fun `header back clears focus and hides keyboard before navigation`() {
        val calls = mutableListOf<String>()

        dismissKeyboardBeforeNavigating(
            clearFocus = { calls += "clearFocus" },
            hideKeyboard = { calls += "hideKeyboard" },
            onBack = { calls += "navigate" },
        )

        assertEquals(listOf("clearFocus", "hideKeyboard", "navigate"), calls)
    }
}
