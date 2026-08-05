package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Move rules for the drawer's manual network order (DrawerReorder.kt). */
class DrawerReorderTest {

    private fun direct(id: Long, name: String = "net$id") = DrawerRow(
        networkId = id, name = name, role = NetworkRole.DIRECT, depth = 0,
        state = IrcClientState.Disconnected, nick = null, unread = 0, mentions = 0,
    )

    private fun root(id: Long, name: String = "soju$id") = DrawerRow(
        networkId = id, name = name, role = NetworkRole.BOUNCER_ROOT, depth = 0,
        state = IrcClientState.Disconnected, nick = null, unread = 0, mentions = 0,
    )

    private fun child(id: Long, name: String = "child$id") = DrawerRow(
        networkId = id, name = name, role = NetworkRole.BOUNCER_CHILD, depth = 1,
        state = IrcClientState.Disconnected, nick = null, unread = 0, mentions = 0,
    )

    /** libera, soju(oftc, ergo), hackint. */
    private val tree = listOf(direct(1), root(2), child(3), child(4), direct(5))

    private fun ids(rows: List<DrawerRow>) = drawerOrderIds(rows)

    @Test
    fun `move up swaps a top-level entry with the one above it`() {
        // One step past the whole soju group, not past its first row.
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), ids(moveDrawerRow(tree, networkId = 5, delta = -1)))
    }

    @Test
    fun `move down swaps a top-level entry with the one below it`() {
        assertEquals(listOf(2L, 3L, 4L, 1L, 5L), ids(moveDrawerRow(tree, networkId = 1, delta = 1)))
    }

    @Test
    fun `a bouncer root carries its children`() {
        // The root passes the whole of "libera"; its children stay attached and stay in order.
        assertEquals(listOf(2L, 3L, 4L, 1L, 5L), ids(moveDrawerRow(tree, networkId = 2, delta = -1)))
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), ids(moveDrawerRow(tree, networkId = 2, delta = 1)))
    }

    @Test
    fun `a bouncer child only moves among its own siblings`() {
        assertEquals(listOf(1L, 2L, 4L, 3L, 5L), ids(moveDrawerRow(tree, networkId = 3, delta = 1)))
        // The first child cannot be promoted out of its root, and the last cannot escape past it.
        assertSame(tree, moveDrawerRow(tree, networkId = 3, delta = -1))
        assertSame(tree, moveDrawerRow(tree, networkId = 4, delta = 1))
    }

    @Test
    fun `moving past either end is a no-op rather than a clamp`() {
        assertSame(tree, moveDrawerRow(tree, networkId = 1, delta = -1))
        assertSame(tree, moveDrawerRow(tree, networkId = 5, delta = 1))
        assertSame(tree, moveDrawerRow(tree, networkId = 1, delta = 0))
        assertSame(tree, moveDrawerRow(tree, networkId = 99, delta = 1))
        // Only entry in the drawer: nowhere to go in either direction.
        val alone = listOf(direct(1))
        assertSame(alone, moveDrawerRow(alone, networkId = 1, delta = -1))
        assertSame(alone, moveDrawerRow(alone, networkId = 1, delta = 1))
    }

    @Test
    fun `a multi-step move lands on the target position`() {
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), ids(moveDrawerRow(tree, networkId = 1, delta = 2)))
        // Two steps from the second position would fall off the end, so nothing moves.
        assertSame(tree, moveDrawerRow(tree, networkId = 5, delta = 2))
    }

    @Test
    fun `canMove agrees with what move actually does`() {
        for (id in listOf(1L, 2L, 3L, 4L, 5L, 99L)) {
            for (delta in listOf(-1, 1)) {
                assertEquals(
                    "network $id delta $delta",
                    canMoveDrawerRow(tree, id, delta),
                    moveDrawerRow(tree, id, delta) !== tree,
                )
            }
        }
        assertFalse(canMoveDrawerRow(tree, networkId = 1, delta = 0))
    }

    @Test
    fun `an order captured earlier is re-applied to freshly derived rows`() {
        val stored = listOf(direct(1), root(2), child(3), child(4), direct(5))
        val pending = listOf(5L, 2L, 4L, 3L, 1L)

        assertEquals(pending, ids(applyDrawerOrder(stored, pending)))
        assertSame(stored, applyDrawerOrder(stored, null))
        assertSame(stored, applyDrawerOrder(stored, emptyList()))
    }

    @Test
    fun `a network added after the order was captured stays with its siblings`() {
        val pending = listOf(5L, 2L, 4L, 3L, 1L)
        // 6 is a new child of the root and 7 a new top-level network; neither is in `pending`.
        val withNewcomers = listOf(direct(1), root(2), child(3), child(4), child(6), direct(5), direct(7))

        // Unranked entries sort last within their own sibling list, never out of the tree.
        assertEquals(
            listOf(5L, 2L, 4L, 3L, 6L, 1L, 7L),
            ids(applyDrawerOrder(withNewcomers, pending)),
        )
    }

    @Test
    fun `drag unit is the entry alone unless it is a bouncer root`() {
        assertEquals(setOf(1L), drawerDragUnit(tree, networkId = 1))
        assertEquals(setOf(3L), drawerDragUnit(tree, networkId = 3))
        assertEquals(setOf(2L, 3L, 4L), drawerDragUnit(tree, networkId = 2))
    }

    @Test
    fun `a swap moves the entry by the neighbour's whole extent`() {
        val heights = mapOf(1L to 60, 2L to 60, 3L to 50, 4L to 50, 5L to 70)

        // Top-level: past the entire soju group (root + both children), not just its first row.
        assertEquals(160, drawerMoveShift(tree, heights, networkId = 1, delta = 1))
        assertEquals(70, drawerMoveShift(tree, heights, networkId = 1, delta = 2))
        // Child: past its sibling only.
        assertEquals(50, drawerMoveShift(tree, heights, networkId = 3, delta = 1))
        // No sibling that way, or no direction at all.
        assertNull(drawerMoveShift(tree, heights, networkId = 1, delta = -1))
        assertNull(drawerMoveShift(tree, heights, networkId = 4, delta = 1))
        assertNull(drawerMoveShift(tree, heights, networkId = 1, delta = 0))
        // Not measured yet: 0, which the drag treats as "cannot swap" instead of a free swap.
        assertEquals(0, drawerMoveShift(tree, emptyMap(), networkId = 1, delta = 1))
    }

    // libera 60, soju 60(oftc 50, ergo 50) => group extent 160, hackint 70.
    private val heights = mapOf(1L to 60, 2L to 60, 3L to 50, 4L to 50, 5L to 70)

    @Test
    fun `drag placement swaps only past the neighbour's midpoint`() {
        // libera downward towards the soju group (extent 160): nothing until past 80.
        val before = drawerDragPlacement(tree, heights, networkId = 1, dragTotal = 80f)
        assertEquals(tree, before.rows)
        assertEquals(0, before.passedExtent)

        val after = drawerDragPlacement(tree, heights, networkId = 1, dragTotal = 81f)
        assertEquals(listOf(2L, 3L, 4L, 1L, 5L), ids(after.rows))
        assertEquals(160, after.passedExtent)

        // A child swaps past its sibling's own extent only (ergo up past oftc, midpoint 25).
        val child = drawerDragPlacement(tree, heights, networkId = 4, dragTotal = -26f)
        assertEquals(listOf(1L, 2L, 4L, 3L, 5L), ids(child.rows))
        assertEquals(-50, child.passedExtent)
    }

    @Test
    fun `drag placement crosses several siblings in one recompute`() {
        // libera down past soju (threshold 80) and then past hackint (threshold 160 + 35).
        val far = drawerDragPlacement(tree, heights, networkId = 1, dragTotal = 300f)
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), ids(far.rows))
        assertEquals(230, far.passedExtent)
    }

    @Test
    fun `drag placement is a pure function of the total travel`() {
        // Same travel twice gives the same answer; travelling back gives the start back. This is
        // what makes a lost frame or a repeated event harmless: no incremental state to corrupt.
        val once = drawerDragPlacement(tree, heights, networkId = 5, dragTotal = -100f)
        val again = drawerDragPlacement(tree, heights, networkId = 5, dragTotal = -100f)
        assertEquals(once, again)
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), ids(once.rows))

        val home = drawerDragPlacement(tree, heights, networkId = 5, dragTotal = 0f)
        assertEquals(tree, home.rows)
        assertEquals(0, home.passedExtent)
    }

    @Test
    fun `drag placement always terminates at a boundary or an unmeasured neighbour`() {
        // Absurd travel upward from the top: no sibling that way, nothing moves, no spin.
        val stuckTop = drawerDragPlacement(tree, heights, networkId = 1, dragTotal = -100_000f)
        assertEquals(tree, stuckTop.rows)
        assertEquals(0, stuckTop.passedExtent)

        // Absurd travel upward from the bottom stops at the top of the list.
        val toTop = drawerDragPlacement(tree, heights, networkId = 5, dragTotal = -100_000f)
        assertEquals(listOf(5L, 1L, 2L, 3L, 4L), ids(toTop.rows))
        assertEquals(-(160 + 60), toTop.passedExtent)

        // An unmeasured neighbour (extent 0) is "not yet swappable", never a free swap.
        val unmeasured = drawerDragPlacement(tree, emptyMap(), networkId = 1, dragTotal = 100_000f)
        assertEquals(tree, unmeasured.rows)
        assertEquals(0, unmeasured.passedExtent)
    }

    @Test
    fun `an order settles once the stored rows display it`() {
        assertTrue(drawerOrderSettled(tree, null))
        assertTrue(drawerOrderSettled(tree, listOf(1L, 2L, 3L, 4L, 5L)))
        assertFalse(drawerOrderSettled(tree, listOf(5L, 1L, 2L, 3L, 4L)))
        assertFalse(drawerOrderSettled(tree, listOf(1L, 2L, 4L, 3L, 5L)))
    }

    @Test
    fun `an order settles even when the rows differ from what it predicted`() {
        // The write listed a network that was deleted before Room published: the surviving rows
        // already display the requested relative order, so the overlay must drop.
        assertTrue(drawerOrderSettled(tree, listOf(9L, 1L, 2L, 3L, 4L, 5L)))
        // Rows the order never ranked (newcomers) already sort last: also settled.
        assertTrue(drawerOrderSettled(tree, listOf(1L, 2L, 3L, 4L)))
    }

    @Test
    fun `grouping and flattening round-trip the drawer list`() {
        val groups = drawerGroups(tree)
        assertEquals(3, groups.size)
        assertEquals(listOf(3L, 4L), groups[1].children.map(DrawerRow::networkId))
        assertTrue(groups[0].children.isEmpty())
        assertEquals(tree, flattenDrawerGroups(groups))
    }
}
