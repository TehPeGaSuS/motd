package io.github.trevarj.motd.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions every Lottie call site delegates to [MotdLottieMotion]: whether the platform
 * wants motion at all, and whether a one-shot beat is owed a play.
 */
class LottieMotionTest {
    @Test fun `animator scale zero means animations off`() {
        // Compose specs get this from MotionDurationScale for free; Lottie has to be told.
        assertFalse(MotdLottieMotion.motionEnabled(0f))
    }

    @Test fun `any positive animator scale plays`() {
        assertTrue(MotdLottieMotion.motionEnabled(0.5f))
        assertTrue(MotdLottieMotion.motionEnabled(1f))
        assertTrue(MotdLottieMotion.motionEnabled(10f))
    }

    @Test fun `a nonsense negative scale is treated as animations off`() {
        assertFalse(MotdLottieMotion.motionEnabled(-1f))
    }

    @Test fun `a beat plays on the transition into its target`() {
        assertTrue(MotdLottieMotion.playOnceOnTransition(previous = false, current = true, target = true))
    }

    @Test fun `a value first seen at the target never plays`() {
        // Scrollback: a message composed fresh and already delivered shows the settled frame.
        assertFalse(MotdLottieMotion.playOnceOnTransition(previous = null, current = true, target = true))
    }

    @Test fun `staying at the target does not replay`() {
        assertFalse(MotdLottieMotion.playOnceOnTransition(previous = true, current = true, target = true))
    }

    @Test fun `leaving or missing the target does not play`() {
        assertFalse(MotdLottieMotion.playOnceOnTransition(previous = true, current = false, target = true))
        assertFalse(MotdLottieMotion.playOnceOnTransition(previous = false, current = false, target = true))
    }

    @Test fun `the target is not hardcoded to a boolean`() {
        assertTrue(MotdLottieMotion.playOnceOnTransition(previous = "a", current = "b", target = "b"))
        assertFalse(MotdLottieMotion.playOnceOnTransition(previous = "b", current = "c", target = "b"))
    }
}
