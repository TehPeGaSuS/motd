package io.github.trevarj.motd.ui.chatlist

import kotlin.math.abs

/**
 * Manual drawer ordering. Pure list algebra over [DrawerRow]s — no Compose or Android types — so
 * every move, boundary, and no-op is unit-testable without a device.
 *
 * A drawer entry only ever moves among its own siblings: a top-level row (DIRECT or BOUNCER_ROOT)
 * moves among top-level rows and carries its bouncer children with it, and a BOUNCER_CHILD moves
 * among the children of its own root. A bouncer child has no meaning outside its root, so promoting
 * or adopting one is not a move the drawer can express.
 */

/** One top-level drawer entry plus its bouncer children: the unit that moves as a whole. */
data class DrawerGroup(val root: DrawerRow, val children: List<DrawerRow> = emptyList()) {
    val rows: List<DrawerRow> get() = listOf(root) + children
}

/** Split the flattened drawer list back into top-level groups. */
fun drawerGroups(rows: List<DrawerRow>): List<DrawerGroup> {
    val groups = ArrayList<DrawerGroup>()
    for (row in rows) {
        val open = groups.lastOrNull()
        // A depth > 0 row with no open group cannot happen from buildDrawerRows; treat it as its
        // own group rather than dropping a network the user can see.
        if (row.depth == 0 || open == null) {
            groups += DrawerGroup(row)
        } else {
            groups[groups.lastIndex] = open.copy(children = open.children + row)
        }
    }
    return groups
}

fun flattenDrawerGroups(groups: List<DrawerGroup>): List<DrawerRow> = groups.flatMap(DrawerGroup::rows)

/** The persisted order key: network ids in display order, roots immediately before their children. */
fun drawerOrderIds(rows: List<DrawerRow>): List<Long> = rows.map(DrawerRow::networkId)

/** True when [networkId] has a sibling [delta] positions away, i.e. the move is not a no-op. */
fun canMoveDrawerRow(rows: List<DrawerRow>, networkId: Long, delta: Int): Boolean {
    if (delta == 0) return false
    val groups = drawerGroups(rows)
    val groupIndex = groups.indexOfFirst { it.root.networkId == networkId }
    if (groupIndex >= 0) return groupIndex + delta in groups.indices
    val owner = groups.firstOrNull { group -> group.children.any { it.networkId == networkId } }
        ?: return false
    val childIndex = owner.children.indexOfFirst { it.networkId == networkId }
    return childIndex + delta in owner.children.indices
}

/**
 * Move [networkId] by [delta] positions within its sibling list. Returns [rows] unchanged for an
 * unknown network, a zero delta, or a target past either end — a move that cannot happen leaves the
 * order alone instead of clamping to the boundary, so a repeated "move up" at the top does nothing
 * rather than silently swallowing the intent somewhere else.
 */
fun moveDrawerRow(rows: List<DrawerRow>, networkId: Long, delta: Int): List<DrawerRow> {
    if (delta == 0) return rows
    val groups = drawerGroups(rows)
    val groupIndex = groups.indexOfFirst { it.root.networkId == networkId }
    if (groupIndex >= 0) {
        val target = groupIndex + delta
        if (target !in groups.indices) return rows
        return flattenDrawerGroups(groups.moved(groupIndex, target))
    }
    val ownerIndex = groups.indexOfFirst { group -> group.children.any { it.networkId == networkId } }
    if (ownerIndex < 0) return rows
    val owner = groups[ownerIndex]
    val childIndex = owner.children.indexOfFirst { it.networkId == networkId }
    val target = childIndex + delta
    if (target !in owner.children.indices) return rows
    val reordered = groups.toMutableList()
    reordered[ownerIndex] = owner.copy(children = owner.children.moved(childIndex, target))
    return flattenDrawerGroups(reordered)
}

/**
 * Re-apply a pending order to freshly derived rows. Live unread/connection updates keep flowing
 * while a drag is in progress, and Room may not have caught up with the last committed move yet;
 * both cases rebuild [rows] in stored order, so the pending order is layered back on top.
 *
 * Ordering happens per sibling list rather than over the flat list, so the result is always a valid
 * tree: a network that appeared after [orderIds] was captured sorts last among its own siblings
 * instead of being stranded away from its root.
 */
fun applyDrawerOrder(rows: List<DrawerRow>, orderIds: List<Long>?): List<DrawerRow> {
    if (orderIds.isNullOrEmpty()) return rows
    val rank = orderIds.withIndex().associate { (index, id) -> id to index }
    fun rankOf(row: DrawerRow) = rank[row.networkId] ?: Int.MAX_VALUE
    val groups = drawerGroups(rows)
        .map { group -> group.copy(children = group.children.sortedBy(::rankOf)) }
        .sortedBy { rankOf(it.root) }
    return flattenDrawerGroups(groups)
}

/** Ids that translate together while [networkId] is dragged: a bouncer root carries its children. */
fun drawerDragUnit(rows: List<DrawerRow>, networkId: Long): Set<Long> {
    val group = drawerGroups(rows).firstOrNull { it.root.networkId == networkId }
        ?: return setOf(networkId)
    return group.rows.mapTo(LinkedHashSet(), DrawerRow::networkId)
}

/**
 * How far, in pixels, [networkId] travels in the layout when it swaps with its sibling in the
 * [delta] direction: the neighbour's full extent, since a bouncer root swaps with a whole group.
 * Null when there is no sibling that way; 0 when the neighbour has not been measured yet, which the
 * drag loop treats as "not yet swappable" rather than a free swap.
 */
fun drawerMoveShift(
    rows: List<DrawerRow>,
    heights: Map<Long, Int>,
    networkId: Long,
    delta: Int,
): Int? {
    if (delta == 0) return null
    val groups = drawerGroups(rows)
    val groupIndex = groups.indexOfFirst { it.root.networkId == networkId }
    if (groupIndex >= 0) {
        val neighbour = groups.getOrNull(groupIndex + delta) ?: return null
        return neighbour.rows.sumOf { heights[it.networkId] ?: 0 }
    }
    val owner = groups.firstOrNull { group -> group.children.any { it.networkId == networkId } }
        ?: return null
    val childIndex = owner.children.indexOfFirst { it.networkId == networkId }
    val neighbour = owner.children.getOrNull(childIndex + delta) ?: return null
    return heights[neighbour.networkId] ?: 0
}

/**
 * Arrangement a live drag is showing: the reordered rows plus the signed extent, in pixels, of the
 * neighbours the dragged unit has swapped past, so its on-screen translation is
 * `dragTotal - passedExtent`.
 */
data class DrawerDragPlacement(val rows: List<DrawerRow>, val passedExtent: Int)

/**
 * Where a drag that has travelled [dragTotal] pixels from [startRows] puts [networkId]: walk
 * sibling by sibling in the drag's direction, swapping only while the travel is past the midpoint
 * of the next neighbour's extent.
 *
 * Deliberately a pure recompute from the drag's start arrangement rather than an incremental step
 * per pointer event: an idempotent function of (start rows, heights, total travel) cannot loop,
 * cannot drift when a frame is dropped, and cannot disagree with what the gesture already showed.
 * An unmeasured neighbour (extent 0) stops the walk — "not yet swappable", never a free swap.
 */
fun drawerDragPlacement(
    startRows: List<DrawerRow>,
    heights: Map<Long, Int>,
    networkId: Long,
    dragTotal: Float,
): DrawerDragPlacement {
    val direction = if (dragTotal >= 0f) 1 else -1
    var rows = startRows
    var passed = 0
    while (true) {
        val extent = drawerMoveShift(rows, heights, networkId, direction)?.takeIf { it > 0 } ?: break
        if (abs(dragTotal) <= passed + extent / 2f) break
        val moved = moveDrawerRow(rows, networkId, direction)
        if (moved == rows) break // no sibling that way after all; never spin on a refused move
        rows = moved
        passed += extent
    }
    return DrawerDragPlacement(rows, direction * passed)
}

/**
 * True when [storedRows] already display in the [pending] arrangement, so the pending overlay can
 * be dropped. Deliberately "already display in", not "identical id lists": Room can publish rows
 * that differ from what the pending order predicted — a network deleted or added between the write
 * and its invalidation — and an overlay that no longer changes anything must still settle, or the
 * drawer stays pinned to a stale arrangement forever.
 */
fun drawerOrderSettled(storedRows: List<DrawerRow>, pending: List<Long>?): Boolean =
    applyDrawerOrder(storedRows, pending) == storedRows

private fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    toMutableList().apply { add(to, removeAt(from)) }
