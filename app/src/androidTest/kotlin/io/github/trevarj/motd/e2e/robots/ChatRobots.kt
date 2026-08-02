package io.github.trevarj.motd.e2e.robots

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.platform.app.InstrumentationRegistry
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_LOADING_TAG
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

    fun assertUnreadEntry(
        firstTag: String,
        secondTag: String,
        expectedLabel: String? = null,
        timeoutMs: Long = 30_000,
    ) {
        rule.waitForIdle()
        // Atomic history publication can leave Paging materializing the bounded entry window for
        // longer than the generic component timeout on a cold hosted emulator.
        awaitTag("chat_read_marker_divider", timeoutMs)
        awaitTag(firstTag, timeoutMs)
        awaitTag(secondTag, timeoutMs)
        val timeline = rule.onNodeWithTag("chat_timeline", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        expectedLabel?.let {
            rule.onNode(
                hasText(it) and hasAnyAncestor(hasTestTag("chat_read_marker_divider")),
                useUnmergedTree = true,
            ).assertIsDisplayed()
        }
        val divider = rule.onNodeWithTag("chat_read_marker_divider", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
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
        assertTrue("unread divider was not attached to the first unread row", divider.bottom <= first.top)
        assertTrue(
            "unread divider was attached to a different row: divider=${divider.bottom}, row=${first.top}",
            first.top - divider.bottom <= 24f * density,
        )
        rule.onAllNodesWithTag(firstTag, useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag(secondTag, useUnmergedTree = true).assertCountEquals(1)
    }

    /**
     * One deliberate older-paging step. The timeline is `reverseLayout = true`, so the newest row
     * sits at index 0 and the oldest loaded row at the highest index; scrolling toward the last
     * index moves the older (APPEND) boundary into the prefetch window and drives exactly one
     * Paging APPEND.
     *
     * Determinism: `MESSAGE_PAGING_CONFIG` uses pageSize 50 > prefetchDistance 25. A scroll that
     * stops at the boundary triggers exactly one APPEND; after the 50-row insert the retained
     * viewport anchor sits 50 rows (> 25) above the new boundary, outside the prefetch range, so
     * no second page fires until the next deliberate `scrollToOlderBoundary()` step. (Opening an
     * unread gap is equally deterministic: the entry lands on the island's oldest unread row,
     * whose load hint sits 0 rows from the APPEND boundary when the island fits under
     * `initialLoadSize` = pageSize * 3 = 150, so every open fetches exactly ONE automatic older
     * page — and never a second in the same open, because the deepest hinted index then sits 50
     * rows above the new boundary. Reopens therefore compound one page per open; the required
     * journey pins the 49 -> 99 -> 149 growth and its row212 -> row162 -> row112 entry anchors.)
     */
    fun scrollToOlderBoundary() {
        awaitTag("chat_timeline")
        val before = timelineItemCount()
        val lastIndex = before - 1
        if (lastIndex > 0) {
            runCatching {
                rule.onNodeWithTag("chat_timeline", useUnmergedTree = true).performScrollToIndex(lastIndex)
            }.onFailure {
                rule.onNodeWithTag("chat_timeline", useUnmergedTree = true)
                    .performTouchInput { swipeDown(durationMillis = 300) }
            }
        } else {
            rule.onNodeWithTag("chat_timeline", useUnmergedTree = true)
                .performTouchInput { swipeDown(durationMillis = 300) }
        }
        // The boundary hit paints the shimmer footer while the APPEND is in flight, or the row set
        // grows if the fixture page lands before the tag is first observed. If neither happens the
        // step reached the confirmed start of history or loaded instantly; either way settle. The
        // swallowed timeout costs at most 10s per fully-settled step (e.g. paging past the true
        // start of history) and stays bounded: scrollOlderUntil's maximumSwipes cap still throws
        // loudly if the requested row never becomes addressable.
        runCatching {
            rule.waitUntil(10_000) { isPresent(CHAT_HISTORY_LOADING_TAG) || timelineItemCount() > before }
        }
        if (isPresent(CHAT_HISTORY_LOADING_TAG)) {
            rule.waitUntil(45_000) { !isPresent(CHAT_HISTORY_LOADING_TAG) }
        }
        rule.waitForIdle()
    }

    /** Repeat [scrollToOlderBoundary] until a row containing [text] becomes addressable. */
    fun scrollOlderUntil(text: String, maximumSwipes: Int = 48) {
        repeat(maximumSwipes) {
            val reached = runCatching {
                rule.onNodeWithTag("chat_timeline", useUnmergedTree = true)
                    .performScrollToNode(hasText(text, substring = true))
            }.isSuccess
            if (reached) {
                rule.onNodeWithText(text, substring = true, useUnmergedTree = true)
                    .assertTextContains(text, substring = true)
                return
            }
            scrollToOlderBoundary()
        }
        throw AssertionError(
            "older history row \"$text\" did not become addressable after $maximumSwipes deliberate scroll steps",
        )
    }

    private fun timelineItemCount(): Int =
        rule.onAllNodesWithTag("chat_timeline", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .singleOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.CollectionInfo)
            ?.rowCount
            ?: 0

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
