package io.github.trevarj.motd.ui.chatlist

import androidx.compose.animation.core.TweenSpec
import io.github.trevarj.motd.ui.theme.MotdMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync header's glyph: which beat it shows, and how much room the resolve beat actually has.
 *
 * The header's show/hide logic is [SyncChromePresenter]'s and is not touched here. What matters for
 * the glyph is that the only window the resolve can count on is the header's exit fade.
 */
class ChatListSyncGlyphTest {
    @Test fun a_running_pass_waves_the_dots() {
        assertEquals(
            SyncBeat.SYNCING,
            syncGlyphBeat(SyncHeaderKind.SYNCING, lastVisible = SyncHeaderKind.SYNCING),
        )
    }

    @Test fun a_settled_pass_resolves_into_the_check() {
        // Chrome has collapsed, but AnimatedContent is still composing the syncing content through
        // its exit -- which is the window the resolve beat lands in.
        assertEquals(
            SyncBeat.RESOLVE,
            syncGlyphBeat(SyncHeaderKind.HIDDEN, lastVisible = SyncHeaderKind.SYNCING),
        )
    }

    @Test fun a_waiting_episode_that_collapses_never_claims_success() {
        // Nothing was ever connected, so nothing synced: drawing a check would be the connection
        // banner's aborted-connect mistake in a second place.
        assertNull(syncGlyphBeat(SyncHeaderKind.HIDDEN, lastVisible = SyncHeaderKind.WAITING))
    }

    @Test fun a_header_that_never_appeared_shows_no_glyph() {
        assertNull(syncGlyphBeat(SyncHeaderKind.HIDDEN, lastVisible = null))
    }

    @Test fun the_waiting_line_carries_itself_without_a_glyph() {
        assertNull(syncGlyphBeat(SyncHeaderKind.WAITING, lastVisible = SyncHeaderKind.WAITING))
        assertNull(syncGlyphBeat(SyncHeaderKind.WAITING, lastVisible = SyncHeaderKind.SYNCING))
    }

    @Test fun beatFrameRangesHandOffWithoutGapOrOverlap() {
        with(SyncStateFrames) {
            assertEquals(0, SyncingFirst)
            // The loop's exclusive end is the resolve's first frame: one asset, two named beats.
            assertEquals(ResolveFirst, SyncingLast)
            assertEquals(Total, ResolveLast)
            assertTrue(SyncingLast > SyncingFirst)
            assertTrue(ResolveLast > ResolveFirst)
            // Animator-scale-off snaps: the dots at rest, the check fully drawn.
            assertEquals(0f, syncingProgress, 0f)
            assertEquals((Total - 1f) / Total, resolvedProgress, 0f)
        }
    }

    @Test fun theWaveLoopsOnceEveryFiveHundredMilliseconds() {
        // 30 frames at the shared 60fps timebase, and frame 30 draws the dots back at their frame-0
        // rest so the loop is seamless rather than snapping on every repeat.
        assertEquals(500, (SyncStateFrames.SyncingLast - SyncStateFrames.SyncingFirst) * 1_000 / 60)
    }

    @Test fun resolveBeatFitsInsideTheHeaderExitFade() {
        // SyncChromePresenter holds visible chrome for SYNC_CHROME_MIN_VISIBLE_MS after it appears,
        // but a pass that outlives that hold collapses the instant it settles: the guaranteed
        // window is the exit fade alone. Nothing holds the header open to buy the beat more room.
        val exitFadeFrames = (MotdMotion.fadeOut as TweenSpec<*>).durationMillis * 60 / 1_000

        with(SyncStateFrames) {
            // ResolveLast is the exclusive clip end, so the last frame actually drawn is one below.
            assertTrue((ResolveLast - 1) - ResolveFirst <= exitFadeFrames)
        }
    }

    @Test fun theMinimumVisibleHoldIsNotTheBudget() {
        // Documents the asymmetry the beat is sized against: a fast pass does get the longer hold,
        // and the beat simply finishes early and parks on the check.
        assertTrue(SYNC_CHROME_MIN_VISIBLE_MS > (MotdMotion.fadeOut as TweenSpec<*>).durationMillis)
    }
}
