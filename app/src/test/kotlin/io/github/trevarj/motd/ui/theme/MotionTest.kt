package io.github.trevarj.motd.ui.theme

import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class MotionTest {
    @Test
    fun `chat back spring is one and a half times faster and springier than drawer entry`() {
        val chatBack = MotdMotion.chatBackSpatial as SpringSpec<*>
        val drawerEntry = MotdMotion.navigationDrawerSpatial as SpringSpec<*>

        assertEquals(0.8f, chatBack.dampingRatio, 0f)
        assertEquals(112.5f, chatBack.stiffness, 0f)
        assertEquals(4f / 3f, sqrt(Spring.StiffnessLow / chatBack.stiffness), 0f)
        assertTrue(chatBack.dampingRatio < drawerEntry.dampingRatio)
        assertTrue(chatBack.stiffness < drawerEntry.stiffness)
    }

    @Test
    fun `chat back spring takes approximately four thirds as long as low stiffness`() {
        val start = IntOffset(1080, 0)
        val target = IntOffset.Zero
        val zeroVelocity = AnimationVector2D(0f, 0f)
        val lowStiffnessBaseline: SpringSpec<IntOffset> = spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessLow,
        )
        val baseline = TargetBasedAnimation(
            lowStiffnessBaseline,
            IntOffset.VectorConverter,
            start,
            target,
            zeroVelocity,
        )
        val chatBack = TargetBasedAnimation(
            MotdMotion.chatBackSpatial,
            IntOffset.VectorConverter,
            start,
            target,
            zeroVelocity,
        )

        val durationRatio = chatBack.durationNanos.toDouble() / baseline.durationNanos
        assertTrue(durationRatio in 1.28..1.38)
    }

    @Test
    fun `content size grows smoothly without overshoot`() {
        val start = IntSize(width = 320, height = 72)
        val target = IntSize(width = 320, height = 184)
        val animation = TargetBasedAnimation(
            MotdMotion.contentSize,
            IntSize.VectorConverter,
            start,
            target,
            AnimationVector2D(0f, 0f),
        )
        val samples = (0..100).map { step ->
            animation.getValueFromNanos(animation.durationNanos * step / 100)
        }

        assertTrue(samples.all { it.width == start.width })
        assertTrue(samples.any { it.height > start.height && it.height < target.height })
        assertTrue(samples.all { it.height in start.height..target.height })
        assertEquals(target, animation.getValueFromNanos(animation.durationNanos))
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
