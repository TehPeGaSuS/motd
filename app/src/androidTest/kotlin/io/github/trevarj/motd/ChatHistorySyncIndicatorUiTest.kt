package io.github.trevarj.motd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_SYNC_BAR_TAG
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_SYNC_INDICATOR_TAG
import io.github.trevarj.motd.ui.chat.CHAT_HISTORY_SYNC_RETRY_TAG
import io.github.trevarj.motd.ui.chat.HISTORY_SYNC_PILL_ESCALATION_MS
import io.github.trevarj.motd.ui.chat.TimelineHistorySyncBar
import io.github.trevarj.motd.ui.chat.TimelineHistorySyncIndicator
import io.github.trevarj.motd.ui.chat.TimelineTopOverlays
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatHistorySyncIndicatorUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun barShowsImmediatelyAndPillEscalates() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                Column {
                    TimelineHistorySyncBar(
                        status = HistorySyncStatus.Syncing,
                        timelineEmpty = false,
                    )
                    TimelineHistorySyncIndicator(
                        status = HistorySyncStatus.Syncing,
                        timelineEmpty = false,
                        retryEnabled = true,
                        onRetry = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        // Enough frames for the micro fade to settle.
        compose.mainClock.advanceTimeBy(FADE_BUDGET_MS)
        compose.onNodeWithTag(CHAT_HISTORY_SYNC_BAR_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_INDICATOR_TAG).assertCountEquals(0)

        // advanceTimeBy rounds every advance up to a whole 16ms frame, so a 1ms-short edge can
        // overshoot the threshold once advances are chained. Stop a full fade budget short instead:
        // still inside the escalation window, but immune to frame rounding.
        compose.mainClock.advanceTimeBy(HISTORY_SYNC_PILL_ESCALATION_MS - 2 * FADE_BUDGET_MS)
        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_INDICATOR_TAG).assertCountEquals(0)

        compose.mainClock.advanceTimeBy(FADE_BUDGET_MS)
        compose.mainClock.advanceTimeBy(FADE_BUDGET_MS)
        compose.onNodeWithTag(CHAT_HISTORY_SYNC_INDICATOR_TAG).assertIsDisplayed()
        compose.onNodeWithText("Finding first unread…").assertExists()
        compose.onNodeWithTag(CHAT_HISTORY_SYNC_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun emptyTimelineShowsLoadingImmediately() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                Column {
                    TimelineHistorySyncBar(
                        status = HistorySyncStatus.Checking,
                        timelineEmpty = true,
                    )
                    TimelineHistorySyncIndicator(
                        status = HistorySyncStatus.Checking,
                        timelineEmpty = true,
                        retryEnabled = true,
                        onRetry = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        // Only the fade advances: an empty timeline must not wait on the escalation delay.
        compose.mainClock.advanceTimeBy(FADE_BUDGET_MS)
        compose.onNodeWithTag(CHAT_HISTORY_SYNC_INDICATOR_TAG).assertIsDisplayed()
        compose.onNodeWithText("Loading messages…").assertExists()
        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_BAR_TAG).assertCountEquals(0)
    }

    @Test
    fun indicatorHidesImmediatelyWhenSyncEnds() {
        var status by mutableStateOf<HistorySyncStatus>(HistorySyncStatus.Syncing)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MotdTheme {
                Column {
                    TimelineHistorySyncBar(status = status, timelineEmpty = false)
                    TimelineHistorySyncIndicator(
                        status = status,
                        timelineEmpty = false,
                        retryEnabled = true,
                        onRetry = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.mainClock.advanceTimeBy(HISTORY_SYNC_PILL_ESCALATION_MS + FADE_BUDGET_MS)
        compose.onNodeWithTag(CHAT_HISTORY_SYNC_INDICATOR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CHAT_HISTORY_SYNC_BAR_TAG).assertIsDisplayed()

        compose.runOnUiThread { status = HistorySyncStatus.Idle }
        compose.mainClock.advanceTimeBy(FADE_BUDGET_MS)
        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_INDICATOR_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_BAR_TAG).assertCountEquals(0)
    }

    @Test
    fun partialStateDoesNotCoverCachedMessages() {
        compose.setContent {
            MotdTheme {
                Column {
                    TimelineHistorySyncBar(
                        status = HistorySyncStatus.Partial("fixture"),
                        timelineEmpty = false,
                    )
                    TimelineHistorySyncIndicator(
                        status = HistorySyncStatus.Partial("fixture"),
                        timelineEmpty = false,
                        retryEnabled = true,
                        onRetry = {},
                    )
                }
            }
        }

        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_INDICATOR_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_BAR_TAG).assertCountEquals(0)
    }

    @Test
    fun failedStateKeepsAccessibleManualRetry() {
        var retries = 0
        compose.setContent {
            MotdTheme {
                Column {
                    TimelineHistorySyncBar(
                        status = HistorySyncStatus.Failed("fixture"),
                        timelineEmpty = false,
                    )
                    TimelineHistorySyncIndicator(
                        status = HistorySyncStatus.Failed("fixture"),
                        timelineEmpty = false,
                        retryEnabled = true,
                        onRetry = { retries++ },
                    )
                }
            }
        }

        compose.onNodeWithText("Couldn't sync messages").assertIsDisplayed()
        compose.onAllNodesWithTag(CHAT_HISTORY_SYNC_BAR_TAG).assertCountEquals(0)
        compose.onNodeWithTag(CHAT_HISTORY_SYNC_RETRY_TAG)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, retries)
    }

    @Test
    fun topOverlaysKeepSyncBelowAudioWithoutOverlap() {
        compose.setContent {
            Box(Modifier.fillMaxWidth().height(160.dp)) {
                TimelineTopOverlays(
                    audioPlayer = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                                .testTag("fixture_audio_player"),
                        )
                    },
                    historyIndicator = {
                        Box(Modifier.size(24.dp).testTag("fixture_history_sync"))
                    },
                    syncBar = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .testTag("fixture_sync_bar"),
                        )
                    },
                )
            }
        }

        val audioBounds = compose.onNodeWithTag("fixture_audio_player").getUnclippedBoundsInRoot()
        val syncBounds = compose.onNodeWithTag("fixture_history_sync").getUnclippedBoundsInRoot()
        val barBounds = compose.onNodeWithTag("fixture_sync_bar").getUnclippedBoundsInRoot()
        assertTrue("audio player was not pinned to the timeline top", audioBounds.top <= 1.dp)
        assertTrue("history sync overlapped the audio player", syncBounds.top >= audioBounds.bottom)
        assertEquals("sync bar was not pinned to the timeline top edge", 0.dp, barBounds.top)
        // The bar is a sibling of the overlay column, so it draws over the player instead of
        // pushing it down; playback controls stay exactly where they were without the bar.
        assertTrue("sync bar displaced the audio player", audioBounds.top < barBounds.bottom)
    }

    private companion object {
        /** Frame budget that lets a `MotdMotion.micro*` fade finish under the manual clock. */
        const val FADE_BUDGET_MS = 500L
    }
}
