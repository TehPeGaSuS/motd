package io.github.trevarj.motd.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The small, shared motion vocabulary used by the app's custom Compose transitions.
 *
 * Specs remain ordinary Compose animation specs so the platform animator scale is respected by
 * Compose's [androidx.compose.ui.MotionDurationScale]. The app deliberately has no separate motion
 * preference and does not add continuous decorative animations to the chat timeline.
 */
object MotdMotion {
    const val MicroDurationMs = 140
    const val StandardDurationMs = 210
    const val NavigationDurationMs = 340
    const val ArchiveSettleMinimumDurationMs = 200
    const val ArchiveSettleMaximumDurationMs = 300

    private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Quintic ease-out keeps the archive settle decisive without spring overshoot. */
    val archiveSettleEasing = Easing { fraction ->
        val inverse = 1f - fraction
        1f - inverse * inverse * inverse * inverse * inverse
    }
    private const val SoftSpringStiffness = 340f
    private const val MaterialDefaultSpatialDampingRatio = 0.9f
    private const val MaterialDefaultSpatialStiffness = 700f
    private const val ChatBackSpatialDampingRatio = 0.8f
    private const val ChatBackSpatialStiffness = 112.5f

    val fadeIn: FiniteAnimationSpec<Float> = tween(
        durationMillis = StandardDurationMs,
        easing = StandardEasing,
    )
    val fadeOut: FiniteAnimationSpec<Float> = tween(
        durationMillis = MicroDurationMs,
        easing = StandardEasing,
    )
    val microFadeIn: FiniteAnimationSpec<Float> = tween(
        durationMillis = MicroDurationMs,
        easing = StandardEasing,
    )
    val microFadeOut: FiniteAnimationSpec<Float> = tween(
        durationMillis = MicroDurationMs,
        easing = StandardEasing,
    )

    /**
     * The standard spatial spring used by Material 3's
     * [androidx.compose.material3.ModalNavigationDrawer]. Material 3 1.4 keeps its motion scheme
     * internal, so custom navigation mirrors the pinned token values.
     */
    val navigationDrawerSpatial: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = MaterialDefaultSpatialDampingRatio,
        stiffness = MaterialDefaultSpatialStiffness,
    )

    /**
     * A lightly underdamped spatial spring for returning from chat to the chat list. At this
     * damping ratio, its stiffness gives a 1.5x characteristic-speed increase over the prior
     * very-low token, with roughly 4/3 the characteristic time of [Spring.StiffnessLow].
     */
    val chatBackSpatial: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = ChatBackSpatialDampingRatio,
        stiffness = ChatBackSpatialStiffness,
    )

    /** A calm spring: responsive and soft, without a playful bounce. */
    val softSpring: FiniteAnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = SoftSpringStiffness,
    )
    val rowPlacement: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = SoftSpringStiffness,
    )
    val contentSize: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = SoftSpringStiffness,
    )

    /** Duration grows monotonically with the remaining fraction and remains within 200–300ms. */
    fun archiveSettleDurationMillis(remainingFraction: Float): Int =
        (ArchiveSettleMinimumDurationMs +
            (ArchiveSettleMaximumDurationMs - ArchiveSettleMinimumDurationMs) *
                remainingFraction.coerceIn(0f, 1f)).toInt()

    fun archiveSettleSpec(remainingFraction: Float): FiniteAnimationSpec<Float> = tween(
        durationMillis = archiveSettleDurationMillis(remainingFraction),
        easing = archiveSettleEasing,
    )
}
