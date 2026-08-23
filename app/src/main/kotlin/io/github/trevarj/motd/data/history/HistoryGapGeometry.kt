package io.github.trevarj.motd.data.history

import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.TimelineAnchor
import javax.inject.Inject

/**
 * One stored history-gap edge, resolved as far as the local store allows, plus the role-specific
 * projections that turn it into a comparable [TimelineAnchor].
 *
 * A gap edge names a message the client may or may not still hold. The resolution ladder
 * ([GapAnchorResolver.resolve]) either pins the edge to an exact timeline position ([Exact]) or
 * runs out of identity and is left with nothing but the edge's server timestamp ([TimeOnly]).
 *
 * The two cases are kept apart deliberately. Collapsing them at resolution time — picking one
 * sentinel and baking it into a [TimelineAnchor] — is what made the same unidentifiable edge mean
 * different things to different callers with no way to see it in the type. Here the ambiguity
 * survives resolution and each caller states, at the point of use, WHICH ROLE the edge plays. The
 * role determines the sentinel; the edge itself never picks one.
 */
sealed interface GapEdgeAnchor {
    /**
     * Where this edge sits when it marks the CUT between two retained rows: the position a seam is
     * drawn at, taken from a gap's newer edge. Named for the inclusive lower window boundary it used
     * to feed, because the projection is the same one and the seam lands exactly where the old clamp
     * did — see [newestPageableGap] for why nothing clamps any more.
     */
    fun asInclusiveLowerBound(): TimelineAnchor

    /** Where this edge sits when a gap's NEWER edge is ranked for fill selection. */
    fun asFocusNewerPosition(): TimelineAnchor

    /**
     * The edge resolved to a concrete timeline position: a retained local row, or the exact
     * `(serverTime, eventId, timelineOrder)` tuple the gap recorded when it was written. No
     * ambiguity remains, so every role sees the same anchor.
     */
    data class Exact(
        val anchor: TimelineAnchor,
    ) : GapEdgeAnchor {
        override fun asInclusiveLowerBound(): TimelineAnchor = anchor

        override fun asFocusNewerPosition(): TimelineAnchor = anchor
    }

    /**
     * The client cannot identify this edge's event AT ALL: no resolvable msgid and no eventId. Only
     * the server timestamp survives.
     *
     * [serverTime] is load-bearing and must not be dropped in favor of a pure sentinel.
     * [TimelineAnchor] compares serverTime FIRST, then timelineOrder, then eventId, so keeping the
     * real timestamp confines the ambiguity to the edge's equal-time cohort: against rows at other
     * timestamps the anchor still orders truthfully, and only inside the cohort does the sentinel
     * decide. A pure `TimelineAnchor(Long.MAX_VALUE, ...)` would instead dominate (or be dominated
     * by) the entire timeline and change behavior everywhere.
     */
    data class TimeOnly(
        val serverTime: Long,
    ) : GapEdgeAnchor {
        // The cut is INCLUSIVE at the anchor: rows at or newer than it sit above the seam. An
        // unknown edge cannot say where inside its equal-time cohort the gap falls, so it claims as
        // little as possible and sits BELOW the whole cohort, leaving every equal-time row on the
        // visible side. (Guessing the other way is the bug fixed in e91698a0: back when this
        // projection was a SQL lower boundary, a MAX-sentinel newer edge excluded every row sharing
        // its timestamp and could empty the presented window outright, while the rows sat durable
        // and unreachable in Room.)
        override fun asInclusiveLowerBound(): TimelineAnchor = cohortFloor()

        // Fill selection is the OPPOSITE problem and therefore takes the opposite sentinel. Here the
        // anchor does not place a cut; it ranks gaps so the autopilot can pick one to page. An
        // unidentifiable edge is precisely the edge most likely to still be hiding history, so it
        // must WIN its cohort's ranking rather than yield it: [newestPageableGap] takes the maximum
        // newer edge, so an unknown newer edge sits at the cohort ceiling and the unlocatable gap
        // becomes the selected one.
        //
        // Do NOT "harmonize" these two. The cut wants the unknown edge to claim nothing; selection
        // wants it to win. Same edge, same timestamp, opposite correct answers — that is why the
        // roles are named rather than implied.
        override fun asFocusNewerPosition(): TimelineAnchor = cohortCeiling()

        /** Below every real row sharing [serverTime], and above every row at an earlier one. */
        private fun cohortFloor() = TimelineAnchor(serverTime, Long.MIN_VALUE, Long.MIN_VALUE)

        /** Above every real row sharing [serverTime], and below every row at a later one. */
        private fun cohortCeiling() = TimelineAnchor(serverTime, Long.MAX_VALUE, Long.MAX_VALUE)
    }
}

/** A stored gap with both edges resolved through [GapAnchorResolver]. */
data class ResolvedGap(
    val gap: HistoryGapEntity,
    val older: GapEdgeAnchor,
    val newer: GapEdgeAnchor,
)

/**
 * Turns stored gap edges into [GapEdgeAnchor]s using the local message store.
 *
 * The ladder, in order, is: the row carrying the edge's msgid, then the retained row with the
 * edge's canonical eventId, then the `(serverTime, eventId, timelineOrder)` tuple the gap recorded,
 * and finally the timestamp alone. Only the last rung is ambiguous; the first three all produce
 * [GapEdgeAnchor.Exact] and are role-independent.
 */
class GapAnchorResolver
    @Inject
    constructor(
        private val messageDao: MessageDao,
    ) {
        suspend fun resolve(
            roomId: Long,
            gaps: List<HistoryGapEntity>,
        ): List<ResolvedGap> =
            gaps.map { gap ->
                ResolvedGap(
                    gap = gap,
                    older =
                        resolveEdge(
                            roomId,
                            gap.olderMsgid,
                            gap.olderServerTime,
                            gap.olderEventId,
                            gap.olderTimelineOrder,
                        ),
                    newer =
                        resolveEdge(
                            roomId,
                            gap.newerMsgid,
                            gap.newerServerTime,
                            gap.newerEventId,
                            gap.newerTimelineOrder,
                        ),
                )
            }

        private suspend fun resolveEdge(
            roomId: Long,
            msgid: String?,
            serverTime: Long,
            eventId: Long?,
            timelineOrder: Long?,
        ): GapEdgeAnchor =
            msgid
                ?.let { messageDao.byMsgid(roomId, it) }
                ?.let { GapEdgeAnchor.Exact(TimelineAnchor(it.serverTime, it.id, it.timelineOrder)) }
                ?: eventId?.let { id ->
                    messageDao
                        .byCanonicalId(id)
                        ?.takeIf { it.bufferId == roomId }
                        ?.let { GapEdgeAnchor.Exact(TimelineAnchor(it.serverTime, it.id, it.timelineOrder)) }
                }
                // The gap's own recorded tuple. The row may be gone, but the position it occupied is exact,
                // so this is still an unambiguous anchor rather than a fallback.
                ?: eventId?.let {
                    GapEdgeAnchor.Exact(TimelineAnchor(serverTime, it, timelineOrder ?: it))
                }
                // Reached only when the edge carries no resolvable msgid and no eventId at all.
                ?: GapEdgeAnchor.TimeOnly(serverTime)
    }

/**
 * The gap a hands-free (autopilot) fill should work on, or null when the room has none.
 *
 * Ranked by each gap's NEWER edge — the edge an older page is requested BEFORE — taking the newest
 * one, i.e. the reconnect catch-up gap whenever there is one.
 *
 * ## Why no window is derived from gaps at all
 *
 * The timeline used to clamp at a gap edge, so every row on the far side sat durable and intact in
 * Room while being unreachable on screen: Recent clamped at the newest gap's newer edge, and a
 * deep-link island clamped on BOTH sides. Both presentations are retired. There is one unbounded
 * list, and a gap is drawn as a tappable seam between the two rows it falls between (see
 * [TimelineSeam]). The seam takes the SAME projection the old lower boundary took
 * ([GapEdgeAnchor.asInclusiveLowerBound]), so the cut lands where the clamp used to — it just draws
 * a line instead of hiding everything past it.
 *
 * Reintroducing any window boundary here re-hides the far side of every gap and stops the seams
 * rendering, because the row at a seam then has no materialized older neighbour and [seamAbove]
 * abstains. It also breaks the mark-read gate downstream, which now rests entirely on "index 0 of
 * the presented list IS the room's newest row".
 *
 * This is also NOT the mediator's selector. The mediator's APPEND is the bottom-of-timeline ladder:
 * with nothing clamped, its local source only runs dry past the oldest retained row, never at an
 * interior seam. Interior seams belong to `HistoryGapFillCoordinator`, driven by a divider tap or by
 * the autopilot that calls this.
 */
fun newestPageableGap(gaps: List<ResolvedGap>): ResolvedGap? =
    gaps
        .maxByOrNull { it.newer.asFocusNewerPosition() }
