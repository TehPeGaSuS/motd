package io.github.trevarj.motd.data.history

import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.irc.client.ChatHistoryReference

/**
 * The single answer to "can this direction page again, and from where?".
 *
 * Paging3 treats `endOfPaginationReached` as PERMANENT for a direction, so [End] must mean "this
 * direction is genuinely finished" and never "I cannot safely page from THIS cursor". The latter is
 * a per-fetch condition (an ambiguous equal-timestamp boundary at a saturated page edge, which on a
 * timestamp-only wire is every full page) and is deliberately not expressible here.
 */
sealed interface Pageability {

    /** Page again, issuing the request from [boundary], attributed to [focusedGapId] if any. */
    data class Page(val boundary: ChatHistoryReference, val focusedGapId: Long?) : Pageability

    /**
     * There is no local boundary to page from, so pull the newest page instead. Reached on a fresh
     * or cleared store, where a directional request has nothing to anchor to.
     */
    data object SeedLatest : Pageability

    /**
     * The direction is finished. [reason] is a fixed classification emitted as the `end_reason`
     * diagnostic field; the strings are matched by field tooling, so treat them as a wire contract
     * and do not reword them.
     */
    data class End(val reason: String) : Pageability
}

/**
 * What the page that just completed actually achieved: the boundary it was requested from and the
 * durable rows the persist added. Null when asking before a fetch rather than after one.
 */
data class PageProgress(val previous: ChatHistoryReference?, val insertedCount: Int)

/**
 * The anti-livelock stop: nothing landed and the next request would be byte-identical.
 *
 * Named because two very different callers read it. For Paging it is terminal — there is nothing
 * else the direction can do. For a demand-driven gap fill it is not a statement about the interval
 * at all, only about this attempt, so the caller that armed the fill is entitled to tell the two
 * apart. Emitted verbatim as an `end_reason` diagnostic field; treat it as a wire contract.
 */
const val NO_APPEND_PROGRESS = "no_append_progress"

/**
 * Older-direction (APPEND / CHATHISTORY BEFORE) pageability.
 *
 * Called twice per load with the same rules: once before fetching, to pick the boundary, and once
 * after, with [progress], to decide terminality. The post-page call re-reads the focused gap and
 * the cursor so a boundary that actually receded can be told apart from one that did not.
 *
 * @param focusedGap the gap this fill is pinned to, from [newestPageableGap] or a tapped divider;
 *   null for the mediator's ungapped bottom-of-timeline ladder. Re-read after a page
 * @param historyComplete the buffer's server-proven start-of-history flag
 * @param cursorOldest the stored protocol cursor's oldest boundary, if one exists
 * @param oldestLocalRow the oldest retained row's boundary, if the store is non-empty
 * @param progress non-null only on the post-page call
 * @param gapFloor the oldest OLDER edge among the room's open gaps, for a caller that must not page
 *   into an interval a gap owns; null when the caller IS the gap-directed one (see [openGapFloor])
 */
fun olderPageability(
    focusedGap: HistoryGapEntity?,
    historyComplete: Boolean,
    cursorOldest: ChatHistoryReference?,
    oldestLocalRow: ChatHistoryReference?,
    progress: PageProgress?,
    gapFloor: ChatHistoryReference? = null,
): Pageability {
    // Same condition, two classifications: before a page the gap was already known unrecoverable,
    // after a page the fetch itself proved the remainder empty. Field tooling distinguishes a stall
    // that never issued a request from one that did, so the split is kept.
    if (focusedGap?.recoverable == false) {
        return Pageability.End(
            if (progress == null) "unrecoverable_focused_gap" else "exhausted_focused_gap",
        )
    }
    // A focused gap outranks the completion flag: history reaching its start says nothing about an
    // interior interval that is still recoverable.
    if (historyComplete && focusedGap == null) return Pageability.End("history_complete")

    // Boundary ladder. A gap-directed caller pages BEFORE the island its gap sits under, full stop.
    //
    // The ungapped caller — the mediator's bottom-of-timeline APPEND — instead takes the OLDEST
    // point it knows, and that is a minimum rather than a preference order for two separate reasons:
    //
    //  - `cursorOldest` is not a lower bound on the local rows. A LATEST page landing a NEWER island
    //    (reconnect catch-up) unions its own oldest into the cursor, so on a store that was empty
    //    before the disconnect the cursor ends up NEWER than rows the client already holds. Taking
    //    it in preference re-requests an interval that is already durable;
    //  - [gapFloor] is where the two demand sources are held apart. An open gap owns the interval
    //    strictly between its edges, so requesting BEFORE any point at or above a gap's OLDER edge
    //    reaches into that interval. Clamping to the oldest such edge puts every ungapped request
    //    strictly BELOW every open gap: BEFORE is strictly-older-than, so the two can no longer name
    //    the same rows, whichever of them runs first.
    //
    // With no open gap the floor is absent and this degenerates to "page below the oldest thing I
    // know", which is the ladder it has always been.
    val boundary = focusedGap?.let { ChatHistoryReference(it.newerMsgid, it.newerServerTime) }
        ?: olderOf(
            olderOf(cursorOldest?.takeIf { it.msgid != null || it.serverTime != null }, oldestLocalRow),
            gapFloor,
        )

    if (progress != null && progress.insertedCount == 0 && !boundary.advancedFrom(progress.previous)) {
        // Anti-livelock guard: nothing landed AND the next request would be identical, so reporting
        // "more" would have Paging hammer the wire forever.
        return Pageability.End(NO_APPEND_PROGRESS)
    }
    // A null boundary is not an end: an empty store simply has nothing to page BEFORE yet, and the
    // newest page seeds one. With SKIP_INITIAL_REFRESH the remote REFRESH never fires on first
    // open, so this is where a fresh buffer's backfill actually starts.
    return boundary?.let { Pageability.Page(it, focusedGap?.id) } ?: Pageability.SeedLatest
}

/**
 * The floor an ungapped older request must stay at or below so it cannot enter an interval a gap
 * owns: the OLDEST `older` edge among [gaps], or null when the room has none.
 *
 * Recoverability is deliberately not filtered on. An unrecoverable gap still covers an interval —
 * one the server has PROVEN empty — so re-requesting it is wasted wire traffic whose empty result
 * the anti-livelock rule would then read as "this direction is finished". Either way that interval
 * belongs to the gap and not to the bottom-of-timeline ladder.
 *
 * The edge is taken by server time alone. Both consumers of this value serialize it to a CHATHISTORY
 * BEFORE selector, which is strictly-older-than on timestamp, so equal-timestamp precision buys
 * nothing here; the exact edge identity still travels with the gap for the fill that owns it.
 */
fun openGapFloor(gaps: List<HistoryGapEntity>): ChatHistoryReference? = gaps
    .minByOrNull { it.olderServerTime }
    ?.let { ChatHistoryReference(it.olderMsgid, it.olderServerTime) }

/**
 * The older of two boundaries.
 *
 * A boundary with no server time cannot be ordered against one that has it — a bare msgid names an
 * event whose position only the server knows — so it yields rather than guessing. Every gap edge and
 * every retained row carries a server time; only a stored protocol cursor can lack one.
 */
internal fun olderOf(a: ChatHistoryReference?, b: ChatHistoryReference?): ChatHistoryReference? {
    if (a == null) return b
    if (b == null) return a
    val aTime = a.serverTime ?: return b
    val bTime = b.serverTime ?: return a
    return if (bTime < aTime) b else a
}

/**
 * Would paging from this boundary issue a DIFFERENT request than [previous]?
 *
 * The asymmetry is deliberate and is not a simple inequality:
 *  - gaining or changing a msgid at an unchanged timestamp IS an advance — the request switches to
 *    a msgid selector, or to a different one, and therefore covers a different interval;
 *  - merely LOSING a msgid at an unchanged timestamp is NOT. That transition happens when a
 *    timestamp-only wire (soju advertises `MSGREFTYPES=timestamp`) strips advertised msgid
 *    references from a persisted boundary. Both the old and the new boundary serialize to the same
 *    timestamp selector, so the next request would repeat the identical interval verbatim.
 *
 * A plain `this != previous` would read that strip as progress and livelock the direction.
 */
internal fun ChatHistoryReference?.advancedFrom(previous: ChatHistoryReference?): Boolean {
    if (this == null || previous == null) return this != previous
    return serverTime != previous.serverTime || (msgid != null && msgid != previous.msgid)
}
