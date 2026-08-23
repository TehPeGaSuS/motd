package io.github.trevarj.motd.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trigger predicate for the reaction burst. This is the restraint the timeline is owed: the
 * sparks are chip-local, one-shot, and only ever fire for a reaction that arrives, on a message the
 * user actually sent, while the app is allowed to animate at all.
 */
class ReactionBurstTest {
    private fun plays(
        isSelf: Boolean = true,
        previousCount: Int? = 1,
        count: Int = 2,
        motionEnabled: Boolean = true,
    ) = reactionBurstPlays(isSelf, previousCount, count, motionEnabled)

    @Test fun a_new_reaction_on_an_own_message_bursts() {
        assertTrue(plays(previousCount = 1, count = 2))
    }

    @Test fun someone_elses_message_never_bursts() {
        // A busy channel would otherwise fire sparks down the whole timeline.
        assertFalse(plays(isSelf = false))
    }

    @Test fun scrollback_never_bursts() {
        // First observation of a count: the chip was scrolled to, not reacted to.
        assertFalse(plays(previousCount = null, count = 12))
    }

    @Test fun an_unreact_never_bursts() {
        assertFalse(plays(previousCount = 3, count = 2))
    }

    @Test fun an_unchanged_count_never_bursts() {
        assertFalse(plays(previousCount = 2, count = 2))
    }

    @Test fun animations_off_never_bursts() {
        assertFalse(plays(motionEnabled = false))
    }

    @Test fun a_jump_of_several_still_bursts_exactly_once() {
        // Several reactions landing in one emission is still one arrival to celebrate.
        assertTrue(plays(previousCount = 1, count = 5))
    }

    @Test fun the_burst_window_matches_the_asset() {
        // 24 frames at the shared 60fps timebase; the overlay unmounts on this deadline, so a
        // shorter constant would cut the sparks off mid-flight.
        assertTrue(REACTION_BURST_DURATION_MS == 400L)
    }
}
