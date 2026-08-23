package io.github.trevarj.motd.ui.theme

import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
    @Test
    fun `chat back stays calmer than drawer entry and both are bounded tweens`() {
        // Bounded tweens, never springs: a spring's settling tail leaves the NavHost transition
        // interruptible long after it looks parked, which can wedge it into a blank composition.
        val chatBack = MotdMotion.chatBackSpatial as TweenSpec<*>
        val drawerEntry = MotdMotion.navigationDrawerSpatial as TweenSpec<*>

        assertEquals(MotdMotion.ChatBackDurationMs, chatBack.durationMillis)
        assertEquals(MotdMotion.NavigationDurationMs, drawerEntry.durationMillis)
        assertTrue(chatBack.durationMillis > drawerEntry.durationMillis)
    }

    @Test
    fun `chat back duration is finite and independent of travel distance`() {
        val zeroVelocity = AnimationVector2D(0f, 0f)
        val durations =
            listOf(IntOffset(60, 0), IntOffset(1080, 0), IntOffset(4320, 0)).map { start ->
                TargetBasedAnimation(
                    MotdMotion.chatBackSpatial,
                    IntOffset.VectorConverter,
                    start,
                    IntOffset.Zero,
                    zeroVelocity,
                ).durationNanos
            }

        assertTrue(durations.all { it == MotdMotion.ChatBackDurationMs * 1_000_000L })
    }

    @Test
    fun `content size grows smoothly without overshoot`() {
        val start = IntSize(width = 320, height = 72)
        val target = IntSize(width = 320, height = 184)
        val animation =
            TargetBasedAnimation(
                MotdMotion.contentSize,
                IntSize.VectorConverter,
                start,
                target,
                AnimationVector2D(0f, 0f),
            )
        val samples =
            (0..100).map { step ->
                animation.getValueFromNanos(animation.durationNanos * step / 100)
            }

        assertTrue(samples.all { it.width == start.width })
        assertTrue(samples.any { it.height > start.height && it.height < target.height })
        assertTrue(samples.all { it.height in start.height..target.height })
        assertEquals(target, animation.getValueFromNanos(animation.durationNanos))
    }

    @Test
    fun `navigation fade is a bounded tween at navigation tempo`() {
        // In-place destination fades ride the same bounded-tween NavHost constraint as the slides.
        val navigationFade = MotdMotion.navigationFade as TweenSpec<*>

        assertEquals(MotdMotion.NavigationDurationMs, navigationFade.durationMillis)
    }

    @Test
    fun `color fade is a bounded micro tween`() {
        // Container-color transitions stay at the micro tempo, never an unbounded spring.
        val colorFade = MotdMotion.colorFade as TweenSpec<*>

        assertEquals(MotdMotion.MicroDurationMs, colorFade.durationMillis)
    }

    @Test
    fun `archive settle duration is monotonic and bounded`() {
        assertEquals(200, MotdMotion.archiveSettleDurationMillis(-1f))
        assertEquals(200, MotdMotion.archiveSettleDurationMillis(0f))
        assertEquals(250, MotdMotion.archiveSettleDurationMillis(.5f))
        assertEquals(300, MotdMotion.archiveSettleDurationMillis(1f))
        assertEquals(300, MotdMotion.archiveSettleDurationMillis(2f))
    }

    @Test
    fun `archive settle uses quintic ease out`() {
        assertEquals(0f, MotdMotion.archiveSettleEasing.transform(0f), 0f)
        assertEquals(.96875f, MotdMotion.archiveSettleEasing.transform(.5f), 0f)
        assertEquals(1f, MotdMotion.archiveSettleEasing.transform(1f), 0f)
    }
}
