package io.github.trevarj.motd.data.history

import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.repo.HistoryWindowFocus
import io.github.trevarj.motd.data.visibility.MessageWindowBounds

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
     * Where this edge sits when used as an inclusive LOWER window boundary (the newer edge of a
     * gap, feeding [MessageWindowBounds.lowerBoundary]).
     */
    fun asInclusiveLowerBound(): TimelineAnchor

    /**
     * Where this edge sits when used as an inclusive UPPER window boundary (the older edge of a
     * gap, feeding [MessageWindowBounds.upperBoundary]).
     */
    fun asInclusiveUpperBound(): TimelineAnchor

    /** Where this edge sits when a gap's NEWER edge is ranked for focus selection. */
    fun asFocusNewerPosition(): TimelineAnchor

    /** Where this edge sits when a gap's OLDER edge is ranked for focus selection. */
    fun asFocusOlderPosition(): TimelineAnchor

    /**
     * The edge resolved to a concrete timeline position: a retained local row, or the exact
     * `(serverTime, eventId, timelineOrder)` tuple the gap recorded when it was written. No
     * ambiguity remains, so every role sees the same anchor.
     */
    data class Exact(val anchor: TimelineAnchor) : GapEdgeAnchor {
        override fun asInclusiveLowerBound(): TimelineAnchor = anchor
        override fun asInclusiveUpperBound(): TimelineAnchor = anchor
        override fun asFocusNewerPosition(): TimelineAnchor = anchor
        override fun asFocusOlderPosition(): TimelineAnchor = anchor
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
    data class TimeOnly(val serverTime: Long) : GapEdgeAnchor {
        // A window bound is INCLUSIVE at the anchor and its job is to hide rows on the far side of a
        // gap. An unknown edge cannot say where the gap is, so it must clamp as little as possible:
        // bounding the window at a guessed position is not more truthful than not bounding it, only
        // more destructive. As a lower bound that means sitting BELOW its whole cohort, as an upper
        // bound ABOVE it — either way every equal-time row stays visible. (Guessing wrong here is
        // the bug fixed in e91698a0: a MAX-sentinel newer edge used as the Recent lowerBoundary
        // excluded every row sharing its timestamp and could empty the presented window outright,
        // while the rows sat durable and unreachable in Room.)
        override fun asInclusiveLowerBound(): TimelineAnchor = cohortFloor()
        override fun asInclusiveUpperBound(): TimelineAnchor = cohortCeiling()

        // Focus selection is the OPPOSITE problem and therefore takes the opposite sentinel. Here
        // the anchors do not hide rows; they rank gaps so the mediator can pick one to page. An
        // unidentifiable edge is precisely the edge most likely to still be hiding history, so it
        // must WIN its cohort's ranking rather than yield it: `focusedOlderGap` takes the maximum
        // newer edge, so an unknown newer edge sits at the cohort ceiling; `focusedNewerGap` takes
        // the minimum older edge at or after the focus anchor, so an unknown older edge sits at the
        // cohort floor. Both make the unlocatable gap the selected one.
        //
        // Do NOT "harmonize" these with the window projections above. The window wants the unknown
        // edge not to clamp; focus selection wants the unknown edge to win. Same edge, same
        // timestamp, opposite correct answers — that is why the roles are named rather than implied.
        override fun asFocusNewerPosition(): TimelineAnchor = cohortCeiling()
        override fun asFocusOlderPosition(): TimelineAnchor = cohortFloor()

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
class GapAnchorResolver(private val messageDao: MessageDao) {

    suspend fun resolve(roomId: Long, gaps: List<HistoryGapEntity>): List<ResolvedGap> =
        gaps.map { gap ->
            ResolvedGap(
                gap = gap,
                older = resolveEdge(
                    roomId,
                    gap.olderMsgid,
                    gap.olderServerTime,
                    gap.olderEventId,
                    gap.olderTimelineOrder,
                ),
                newer = resolveEdge(
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
    ): GapEdgeAnchor = msgid?.let { messageDao.byMsgid(roomId, it) }
        ?.let { GapEdgeAnchor.Exact(TimelineAnchor(it.serverTime, it.id, it.timelineOrder)) }
        ?: eventId?.let { id ->
            messageDao.byCanonicalId(id)?.takeIf { it.bufferId == roomId }
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
 * The presented window for [focus]: the newest contiguous local island, clamped by the gaps that
 * bracket it.
 *
 * `newer` edges only ever become lower bounds and `older` edges only ever become upper bounds, so
 * each side is projected through the bound role it feeds. Both bounds are inclusive at the anchor.
 */
fun windowBounds(focus: HistoryWindowFocus, gaps: List<ResolvedGap>): MessageWindowBounds {
    val lowerCandidates = gaps.map { it.newer.asInclusiveLowerBound() }
    return when (focus) {
        HistoryWindowFocus.Recent -> MessageWindowBounds(
            // Recent paints everything at or after the newest gap; older islands stay hidden until
            // the gap between them closes.
            lowerBoundary = lowerCandidates.maxOrNull(),
        )
        is HistoryWindowFocus.Around -> MessageWindowBounds(
            lowerBoundary = lowerCandidates.filter { it <= focus.anchor }.maxOrNull(),
            upperBoundary = gaps.map { it.older.asInclusiveUpperBound() }
                .filter { it >= focus.anchor }
                .minOrNull(),
        )
    }
}

/**
 * The gap that older (APPEND) paging should work on under [focus], or null when none applies.
 *
 * Ranked by each gap's NEWER edge — the edge an older page is requested BEFORE. Recent takes the
 * newest such edge; a focused window takes the newest edge at or before its anchor, so paging
 * grows the focused island downward instead of jumping to an unrelated one.
 */
fun focusedOlderGap(focus: HistoryWindowFocus, gaps: List<ResolvedGap>): ResolvedGap? {
    val ranked = gaps.map { it to it.newer.asFocusNewerPosition() }
    return when (focus) {
        HistoryWindowFocus.Recent -> ranked.maxByOrNull { it.second }?.first
        is HistoryWindowFocus.Around -> ranked
            .filter { it.second <= focus.anchor }
            .maxByOrNull { it.second }
            ?.first
    }
}

/**
 * The gap that newer (PREPEND) paging should work on under [focus], or null when none applies.
 *
 * Only a focused window pages toward recent; under Recent focus live events already supply newer
 * messages, so there is nothing to select. Ranked by each gap's OLDER edge — the edge a newer page
 * is requested AFTER — taking the nearest one at or after the anchor.
 */
fun focusedNewerGap(focus: HistoryWindowFocus, gaps: List<ResolvedGap>): ResolvedGap? {
    if (focus !is HistoryWindowFocus.Around) return null
    return gaps.map { it to it.older.asFocusOlderPosition() }
        .filter { it.second >= focus.anchor }
        .minByOrNull { it.second }
        ?.first
}
