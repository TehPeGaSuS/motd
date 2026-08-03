package io.github.trevarj.motd.data.history

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.TimelineAnchor

/**
 * One visible break in the timeline: the divider row drawn where a stored history gap interrupts the
 * message stream ("Missing history — tap to load").
 *
 * A seam is presentation only. It hides nothing: the timeline stays one unbounded list and the seam
 * is drawn between the two rows the gap falls between. That is the whole point of the model — the
 * older "hidden window" presentation clamped the SQL window at a gap, so every row on the far side
 * became unreachable in the UI while sitting durable and intact in Room.
 *
 * [recoverable] mirrors the stored gap's flag and is CARRIED, not filtered on. An unrecoverable gap
 * still gets a seam; it renders differently later (nothing left to fetch, so no tap affordance) but
 * it must exist, because dropping it is exactly what hid the user's own stored history behind a gap
 * that could never be closed.
 */
data class TimelineSeam(
    val gapId: Long,
    val position: TimelineAnchor,
    val recoverable: Boolean,
)

/**
 * The seams contributed by [gaps], ordered oldest-first by [TimelineSeam.position].
 *
 * ## Why the seam takes the WINDOW lower-bound projection
 *
 * A seam's position is `newer.asInclusiveLowerBound()` — the same role [windowBounds] feeds into
 * the presented window's inclusive lower boundary — and deliberately NOT
 * [GapEdgeAnchor.asFocusNewerPosition].
 *
 * Both roles ask the identical question of the identical edge: *which rows sit at or after this
 * gap?* The window answers by clamping and the seam answers by drawing a line, but the cut lands in
 * the same place, so both must take the same sentinel. For a [GapEdgeAnchor.TimeOnly] newer edge
 * that is the cohort FLOOR: the seam sits below every row sharing the edge's timestamp, so it is
 * rendered under the whole equal-timestamp cohort rather than slicing through it at an arbitrary
 * point the client cannot actually justify.
 *
 * The focus projections answer the OPPOSITE way on purpose, because gap *selection* is a different
 * problem (an unlocatable gap must win its cohort's ranking so paging works on it). Taking
 * `asFocusNewerPosition` here would put an unidentifiable seam at the cohort CEILING, which is wrong
 * twice over: it would split the cohort, and when that cohort is the newest thing in the buffer the
 * ceiling sits above every materialized row, matches no interval at all, and the seam vanishes —
 * the user sees a silently truncated timeline with no indication anything is missing. That is the
 * shape of the bug fixed in `e91698a0`, so do not "harmonize" the two conventions;
 * `HistoryGapGeometryTest` fails loudly if anyone tries.
 */
fun timelineSeams(gaps: List<ResolvedGap>): List<TimelineSeam> = gaps
    .map { TimelineSeam(it.gap.id, it.newer.asInclusiveLowerBound(), it.gap.recoverable) }
    // gapId only breaks ties between two gaps resolving to the same position, so the order is
    // total and stable regardless of how the caller happened to read the rows.
    .sortedWith(compareBy({ it.position }, { it.gapId }))

/**
 * The seam, if any, that belongs immediately above [row] in the reversed timeline.
 *
 * The list renders newest-first (`ORDER BY serverTime DESC, timelineOrder DESC, id DESC`), so
 * [olderNeighbor] is the row at the NEXT HIGHER index and the pair brackets one visual slot. A seam
 * fills that slot when its position falls in the half-open interval `(olderNeighbor, row]`.
 *
 * Half-open at the top on purpose: a seam sitting exactly ON [row]'s anchor means "this row is the
 * first one at or after the gap", so it is drawn above [row], not above the next row up. Closing
 * both ends would draw it twice; opening both would drop it whenever it coincides with a real row.
 *
 * A null [olderNeighbor] is NOT "start of history" — it is an unmaterialized Paging placeholder, so
 * the interval's lower end is unknown and the question is genuinely undecidable. Answering it would
 * mean guessing, and the guess is visible: a seam pinned to the bottom of a page that jumps
 * elsewhere the moment the placeholder loads. Abstain instead and render nothing; the seam appears
 * on its own once that row materializes. (Note this differs from the unread-marker and day-separator
 * rules in `MessageList`, which treat a null neighbor as a real edge — those are derived from the
 * row itself and stay correct under any neighbor, while a seam's placement depends on the neighbor.)
 */
fun seamAbove(
    row: MessageEntity,
    olderNeighbor: MessageEntity?,
    seams: List<TimelineSeam>,
): TimelineSeam? {
    val lowerExclusive = olderNeighbor?.timelineAnchor() ?: return null
    val upperInclusive = row.timelineAnchor()
    return seams
        .filter { it.position > lowerExclusive && it.position <= upperInclusive }
        // Two gaps can land in one slot (adjacent rows with more than one gap between them). They
        // describe the same visual break, so pick deterministically rather than by list order.
        .minWithOrNull(compareBy({ it.position }, { it.gapId }))
}

private fun MessageEntity.timelineAnchor() = TimelineAnchor(serverTime, id, timelineOrder)
