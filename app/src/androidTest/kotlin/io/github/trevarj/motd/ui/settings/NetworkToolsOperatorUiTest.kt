package io.github.trevarj.motd.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NetworkToolsOperatorUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun ircopGroupStartsCollapsedAndKillConfirmPreviewsTheExactCommand() {
        var sent: IrcMessage? = null
        compose.setContent {
            MotdTheme {
                NetworkToolsContent(
                    state = NetworkToolsUiState(networkId = 1, connected = true, selfNick = "me"),
                    onBack = {},
                    onSendCommand = { sent = it },
                )
            }
        }

        // Collapsed by default: no operator field is reachable until the group is opened.
        compose.onAllNodesWithTag("network_tools_kill_nick").assertCountEquals(0)

        compose.onNodeWithTag("network_tools_ircop_expand").performScrollTo().performClick()

        compose.onNodeWithTag("network_tools_kill_nick").performScrollTo().performTextInput("spammer")
        compose.onNodeWithTag("network_tools_kill_chip_disruptive").performScrollTo().performClick()
        compose.onNodeWithTag("network_tools_kill_send").performScrollTo().performClick()

        // The confirmation shows the exact line the confirm button will hand to the transport.
        compose.onNodeWithTag("network_tools_confirm_preview")
            .assertTextEquals("KILL spammer :Being disruptive")
        compose.onNodeWithTag("network_tools_confirm_accept").performClick()

        compose.runOnIdle {
            assertEquals(listOf("spammer", "Being disruptive"), sent?.params)
            assertEquals("KILL", sent?.command)
        }
    }
}
