package io.github.trevarj.motd

import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import io.github.trevarj.motd.ui.chat.ScrollToBottomFab
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScrollToBottomFabUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun physicalTapDispatchesTheFabClick() {
        var taps = 0
        var longPresses = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ScrollToBottomFab(
                    visible = true,
                    unread = 0,
                    mentionPending = false,
                    onClick = { taps++ },
                    onLongClick = { longPresses++ },
                )
            }
        }

        compose.onNodeWithTag("chat_scroll_to_bottom_fab")
            .performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(1, taps)
            assertEquals(0, longPresses)
        }
    }
}
