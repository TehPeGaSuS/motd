package io.github.trevarj.motd.gesture.radial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The pure math behind the radial menu: where a ring can fan, how wide its slices are, and what the
 * finger is currently over.
 *
 * Everything here takes pixels and plain geometry, never `Dp`, `Density` or composition state, so
 * the whole interaction can be pinned by unit tests (the `VoiceRecordButton` split: a pure decision
 * function plus a thin `awaitEachGesture` shell).
 *
 * Angles are screen degrees: 0° points right along +x and they grow clockwise, because the y axis
 * points down. That makes "up" 270° and "down" 90°.
 */

/** Comfortable slice width. A ring wider than this per slice reads at a glance; narrower is a squeeze. */
const val PREFERRED_SLICE_DEGREES = 24f

/** Below this a slice is not reliably hittable with a thumb, so entries spill into a trailing "More…". */
const val MIN_SLICE_DEGREES = 18f

/**
 * The narrowest arc a ring is ever laid out in.
 *
 * Two slices' worth of hard floor: a ring pinned into a screen corner still has to offer one real
 * entry plus the overflow slice that reaches the rest, otherwise the entries below it would be
 * unreachable rather than merely awkward.
 */
const val MIN_RING_SWEEP_DEGREES = 2 * MIN_SLICE_DEGREES

/** A ring never wraps all the way round: the orb is edge-docked, so the menu fans inward. */
const val MAX_RING_SWEEP_DEGREES = 180f

/** Every radial distance in pixels, resolved once per gesture from the current density. */
data class RadialMetrics(
    /** Inside this the finger is "at the centre": cancel at the root, back out of a submenu below it. */
    val deadzoneRadius: Float,
    /** Where the slice band starts. Equal to the deadzone: the well ends exactly where slices begin. */
    val bandInnerRadius: Float,
    /** Where the drawn band ends. */
    val bandOuterRadius: Float,
    /** Drag past this and a ring-opening slice descends. The gap above the band is the commit margin. */
    val descendRadius: Float,
    /** Radius the labels sit at; the outermost thing that has to stay on screen. */
    val labelRadius: Float,
    /** How far the labels stay clear of the screen edge. */
    val edgeMargin: Float,
)

/** Which screen edge a ring's centre is pinned against, and therefore which way it fans. */
enum class RadialDock { LEFT, RIGHT, TOP, BOTTOM }

/**
 * A laid-out ring: [slices] equal wedges covering [sweepDegrees] starting at [startDegrees].
 *
 * The arc is the only thing that knows about angles; hit testing and drawing both go through it, so
 * a slice can never be drawn in one place and hit in another.
 */
data class RadialArc(
    val startDegrees: Float,
    val sweepDegrees: Float,
    val slices: Int,
) {
    val sliceDegrees: Float get() = if (slices <= 0) 0f else sweepDegrees / slices

    fun sliceStartDegrees(index: Int): Float = normalizeDegrees(startDegrees + sliceDegrees * index)

    fun sliceCenterDegrees(index: Int): Float =
        normalizeDegrees(startDegrees + sliceDegrees * (index + 0.5f))

    /** Slice under [degrees], or null when the direction falls off either end of the arc. */
    fun indexAt(degrees: Float): Int? {
        if (slices <= 0 || sweepDegrees <= 0f) return null
        val delta = normalizeDegrees(degrees - startDegrees)
        if (delta > sweepDegrees) return null
        return min(slices - 1, floor(delta / sliceDegrees).toInt())
    }
}

/** True when every slice still gets the comfortable width, so all labels can be shown at once. */
fun RadialArc.isComfortable(): Boolean = sliceDegrees >= PREFERRED_SLICE_DEGREES

/** What the finger is over, relative to one ring. */
sealed interface RadialHit {
    /** The centre well: cancels the gesture at the root ring, pops one ring below it. */
    data object Deadzone : RadialHit

    /** Off the arc entirely — behind the dock edge, or past a corner-clamped end. Selects nothing. */
    data object Outside : RadialHit

    /** Over slice [index], not yet committed to descending. */
    data class Slice(val index: Int) : RadialHit

    /** Dragged past the commit radius on slice [index]. */
    data class Descend(val index: Int) : RadialHit
}

/** Nearest screen edge to [center]; ties resolve left, right, top, bottom so layout is deterministic. */
fun dockFor(center: Offset, screenSize: Size): RadialDock {
    val left = center.x
    val right = screenSize.width - center.x
    val top = center.y
    val bottom = screenSize.height - center.y
    val nearest = minOf(left, right, top, bottom)
    return when (nearest) {
        left -> RadialDock.LEFT
        right -> RadialDock.RIGHT
        top -> RadialDock.TOP
        else -> RadialDock.BOTTOM
    }
}

/** The direction a ring docked on [dock] fans towards: away from the edge it is pinned against. */
fun inwardDegrees(dock: RadialDock): Float = when (dock) {
    RadialDock.LEFT -> 0f
    RadialDock.TOP -> 90f
    RadialDock.RIGHT -> 180f
    RadialDock.BOTTOM -> 270f
}

/**
 * How wide a ring centred on [center] may fan before its labels leave the screen.
 *
 * The half-arc is symmetric about the inward direction only when there is room on both sides. Near a
 * corner one side runs out first: a label at deviation φ sits `labelRadius · sin φ` off the inward
 * axis, so the room perpendicular to that axis caps φ directly. Clamped to [MIN_RING_SWEEP_DEGREES]
 * so a ring pinned into a corner still has somewhere to draw.
 */
fun availableSweepDegrees(center: Offset, screenSize: Size, metrics: RadialMetrics): Float {
    val inward = inwardDegrees(dockFor(center, screenSize))
    val (before, after) = sideSweeps(center, screenSize, metrics, inward)
    return (before + after).coerceIn(MIN_RING_SWEEP_DEGREES, MAX_RING_SWEEP_DEGREES)
}

/**
 * Lay [slices] wedges out around [center], fanning inward from the nearest screen edge.
 *
 * The arc always uses the whole available sweep rather than reserving a preferred slice width: a
 * wider wedge is strictly easier to hit, and how many entries fit is already decided by
 * [ringCapacity] before this is called.
 */
fun arcForDock(center: Offset, screenSize: Size, slices: Int, metrics: RadialMetrics): RadialArc {
    val inward = inwardDegrees(dockFor(center, screenSize))
    val (before, after) = sideSweeps(center, screenSize, metrics, inward)
    val raw = before + after
    // Each side is capped at a quarter turn, so the two together never exceed the inward half-plane.
    return if (raw < MIN_RING_SWEEP_DEGREES) {
        // Both sides are pinched (a tight corner): centre the minimum arc on the inward direction
        // rather than letting the ring collapse to nothing.
        RadialArc(normalizeDegrees(inward - MIN_RING_SWEEP_DEGREES / 2f), MIN_RING_SWEEP_DEGREES, slices)
    } else {
        RadialArc(normalizeDegrees(inward - before), raw, slices)
    }
}

/** How many slices an arc of [sweepDegrees] carries without dropping below the hard floor. */
fun ringCapacity(sweepDegrees: Float): Int =
    max(2, floor(sweepDegrees / MIN_SLICE_DEGREES).toInt())

/** Classify [position] against a ring centred on [center] and laid out as [arc]. */
fun radialHit(center: Offset, position: Offset, arc: RadialArc, metrics: RadialMetrics): RadialHit {
    val distance = hypot(position.x - center.x, position.y - center.y)
    if (distance < metrics.deadzoneRadius) return RadialHit.Deadzone
    val index = arc.indexAt(bearingDegrees(center, position)) ?: return RadialHit.Outside
    if (distance < metrics.bandInnerRadius) return RadialHit.Outside
    return if (distance >= metrics.descendRadius) RadialHit.Descend(index) else RadialHit.Slice(index)
}

/** Point [radius] away from [center] along [degrees]. */
fun polarOffset(center: Offset, degrees: Float, radius: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    return Offset(
        x = center.x + (radius * cos(radians)).toFloat(),
        y = center.y + (radius * sin(radians)).toFloat(),
    )
}

/** Where slice [index]'s icon/label sits, [radius] out from [center]. */
fun sliceAnchor(center: Offset, arc: RadialArc, index: Int, radius: Float): Offset =
    polarOffset(center, arc.sliceCenterDegrees(index), radius)

/** Direction from [center] to [position], in screen degrees. */
fun bearingDegrees(center: Offset, position: Offset): Float =
    normalizeDegrees(Math.toDegrees(atan2((position.y - center.y).toDouble(), (position.x - center.x).toDouble())).toFloat())

/** Fold any angle into `[0, 360)`. */
fun normalizeDegrees(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

/**
 * How far the arc may swing to either side of [inward] before the labels leave the screen, capped at
 * a quarter turn each so the two halves never add up to more than the inward half-plane.
 */
private fun sideSweeps(
    center: Offset,
    screenSize: Size,
    metrics: RadialMetrics,
    inward: Float,
): Pair<Float, Float> {
    // Rotating the inward direction by ±90° lands on the axis the labels can run out of room along.
    val before = swingLimit(roomAlong(center, screenSize, inward - 90f, metrics.edgeMargin), metrics.labelRadius)
    val after = swingLimit(roomAlong(center, screenSize, inward + 90f, metrics.edgeMargin), metrics.labelRadius)
    return before to after
}

/** Largest deviation whose label still lands inside the margin, given [room] perpendicular to the arc. */
private fun swingLimit(room: Float, labelRadius: Float): Float {
    if (labelRadius <= 0f) return MAX_RING_SWEEP_DEGREES / 2f
    val ratio = (room / labelRadius).coerceIn(0f, 1f)
    return Math.toDegrees(asin(ratio.toDouble())).toFloat()
}

/** Distance from [center] to the margin box along an axis-aligned [degrees] direction. */
private fun roomAlong(center: Offset, screenSize: Size, degrees: Float, margin: Float): Float {
    val direction = normalizeDegrees(degrees)
    val room = when {
        direction < 45f || direction >= 315f -> screenSize.width - margin - center.x
        direction < 135f -> screenSize.height - margin - center.y
        direction < 225f -> center.x - margin
        else -> center.y - margin
    }
    return max(0f, room)
}
