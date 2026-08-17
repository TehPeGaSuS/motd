package io.github.trevarj.motd.gesture.radial

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.trevarj.motd.gesture.GestureAction
import io.github.trevarj.motd.gesture.GestureIcon
import io.github.trevarj.motd.gesture.MAX_GESTURE_RINGS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ring stack and its transitions, driven exactly as a finger would drive them. */
class RadialMenuMachineTest {
    private val metrics = RadialMetrics(
        deadzoneRadius = 40f,
        bandInnerRadius = 40f,
        bandOuterRadius = 104f,
        descendRadius = 116f,
        labelRadius = 124f,
        edgeMargin = 16f,
    )
    private val screen = Size(400f, 900f)
    private val orb = Offset(12f, 450f)
    private val more = "More…"

    private fun leaf(id: String, action: GestureAction = GestureAction.NextUnread) =
        RadialEntry(id = id, label = id, icon = GestureIcon.BOLT, action = action)

    private fun submenu(id: String, vararg children: RadialEntry) =
        RadialEntry(id = id, label = id, icon = GestureIcon.FOLDER, children = children.toList())

    private fun open(root: RadialEntry, center: Offset = orb) =
        openRadialMenu(root, center, screen, metrics, more)

    private fun move(state: RadialMenuState, position: Offset) =
        onRadialPointer(state, position, screen, metrics, more)

    /** A point [radius] out from the ring's centre, on slice [index]. */
    private fun onSlice(ring: RadialRing, index: Int, radius: Float): Offset =
        sliceAnchor(ring.center, ring.arc, index, radius)

    // --- opening ---

    @Test fun `the menu opens on the root's own children`() {
        val state = open(submenu("root", leaf("a"), leaf("b"), leaf("c")))

        assertEquals(1, state.rings.size)
        assertEquals(listOf("a", "b", "c"), state.active.entries.map { it.id })
        assertEquals(orb, state.active.center)
        assertNull(state.focusedEntry)
    }

    @Test fun `an empty root still opens, and releasing on it commits nothing`() {
        val state = open(submenu("root"))

        assertEquals(0, state.active.entries.size)
        assertEquals(RadialRelease.Cancel, onRadialRelease(state))
    }

    // --- focus ---

    @Test fun `moving onto a slice focuses it and reports the change once`() {
        val state = open(submenu("root", leaf("a"), leaf("b"), leaf("c"), leaf("d")))
        val target = onSlice(state.active, 2, 70f)

        val first = move(state, target)
        assertEquals(RadialEffect.FOCUS_CHANGED, first.effect)
        assertEquals("c", first.state.focusedEntry?.id)

        // Same slice, a little further out: nothing changed, so nothing should be reported.
        val second = move(first.state, onSlice(first.state.active, 2, 90f))
        assertEquals(RadialEffect.NONE, second.effect)
        assertSame(first.state, second.state)
    }

    @Test fun `dragging off the arc drops the selection`() {
        val state = open(submenu("root", leaf("a"), leaf("b")))
        val focused = move(state, onSlice(state.active, 0, 70f)).state

        val cleared = move(focused, orb + Offset(-90f, 0f))

        assertEquals(RadialEffect.FOCUS_CHANGED, cleared.effect)
        assertNull(cleared.state.focusedEntry)
        assertEquals(RadialRelease.Cancel, onRadialRelease(cleared.state))
    }

    // --- release ---

    @Test fun `releasing on a leaf runs it`() {
        val state = open(submenu("root", leaf("a", GestureAction.MarkAllRead), leaf("b")))
        val focused = move(state, onSlice(state.active, 0, 70f)).state

        val release = onRadialRelease(focused)

        assertTrue(release is RadialRelease.Execute)
        assertEquals(GestureAction.MarkAllRead, (release as RadialRelease.Execute).entry.action)
    }

    /** A ring-opening slice released without ever descending has committed to nothing. */
    @Test fun `releasing on a submenu slice cancels rather than guessing`() {
        val state = open(submenu("root", submenu("tools", leaf("a")), leaf("b")))
        val focused = move(state, onSlice(state.active, 0, 70f)).state

        assertEquals("tools", focused.focusedEntry?.id)
        assertEquals(RadialRelease.Cancel, onRadialRelease(focused))
    }

    @Test fun `a leaf dragged past the commit radius stays selected`() {
        val state = open(submenu("root", leaf("a"), leaf("b")))

        val update = move(state, onSlice(state.active, 1, 200f))

        assertEquals(1, update.state.rings.size)
        assertEquals("b", update.state.focusedEntry?.id)
        assertTrue(onRadialRelease(update.state) is RadialRelease.Execute)
    }

    // --- descending and backing out ---

    @Test fun `dragging past the commit radius on a submenu opens its ring at the crossing point`() {
        val state = open(submenu("root", submenu("tools", leaf("x"), leaf("y")), leaf("b")))
        val crossing = onSlice(state.active, 0, 130f)

        val update = move(state, crossing)

        assertEquals(RadialEffect.DESCENDED, update.effect)
        assertEquals(2, update.state.rings.size)
        assertEquals(crossing, update.state.active.center)
        assertEquals(listOf("x", "y"), update.state.active.entries.map { it.id })
    }

    /** The new ring is born under the finger, so its own centre must not read as "backed out". */
    @Test fun `the sample right after a descend does not pop the ring it just opened`() {
        val state = open(submenu("root", submenu("tools", leaf("x")), leaf("b")))
        val crossing = onSlice(state.active, 0, 130f)
        val descended = move(state, crossing).state

        val settled = move(descended, crossing + Offset(2f, 0f))

        assertEquals(RadialEffect.NONE, settled.effect)
        assertEquals(2, settled.state.rings.size)
    }

    @Test fun `returning to the centre after leaving it pops one ring`() {
        val state = open(submenu("root", submenu("tools", leaf("x"), leaf("y")), leaf("b")))
        val crossing = onSlice(state.active, 0, 130f)
        val descended = move(state, crossing).state
        val armed = move(descended, onSlice(descended.active, 0, 70f)).state

        val popped = move(armed, crossing)

        assertEquals(RadialEffect.POPPED, popped.effect)
        assertEquals(1, popped.state.rings.size)
        assertEquals(listOf("tools", "b"), popped.state.active.entries.map { it.id })
    }

    @Test fun `nesting stops at the authored ring limit`() {
        val deep = submenu("root", submenu("l2", submenu("l3", submenu("l4", leaf("x")))))
        var state = open(deep)
        repeat(MAX_GESTURE_RINGS) {
            val update = move(state, onSlice(state.active, 0, 130f))
            state = update.state
        }

        assertEquals(MAX_GESTURE_RINGS, state.rings.size)
        // The fourth descend is refused: the slice stays selected instead of opening a ring.
        assertEquals("l4", state.focusedEntry?.id)
    }

    // --- overflow ---

    @Test fun `entries that do not fit spill into a trailing More slice carrying the rest`() {
        // A corner ring: the sweep is clamped, so a wide menu cannot be shown in one pass.
        val corner = Offset(12f, 40f)
        val entries = (1..8).map { leaf("e$it") }
        val state = open(submenu("root", *entries.toTypedArray()), center = corner)
        val capacity = ringCapacity(availableSweepDegrees(corner, screen, metrics))

        assertTrue("the corner should not fit all eight", capacity < entries.size)
        assertEquals(capacity, state.active.entries.size)
        val last = state.active.entries.last()
        assertTrue(last.overflow)
        assertEquals(more, last.label)
        assertEquals(
            entries.drop(capacity - 1).map { it.id },
            last.children.map { it.id },
        )
    }

    @Test fun `an overflow ring continues the level instead of spending the nesting budget`() {
        val corner = Offset(12f, 40f)
        val entries = (1..8).map { leaf("e$it") }
        val state = open(submenu("root", *entries.toTypedArray()), center = corner)
        val overflowIndex = state.active.entries.lastIndex

        val descended = move(state, onSlice(state.active, overflowIndex, 130f)).state

        assertEquals(RadialEffect.DESCENDED, move(state, onSlice(state.active, overflowIndex, 130f)).effect)
        assertEquals(2, descended.rings.size)
        assertEquals(1, descended.depth)
        assertTrue(descended.active.overflow)
    }

    @Test fun `a ring that fits is left exactly as authored`() {
        val entries = (1..6).map { leaf("e$it") }
        val state = open(submenu("root", *entries.toTypedArray()))

        assertEquals(entries.map { it.id }, state.active.entries.map { it.id })
        assertTrue(state.active.entries.none { it.overflow })
    }
}
