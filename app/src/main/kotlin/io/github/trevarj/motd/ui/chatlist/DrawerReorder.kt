package io.github.trevarj.motd.ui.chatlist

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

private fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    toMutableList().apply { add(to, removeAt(from)) }
