package io.github.trevarj.motd.ui.chat

/** A same-frame scroll correction, applied with `LazyListState.requestScrollToItem(index, offset)`. */
internal data class TimelinePin(
    val index: Int,
    val offset: Int,
)

/**
 * Where the viewport must be re-pinned after a Paging presentation, or `null` to leave it alone.
 *
 * ## The problem
 *
 * `LazyListState` re-anchors the viewport by KEY: when a new item provider arrives it looks up the
 * key that owned the first visible slot and moves `firstVisibleItemIndex` to wherever that key
 * went. The timeline keys rows by message id, so that works — right up until the row it is
 * following stops being a loaded row. A placeholder has no id, so Paging gives its slot a private
 * positional key; the anchor's key is then absent from the list entirely, the lookup fails, and the
 * viewport silently falls back to holding the raw numeric index. When the same presentation ALSO
 * moved every row — a reconnect catch-up inserts rows newer than the whole viewport — that retained
 * index points at different content, and the timeline jumps.
 *
 * ## The rule
 *
 * Restore the anchor's index by CONSERVATION, never by estimation. Rows are inserted and the loaded
 * window is re-placed, but retained rows never reorder relative to each other, so any row present
 * in both presentations measures the shift:
 *
 *     shift = current.indexOf(reference) - previous.indexOf(reference)
 *
 * The reference is the conserved row NEAREST the anchor and strictly NEWER than it (a lower
 * presentation index). Newer-side only, because that is what makes the correction safe: the changes
 * newer than the reference are a subset of the changes newer than the anchor, so on an
 * insert-dominated timeline — which this one is; rows are appended and backfilled, and only a
 * user's own message is ever deleted — the measured shift can only ever UNDERSTATE the anchor's own.
 * The pin therefore lands between where the viewport is now and where the anchor actually is: never
 * past it, never on the far side, and never further away than doing nothing. That is the sense in
 * which the worst case is today's behavior.
 *
 * ## Why the uncertain cases cannot hurt
 *
 * Every branch below fails CLOSED — returns null, which is exactly what happens today — and only a
 * branch with positive evidence acts:
 *
 *  - No anchor key, or an empty new presentation: nothing is conserved, so nothing is claimed.
 *  - The anchor is still a loaded row: `LazyListState` re-anchors it natively, at measure time, and
 *    acting here would only fight a mechanism that is already right.
 *  - No newer row survives into the new presentation: the shift is unmeasurable, so it is not
 *    guessed.
 *  - `shift == 0`: the index the list already holds is the right one.
 *  - The target falls outside the new presentation: a pin that cannot land is not issued.
 *  - The target falls inside the loaded window: this function only runs when the anchor is a
 *    PLACEHOLDER, so an index Paging has loaded cannot be the anchor's. That is proof the shift
 *    describes the reference and not the anchor, so it is discarded.
 *
 * The caller adds the situational guards this function cannot see — settled, not scrolling, not
 * following; see the call site in `ChatContent`.
 *
 * @param anchor the viewport as last MEASURED: index and offset from `LazyListState`, key read out
 *   of [previous], which is the presentation that layout was measured against.
 */
internal fun timelinePresentationPin(
    anchor: TimelineViewportAnchor,
    previous: TimelineWindow,
    current: TimelineWindow,
): TimelinePin? {
    val key = anchor.key ?: return null
    if (current.itemCount == 0) return null
    // Resolvable by key: Compose already does this, and does it at measure time.
    if (current.indexOf(key) >= 0) return null
    if (anchor.index !in 0 until previous.itemCount) return null

    val referenceIndex = nearestConservedNewerIndex(anchor.index, previous, current) ?: return null
    val referenceId = previous.idAt(referenceIndex) ?: return null
    val shift = current.indexOf(referenceId) - referenceIndex
    if (shift == 0) return null

    val target = anchor.index + shift
    if (target !in 0 until current.itemCount) return null
    // A placeholder anchor cannot occupy a loaded slot. The reference is newer than the anchor, so
    // the anchor is older than the whole loaded window or the shift is not the anchor's.
    if (target <= current.loadedLastIndex) return null
    return TimelinePin(target, anchor.offset)
}

/**
 * Presentation index, in [previous], of the newest-side conserved row nearest the anchor.
 *
 * Nearest because every row between the reference and the anchor is a row that could have been
 * inserted between them, and the shortest span is the least exposed to that. The scan runs from the
 * anchor outward and stops at the first hit, so it is O(distance) rather than O(window).
 */
private fun nearestConservedNewerIndex(
    anchorIndex: Int,
    previous: TimelineWindow,
    current: TimelineWindow,
): Int? {
    if (previous.loadedIds.isEmpty() || current.loadedIds.isEmpty()) return null
    val conserved = current.loadedIds.toHashSet()
    val start = minOf(anchorIndex - 1, previous.loadedLastIndex)
    for (index in start downTo previous.loadedFirstIndex) {
        val id = previous.idAt(index) ?: continue
        if (id in conserved) return index
    }
    return null
}
