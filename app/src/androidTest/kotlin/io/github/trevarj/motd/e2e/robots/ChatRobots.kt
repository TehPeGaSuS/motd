package io.github.trevarj.motd.e2e.robots

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

internal class ChatListRobot(compose: ComposeTestRule) : BaseRobot(compose) {
    fun open(bufferId: Long) = click("chatlist_row_$bufferId")
}

internal class ChatRobot(compose: ComposeTestRule) : BaseRobot(compose) {
    fun send(text: String) {
        replace("chat_composer_field", text)
        click("chat_composer_send")
    }
}

internal class TimelineRobot(private val rule: ComposeTestRule) : BaseRobot(rule) {
    fun assertMessage(text: String) {
        scrollContainerTo("chat_timeline", hasText(text, substring = true))
        rule.onNodeWithText(text, substring = true, useUnmergedTree = true).assertTextContains(text, substring = true)
    }

    fun assertCompactAudioPlayer(messageTag: String) {
        val playerMatcher = hasTestTag("audio_player") and hasAnyAncestor(hasTestTag(messageTag))
        val detailsMatcher = hasTestTag("audio_player_details") and hasAnyAncestor(hasTestTag(messageTag))
        scrollContainerTo("chat_timeline", hasTestTag(messageTag))
        val players = rule.onAllNodes(playerMatcher, useUnmergedTree = true).assertCountEquals(1)
        val player = players[0].assertIsDisplayed()
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val heightDp = player.fetchSemanticsNode().boundsInRoot.height / density
        assertTrue("audio player height was ${heightDp}dp", heightDp <= 84f)
        rule.onAllNodes(detailsMatcher, useUnmergedTree = true).assertCountEquals(1)[0].performClick()
        rule.onNodeWithText("Link", useUnmergedTree = true).assertIsDisplayed()
    }

    fun assertUnreadEntry(firstTag: String, secondTag: String) {
        rule.waitForIdle()
        awaitTag("chat_read_marker_divider")
        awaitTag(firstTag)
        awaitTag(secondTag)
        val timeline = rule.onNodeWithTag("chat_timeline", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val first = rule.onNodeWithTag(firstTag, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag(secondTag, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("first unread was above the timeline viewport", first.top >= timeline.top - density)
        assertTrue(
            "first unread was not anchored near the viewport top: timeline=${timeline.top}, row=${first.top}",
            first.top <= timeline.top + 96f * density,
        )
        assertTrue("second unread did not follow the first", second.top > first.top)
        rule.onAllNodesWithTag(firstTag, useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag(secondTag, useUnmergedTree = true).assertCountEquals(1)
    }

    fun scrollToBottom() {
        awaitTag("chat_scroll_to_bottom_fab")
        rule.onNodeWithTag("chat_scroll_to_bottom_fab", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("chat_scroll_to_bottom_fab", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        rule.waitForIdle()
    }

    fun assertNoUnreadDivider() {
        rule.onAllNodesWithTag("chat_read_marker_divider", useUnmergedTree = true).assertCountEquals(0)
    }

    fun assertMessageVisible(tag: String) {
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed() }.isSuccess
        }
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).assertCountEquals(1)
    }
}
