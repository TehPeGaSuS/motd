package io.github.trevarj.motd.gesture.radial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radial menu's geometry, in device-independent pixels at density 1 so the numbers read as the
 * dp figures the design is specified in.
 */
class RadialGeometryTest {
    private val metrics =
        RadialMetrics(
            deadzoneRadius = 40f,
            bandInnerRadius = 40f,
            bandOuterRadius = 104f,
            descendRadius = 116f,
            labelRadius = 124f,
            edgeMargin = 16f,
        )
    private val screen = Size(400f, 900f)

    // --- docking ---

    @Test fun `a ring docks against the edge it is nearest`() {
        assertEquals(RadialDock.LEFT, dockFor(Offset(12f, 450f), screen))
        assertEquals(RadialDock.RIGHT, dockFor(Offset(388f, 450f), screen))
        assertEquals(RadialDock.TOP, dockFor(Offset(200f, 10f), screen))
        assertEquals(RadialDock.BOTTOM, dockFor(Offset(200f, 890f), screen))
    }

    @Test fun `each dock fans away from its own edge`() {
        assertEquals(0f, inwardDegrees(RadialDock.LEFT), 0f)
        assertEquals(180f, inwardDegrees(RadialDock.RIGHT), 0f)
        // Screen degrees grow clockwise with y pointing down, so a top dock fans "down" at 90°.
        assertEquals(90f, inwardDegrees(RadialDock.TOP), 0f)
        assertEquals(270f, inwardDegrees(RadialDock.BOTTOM), 0f)
    }

    // --- arc ---

    @Test fun `an edge dock with room on both sides gets the full inward half`() {
        val arc = arcForDock(Offset(12f, 450f), screen, slices = 8, metrics = metrics)

        assertEquals(180f, arc.sweepDegrees, 0.01f)
        assertEquals(270f, arc.startDegrees, 0.01f)
        assertEquals(22.5f, arc.sliceDegrees, 0.01f)
    }

    @Test fun `a right dock mirrors the same half onto the other side`() {
        val arc = arcForDock(Offset(388f, 450f), screen, slices = 4, metrics = metrics)

        assertEquals(180f, arc.sweepDegrees, 0.01f)
        assertEquals(90f, arc.startDegrees, 0.01f)
    }

    /** A ring near a corner has to give up the side that would push its labels off screen. */
    @Test fun `a corner clamps the sweep and keeps every label inside the margin`() {
        val center = Offset(12f, 40f)
        val arc = arcForDock(center, screen, slices = 8, metrics = metrics)

        assertTrue("corner arc should be narrower than a half turn", arc.sweepDegrees < 180f)
        val ends = listOf(arc.startDegrees, arc.startDegrees + arc.sweepDegrees)
        ends.forEach { degrees ->
            val label = polarOffset(center, degrees, metrics.labelRadius)
            assertTrue("label at $degrees left the top margin: $label", label.y >= metrics.edgeMargin - 0.01f)
        }
    }

    @Test fun `a ring pinched on both sides still gets the two-slice minimum`() {
        // A screen barely taller than the label radius leaves no room either way.
        val tiny = Size(400f, 60f)
        val arc = arcForDock(Offset(12f, 30f), tiny, slices = 2, metrics = metrics)

        assertEquals(MIN_RING_SWEEP_DEGREES, arc.sweepDegrees, 0.01f)
        assertEquals(MIN_RING_SWEEP_DEGREES, availableSweepDegrees(Offset(12f, 30f), tiny, metrics), 0.01f)
    }

    // --- capacity ---

    @Test fun `capacity is the hard floor, and a full ring of eight fits a half turn`() {
        assertEquals(10, ringCapacity(180f))
        assertEquals(5, ringCapacity(100f))
        assertEquals(2, ringCapacity(MIN_RING_SWEEP_DEGREES))
        // Never below two: a corner ring still owes one real slice plus the way to the rest.
        assertEquals(2, ringCapacity(0f))
        assertTrue(ringCapacity(180f) >= 8)
    }

    @Test fun `a ring is comfortable only while its slices stay above the preferred width`() {
        assertTrue(RadialArc(0f, 180f, 6).isComfortable())
        assertTrue(!RadialArc(0f, 180f, 8).isComfortable())
        assertEquals(PREFERRED_SLICE_DEGREES, RadialArc(0f, 96f, 4).sliceDegrees, 0.01f)
    }

    // --- angles ---

    @Test fun `slices divide the arc in order and answer for their own wedge`() {
        val arc = RadialArc(startDegrees = 270f, sweepDegrees = 180f, slices = 4)

        assertEquals(270f, arc.sliceStartDegrees(0), 0.01f)
        assertEquals(292.5f, arc.sliceCenterDegrees(0), 0.01f)
        assertEquals(0, arc.indexAt(280f))
        assertEquals(1, arc.indexAt(340f))
        assertEquals(2, arc.indexAt(20f))
        assertEquals(3, arc.indexAt(80f))
    }

    @Test fun `a direction off either end of the arc belongs to no slice`() {
        val arc = RadialArc(startDegrees = 270f, sweepDegrees = 180f, slices = 4)

        assertNull(arc.indexAt(180f))
        assertNull(arc.indexAt(200f))
        assertNull(arc.indexAt(269f))
        // The far end is inclusive only up to the sweep itself.
        assertEquals(3, arc.indexAt(90f))
        assertNull(arc.indexAt(91f))
    }

    @Test fun `an empty ring answers for nothing`() {
        assertNull(RadialArc(0f, 180f, 0).indexAt(90f))
        assertEquals(0f, RadialArc(0f, 180f, 0).sliceDegrees, 0f)
    }

    // --- hit testing ---

    @Test fun `the centre well, the band and the commit radius are three different answers`() {
        val center = Offset(200f, 450f)
        val arc = RadialArc(startDegrees = 270f, sweepDegrees = 180f, slices = 4)

        assertEquals(RadialHit.Deadzone, radialHit(center, center + Offset(20f, 0f), arc, metrics))
        assertEquals(RadialHit.Slice(2), radialHit(center, center + Offset(70f, 0f), arc, metrics))
        // Still inside the band at 110px: the gap above it is the commit margin, not a hit zone.
        assertEquals(RadialHit.Slice(2), radialHit(center, center + Offset(110f, 0f), arc, metrics))
        assertEquals(RadialHit.Descend(2), radialHit(center, center + Offset(130f, 0f), arc, metrics))
    }

    @Test fun `dragging behind the dock edge selects nothing however far it goes`() {
        val center = Offset(200f, 450f)
        val arc = RadialArc(startDegrees = 270f, sweepDegrees = 180f, slices = 4)

        assertEquals(RadialHit.Outside, radialHit(center, center + Offset(-70f, 0f), arc, metrics))
        assertEquals(RadialHit.Outside, radialHit(center, center + Offset(-300f, 0f), arc, metrics))
    }

    @Test fun `an anchor sits on its own slice`() {
        val center = Offset(200f, 450f)
        val arc = RadialArc(startDegrees = 270f, sweepDegrees = 180f, slices = 4)
        val anchor = sliceAnchor(center, arc, index = 1, radius = 80f)

        assertEquals(RadialHit.Slice(1), radialHit(center, anchor, arc, metrics))
    }

    @Test fun `angles fold into a single turn`() {
        assertEquals(10f, normalizeDegrees(370f), 0.001f)
        assertEquals(350f, normalizeDegrees(-10f), 0.001f)
        assertEquals(90f, bearingDegrees(Offset.Zero, Offset(0f, 5f)), 0.001f)
        assertEquals(180f, bearingDegrees(Offset.Zero, Offset(-5f, 0f)), 0.001f)
    }
}
