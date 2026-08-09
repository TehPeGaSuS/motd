package io.github.trevarj.motd.ui.chatlist

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import io.github.trevarj.motd.ui.ComposeFoundationWorkarounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Regression coverage for the chat-list glitch where rows visibly sprang into place "for no
 * reason": scroll down, ride the scroll-to-top FAB (`animateScrollToItem`), scroll down again.
 * Foundation 1.11's skip-placement-animation fix (b/493183465) freezes the lazy item animator's
 * bookkeeping during animated scrolls; a row re-sorted while off-screen then gets misclassified
 * as "moving in" on the next user scroll and animates in from outside the viewport.
 * [ComposeFoundationWorkarounds.apply] opts out of that fix; these tests pin the resulting
 * behavior for every way of returning to the top. Rows mirror the chat list's `animateItem`
 * configuration (fades plus a spring placement spec).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatListScrollPlacementTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun applyProductionWorkarounds() {
        ComposeFoundationWorkarounds.apply()
    }

    private val rowHeightPx = 100
    private val viewportPx = 500

    private lateinit var listState: LazyListState
    private lateinit var scope: CoroutineScope
    private var order by mutableStateOf((0 until 60).toList())

    private fun setContent() {
        compose.setContent {
            listState = rememberLazyListState()
            scope = rememberCoroutineScope()
            with(LocalDensity.current) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(200.toDp())
                        .height(viewportPx.toDp())
                        .testTag("list"),
                ) {
                    items(count = order.size, key = { order[it] }) { i ->
                        Box(
                            Modifier
                                .width(200.toDp())
                                .height(rowHeightPx.toDp())
                                .testTag("row-${order[i]}")
                                .animateItem(
                                    fadeInSpec = tween(90),
                                    fadeOutSpec = tween(90),
                                    placementSpec = spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                        visibilityThreshold = IntOffset.VisibilityThreshold,
                                    ),
                                ),
                        ) {}
                    }
                }
            }
        }
    }

    private fun launchOnUi(block: suspend CoroutineScope.() -> Unit): Job {
        var job: Job? = null
        compose.runOnUiThread { job = scope.launch { block() } }
        return job!!
    }

    private fun advanceUntil(job: Job, maxFrames: Int = 600) {
        var frames = 0
        while (job.isActive && frames < maxFrames) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }
        assertTrue("job did not finish within $maxFrames frames", job.isCompleted)
    }

    /**
     * Unclipped top position of every currently visible row keyed by row id, in root coordinates,
     * so a row peeking past the viewport edge still reports its true offset.
     */
    private fun visibleRowTops(): Map<Int, Float> {
        val visible = compose.runOnUiThread {
            listState.layoutInfo.visibleItemsInfo.map { it.key as Int }
        }
        return visible.associateWith { id ->
            compose.onNodeWithTag("row-$id").fetchSemanticsNode().positionInRoot.y
        }
    }

    /** Rows must tile exactly: consecutive visible rows differ by exactly one row height. */
    private fun assertRowsTile(label: String) {
        val tops = visibleRowTops()
        val visible = compose.runOnUiThread {
            listState.layoutInfo.visibleItemsInfo.sortedBy { it.offset }.map { it.key as Int }
        }
        visible.zipWithNext().forEach { (a, b) ->
            assertEquals(
                "$label: rows $a/$b not tiled (tops ${tops.getValue(a)}/${tops.getValue(b)})",
                rowHeightPx.toFloat(),
                tops.getValue(b) - tops.getValue(a),
                0.5f,
            )
        }
    }

    /** With no input and no data change, row positions must not move between frames. */
    private fun assertRowsFrozen(label: String, frames: Int = 40) {
        val before = visibleRowTops()
        repeat(frames) { compose.mainClock.advanceTimeByFrame() }
        val after = visibleRowTops()
        before.keys.intersect(after.keys).forEach { id ->
            assertEquals(
                "$label: row $id moved with no scroll input",
                before.getValue(id),
                after.getValue(id),
                0.5f,
            )
        }
    }

    private fun scrollDownInSteps(steps: Int, stepPx: Float, label: String) {
        repeat(steps) {
            val job = launchOnUi { listState.scrollBy(stepPx) }
            compose.mainClock.advanceTimeByFrame()
            assertTrue(job.isCompleted)
            assertRowsTile("$label step $it")
        }
    }

    /** Shared prologue: scroll down, then re-sort a row that is far above the viewport. */
    private fun scrollDownAndReorderOffscreen() {
        advanceUntil(launchOnUi { listState.scrollBy(2500f) })
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread {
            order = listOf(10) + order.filter { it != 10 }
        }
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun scrollDownAfterAnimatedScrollToTop_staysStatic() {
        setContent()
        compose.mainClock.autoAdvance = false

        advanceUntil(launchOnUi { listState.scrollBy(2500f) })
        compose.mainClock.advanceTimeByFrame()

        advanceUntil(launchOnUi { listState.animateScrollToItem(0) })
        repeat(60) { compose.mainClock.advanceTimeByFrame() }

        scrollDownInSteps(steps = 40, stepPx = 30f, label = "post-fab scroll")
        assertRowsFrozen("post-fab settle")
    }

    @Test
    fun offscreenReorderThenAnimatedScrollToTop_thenScrollDown_staysStatic() {
        setContent()
        compose.mainClock.autoAdvance = false
        scrollDownAndReorderOffscreen()

        advanceUntil(launchOnUi { listState.animateScrollToItem(0) })
        repeat(60) { compose.mainClock.advanceTimeByFrame() }

        scrollDownInSteps(steps = 40, stepPx = 30f, label = "post-reorder scroll")
        assertRowsFrozen("post-reorder settle")
    }

    @Test
    fun offscreenReorderThenManualScrollToTop_thenScrollDown_staysStatic() {
        setContent()
        compose.mainClock.autoAdvance = false
        scrollDownAndReorderOffscreen()

        repeat(25) {
            val job = launchOnUi { listState.scrollBy(-100f) }
            compose.mainClock.advanceTimeByFrame()
            assertTrue(job.isCompleted)
        }
        repeat(60) { compose.mainClock.advanceTimeByFrame() }

        scrollDownInSteps(steps = 40, stepPx = 30f, label = "post-manual scroll")
        assertRowsFrozen("post-manual settle")
    }

    @Test
    fun offscreenReorderThenSnapScrollToTop_thenScrollDown_staysStatic() {
        setContent()
        compose.mainClock.autoAdvance = false
        scrollDownAndReorderOffscreen()

        advanceUntil(launchOnUi { listState.scrollToItem(0) })
        repeat(60) { compose.mainClock.advanceTimeByFrame() }

        scrollDownInSteps(steps = 40, stepPx = 30f, label = "post-snap scroll")
        assertRowsFrozen("post-snap settle")
    }
}
