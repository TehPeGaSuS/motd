package io.github.trevarj.motd.gesture.radial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the resting tab sits, and how a drag moves it. */
class OrbPlacementTest {
    private val screen = Size(400f, 900f)
    private val orb = Size(24f, 48f)

    @Test fun `the default tab sits low on the right, where a thumb already is`() {
        val placement = OrbPlacement()

        assertEquals(OrbEdge.RIGHT, placement.edge)
        assertEquals(DEFAULT_ORB_FRACTION, placement.verticalFraction, 0f)
        assertTrue(placement.verticalFraction > 0.5f)
    }

    @Test fun `a fraction that would hang the tab off an edge is pulled back inside`() {
        // Half a 48px tab in a 900px screen is 1/37.5 of the height.
        assertEquals(48f / 2f / 900f, clampOrbFraction(0f, orb.height, screen.height), 0.0001f)
        assertEquals(1f - 48f / 2f / 900f, clampOrbFraction(1f, orb.height, screen.height), 0.0001f)
        assertEquals(0.5f, clampOrbFraction(0.5f, orb.height, screen.height), 0.0001f)
    }

    @Test fun `a tab taller than the screen is centred rather than inverted`() {
        assertEquals(0.5f, clampOrbFraction(0.9f, orbHeight = 200f, screenHeight = 100f), 0f)
    }

    @Test fun `each edge parks the tab half its width inside that side`() {
        val left = orbCenter(OrbPlacement(OrbEdge.LEFT, 0.5f), screen, orb)
        val right = orbCenter(OrbPlacement(OrbEdge.RIGHT, 0.5f), screen, orb)

        assertEquals(12f, left.x, 0.001f)
        assertEquals(388f, right.x, 0.001f)
        assertEquals(450f, left.y, 0.001f)
        assertEquals(Offset(0f, 426f), orbTopLeft(OrbPlacement(OrbEdge.LEFT, 0.5f), screen, orb))
    }

    @Test fun `crossing the middle of the screen swaps the tab to the other edge`() {
        assertEquals(OrbEdge.LEFT, placementForDrag(Offset(120f, 300f), screen, orb).edge)
        assertEquals(OrbEdge.RIGHT, placementForDrag(Offset(280f, 300f), screen, orb).edge)
        assertEquals(1f / 3f, placementForDrag(Offset(280f, 300f), screen, orb).verticalFraction, 0.001f)
    }

    @Test fun `a drag off the top of the screen still leaves the whole tab visible`() {
        val dragged = placementForDrag(Offset(10f, -50f), screen, orb)

        assertEquals(OrbEdge.LEFT, dragged.edge)
        assertEquals(48f / 2f / 900f, dragged.verticalFraction, 0.0001f)
        assertTrue(orbTopLeft(dragged, screen, orb).y >= 0f)
    }

    // --- persistence ---

    @Test fun `a placement survives being written and read back`() {
        val placement = OrbPlacement(OrbEdge.LEFT, 0.25f)

        assertEquals(placement, decodeOrbPlacement(encodeOrbPlacement(placement)))
    }

    @Test fun `an unreadable or absent placement falls back to the default corner`() {
        assertEquals(OrbPlacement(), decodeOrbPlacement(null))
        assertEquals(OrbPlacement(), decodeOrbPlacement(""))
        assertEquals(OrbPlacement(), decodeOrbPlacement("{not json"))
    }

    /** An edge a newer build invented must not take the orb off screen or fail the whole placement. */
    @Test fun `an unknown edge decodes to the default side`() {
        val decoded = decodeOrbPlacement("""{"edge":"FLOATING","verticalFraction":0.4}""")

        assertEquals(OrbEdge.RIGHT, decoded.edge)
        assertEquals(0.4f, decoded.verticalFraction, 0.0001f)
    }

    @Test fun `a stored fraction outside the screen is clamped on the way in`() {
        assertEquals(1f, decodeOrbPlacement("""{"edge":"LEFT","verticalFraction":7.5}""").verticalFraction, 0f)
        assertEquals(0f, decodeOrbPlacement("""{"edge":"LEFT","verticalFraction":-3.0}""").verticalFraction, 0f)
    }
}
