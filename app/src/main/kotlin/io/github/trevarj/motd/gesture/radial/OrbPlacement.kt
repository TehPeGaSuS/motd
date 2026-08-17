package io.github.trevarj.motd.gesture.radial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.trevarj.motd.gesture.FallbackEnumSerializer
import io.github.trevarj.motd.gesture.gestureMenuJson
import kotlinx.serialization.Serializable

/**
 * Where the resting orb tab lives.
 *
 * Stored as an edge plus a fraction rather than a pixel offset, so the tab keeps its place across
 * rotation, split screen, and a different device restoring the same preferences.
 */

/** Which side the tab is docked against. Only the vertical edges: the tab is a side tab. */
@Serializable(with = OrbEdgeSerializer::class)
enum class OrbEdge { LEFT, RIGHT }

/** Default resting height: below centre, in easy thumb reach without covering a chat's top bar. */
const val DEFAULT_ORB_FRACTION = 0.66f

@Serializable
data class OrbPlacement(
    val edge: OrbEdge = OrbEdge.RIGHT,
    /** Centre of the tab as a fraction of screen height, 0 = top. */
    val verticalFraction: Float = DEFAULT_ORB_FRACTION,
)

/** An edge name a newer build invented docks right rather than failing the whole placement. */
object OrbEdgeSerializer : FallbackEnumSerializer<OrbEdge>(
    serialName = "io.github.trevarj.motd.gesture.radial.OrbEdge",
    entries = OrbEdge.entries,
    fallback = OrbEdge.RIGHT,
)

internal fun encodeOrbPlacement(placement: OrbPlacement): String =
    gestureMenuJson.encodeToString(OrbPlacement.serializer(), placement)

/** Decodes a stored placement; anything unreadable falls back to the default corner. */
internal fun decodeOrbPlacement(raw: String?): OrbPlacement {
    if (raw.isNullOrBlank()) return OrbPlacement()
    return runCatching { gestureMenuJson.decodeFromString(OrbPlacement.serializer(), raw) }
        .getOrElse { OrbPlacement() }
        .let { it.copy(verticalFraction = it.verticalFraction.coerceIn(0f, 1f)) }
}

/** Keep the whole tab on screen: the fraction addresses its centre, so half a tab is the limit. */
fun clampOrbFraction(fraction: Float, orbHeight: Float, screenHeight: Float): Float {
    if (screenHeight <= 0f) return fraction.coerceIn(0f, 1f)
    val half = (orbHeight / 2f) / screenHeight
    // A tab taller than the screen has no legal position; centre it instead of inverting the range.
    if (half >= 0.5f) return 0.5f
    return fraction.coerceIn(half, 1f - half)
}

/** Centre of the resting tab in screen pixels. */
fun orbCenter(placement: OrbPlacement, screenSize: Size, orbSize: Size): Offset {
    val fraction = clampOrbFraction(placement.verticalFraction, orbSize.height, screenSize.height)
    val x = when (placement.edge) {
        OrbEdge.LEFT -> orbSize.width / 2f
        OrbEdge.RIGHT -> screenSize.width - orbSize.width / 2f
    }
    return Offset(x, screenSize.height * fraction)
}

/** Top-left of the resting tab in screen pixels, for laying the composable out. */
fun orbTopLeft(placement: OrbPlacement, screenSize: Size, orbSize: Size): Offset {
    val center = orbCenter(placement, screenSize, orbSize)
    return Offset(center.x - orbSize.width / 2f, center.y - orbSize.height / 2f)
}

/**
 * Placement the tab takes if the drag ends with the finger at [position].
 *
 * The edge follows the half of the screen the finger is in, so crossing the middle swaps sides in
 * one motion; the height follows directly and is clamped to keep the tab whole.
 */
fun placementForDrag(position: Offset, screenSize: Size, orbSize: Size): OrbPlacement {
    val edge = if (position.x < screenSize.width / 2f) OrbEdge.LEFT else OrbEdge.RIGHT
    val fraction = if (screenSize.height <= 0f) DEFAULT_ORB_FRACTION else position.y / screenSize.height
    return OrbPlacement(edge, clampOrbFraction(fraction, orbSize.height, screenSize.height))
}
