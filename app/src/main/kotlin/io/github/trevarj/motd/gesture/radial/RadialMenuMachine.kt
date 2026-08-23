package io.github.trevarj.motd.gesture.radial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.trevarj.motd.gesture.GestureAction
import io.github.trevarj.motd.gesture.GestureIcon
import io.github.trevarj.motd.gesture.MAX_GESTURE_RINGS

/**
 * The radial menu as a pure state machine: a stack of open rings plus the transitions one pointer
 * sample can cause.
 *
 * No timing anywhere. Descending is "drag past the commit radius", backing out is "return to the
 * centre well", and both are decided from the pointer position alone — a dwell timer would make the
 * same drag behave differently depending on how fast the user moved.
 *
 * Providers are already resolved by the time anything here runs (see [RadialEntry]): the ring the
 * finger is committing to must not change under it mid-gesture.
 */

/** How many rings can be stacked at once, whatever their kind. A backstop against a runaway descend. */
const val MAX_RADIAL_RINGS = 6

/** Id prefix for the synthetic "More…" slice, so it can never collide with an authored node id. */
private const val OVERFLOW_ID_PREFIX = "gesture-overflow-"

/**
 * A menu node with every provider already fanned out and every label already resolved.
 *
 * A node that opens a ring has [children]; a node that runs something has an [action]. Anything with
 * neither is inert — an empty provider ring stays visible (so the menu keeps its shape) but releasing
 * on it does nothing.
 */
data class RadialEntry(
    val id: String,
    val label: String,
    val icon: GestureIcon,
    val children: List<RadialEntry> = emptyList(),
    val action: GestureAction? = null,
    /** True for the synthetic "More…" slice, which continues a ring rather than nesting a new level. */
    val overflow: Boolean = false,
)

/** One open ring. */
data class RadialRing(
    /** Exactly the slices drawn, including the trailing "More…" when the entries did not all fit. */
    val entries: List<RadialEntry>,
    val center: Offset,
    val arc: RadialArc,
    val focus: Int? = null,
    /**
     * True once the pointer has left this ring's centre well.
     *
     * A ring is born under the finger, so without this the sample right after a descend would read
     * as "returned to the centre" and pop the ring again immediately.
     */
    val armed: Boolean = false,
    /** True when this ring is the continuation of an overflowing one rather than a nested level. */
    val overflow: Boolean = false,
)

/** The open menu. Never empty: the root ring exists for as long as the menu does. */
data class RadialMenuState(
    val rings: List<RadialRing>,
) {
    val active: RadialRing get() = rings.last()

    val focusedEntry: RadialEntry? get() = active.focus?.let { active.entries.getOrNull(it) }

    /** Authored nesting depth; overflow rings continue a ring instead of adding a level. */
    val depth: Int get() = rings.count { !it.overflow }
}

/** What one pointer sample changed, so the caller can fire the matching haptic. */
enum class RadialEffect { NONE, FOCUS_CHANGED, DESCENDED, POPPED }

data class RadialUpdate(
    val state: RadialMenuState,
    val effect: RadialEffect,
)

/** What lifting the finger means. */
sealed interface RadialRelease {
    /** [entry] is a leaf under the finger; run its action. */
    data class Execute(
        val entry: RadialEntry,
    ) : RadialRelease

    /** Released in the centre well, off the arc, or on a slice that only opens a ring. */
    data object Cancel : RadialRelease
}

/** Open [root]'s ring centred on the finger. */
fun openRadialMenu(
    root: RadialEntry,
    center: Offset,
    screenSize: Size,
    metrics: RadialMetrics,
    moreLabel: String,
): RadialMenuState = RadialMenuState(listOf(buildRadialRing(root.children, center, screenSize, metrics, moreLabel)))

/**
 * Lay [entries] out around [center], spilling whatever does not fit into a trailing "More…" slice.
 *
 * The overflow slice carries the remainder as its own children, so descending into it re-runs this
 * function at the new centre and the tail is reachable however cramped the corner was. Each spill
 * strictly shrinks the remainder, so the chain always terminates.
 */
fun buildRadialRing(
    entries: List<RadialEntry>,
    center: Offset,
    screenSize: Size,
    metrics: RadialMetrics,
    moreLabel: String,
    overflow: Boolean = false,
): RadialRing {
    val capacity = ringCapacity(availableSweepDegrees(center, screenSize, metrics))
    val drawn =
        if (entries.size <= capacity) {
            entries
        } else {
            val kept = entries.take(capacity - 1)
            kept +
                RadialEntry(
                    id = "$OVERFLOW_ID_PREFIX${entries.size}",
                    label = moreLabel,
                    icon = GestureIcon.MORE,
                    children = entries.drop(capacity - 1),
                    overflow = true,
                )
        }
    return RadialRing(
        entries = drawn,
        center = center,
        arc = arcForDock(center, screenSize, drawn.size, metrics),
        overflow = overflow,
    )
}

/** Apply one pointer sample at [position] to [state]. */
fun onRadialPointer(
    state: RadialMenuState,
    position: Offset,
    screenSize: Size,
    metrics: RadialMetrics,
    moreLabel: String,
): RadialUpdate {
    val active = state.active
    return when (val hit = radialHit(active.center, position, active.arc, metrics)) {
        RadialHit.Deadzone -> {
            if (active.armed && state.rings.size > 1) {
                RadialUpdate(RadialMenuState(state.rings.dropLast(1)), RadialEffect.POPPED)
            } else {
                // Not yet armed: the finger has simply not left the well this ring was born under.
                focused(state, null, arm = false)
            }
        }

        RadialHit.Outside -> {
            focused(state, null, arm = true)
        }

        is RadialHit.Slice -> {
            focused(state, hit.index, arm = true)
        }

        is RadialHit.Descend -> {
            val entry = active.entries.getOrNull(hit.index)
            if (entry != null && entry.children.isNotEmpty() && canDescend(state, entry)) {
                val armed = state.rings.dropLast(1) + active.copy(focus = hit.index, armed = true)
                val ring =
                    buildRadialRing(
                        entries = entry.children,
                        center = position,
                        screenSize = screenSize,
                        metrics = metrics,
                        moreLabel = moreLabel,
                        overflow = entry.overflow,
                    )
                RadialUpdate(RadialMenuState(armed + ring), RadialEffect.DESCENDED)
            } else {
                // A leaf dragged past the commit radius stays selected: releasing out there runs it.
                focused(state, hit.index, arm = true)
            }
        }
    }
}

/** Decide what lifting the finger does. */
fun onRadialRelease(state: RadialMenuState): RadialRelease {
    val entry = state.focusedEntry ?: return RadialRelease.Cancel
    // Releasing on a ring-opening slice commits nothing: the ring it would have opened is exactly
    // what the drag never entered, and guessing an action out of that would be a misfire.
    return entry.action?.let { RadialRelease.Execute(entry) } ?: RadialRelease.Cancel
}

/** Overflow continues the current level, so only authored rings spend the nesting budget. */
private fun canDescend(
    state: RadialMenuState,
    entry: RadialEntry,
): Boolean =
    when {
        state.rings.size >= MAX_RADIAL_RINGS -> false
        entry.overflow -> true
        else -> state.depth < MAX_GESTURE_RINGS
    }

private fun focused(
    state: RadialMenuState,
    index: Int?,
    arm: Boolean,
): RadialUpdate {
    val active = state.active
    val armed = active.armed || arm
    if (active.focus == index && active.armed == armed) return RadialUpdate(state, RadialEffect.NONE)
    val updated = active.copy(focus = index, armed = armed)
    val effect = if (active.focus == index) RadialEffect.NONE else RadialEffect.FOCUS_CHANGED
    return RadialUpdate(RadialMenuState(state.rings.dropLast(1) + updated), effect)
}
