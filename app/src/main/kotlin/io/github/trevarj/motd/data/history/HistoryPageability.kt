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
 * Older-direction (APPEND / CHATHISTORY BEFORE) pageability.
 *
 * Called twice per load with the same rules: once before fetching, to pick the boundary, and once
 * after, with [progress], to decide terminality. The post-page call re-reads the focused gap and
 * the cursor so a boundary that actually receded can be told apart from one that did not.
 *
 * @param focusedGap the gap older paging is working on, from [focusedOlderGap]; re-read after a page
 * @param historyComplete the buffer's server-proven start-of-history flag
 * @param cursorOldest the stored protocol cursor's oldest boundary, if one exists
 * @param oldestLocalRow the oldest retained row's boundary, if the store is non-empty
 * @param progress non-null only on the post-page call
 */
fun olderPageability(
    focusedGap: HistoryGapEntity?,
    historyComplete: Boolean,
    cursorOldest: ChatHistoryReference?,
    oldestLocalRow: ChatHistoryReference?,
    progress: PageProgress?,
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

    // Boundary ladder: the focused gap's newer edge (page BEFORE the island the gap sits under),
    // else the stored protocol cursor, else the oldest retained row.
    val boundary = focusedGap?.let { ChatHistoryReference(it.newerMsgid, it.newerServerTime) }
        ?: cursorOldest?.takeIf { it.msgid != null || it.serverTime != null }
        ?: oldestLocalRow

    if (progress != null && progress.insertedCount == 0 && !boundary.advancedFrom(progress.previous)) {
        // Anti-livelock guard: nothing landed AND the next request would be identical, so reporting
        // "more" would have Paging hammer the wire forever.
        return Pageability.End("no_append_progress")
    }
    // A null boundary is not an end: an empty store simply has nothing to page BEFORE yet, and the
    // newest page seeds one. With SKIP_INITIAL_REFRESH the remote REFRESH never fires on first
    // open, so this is where a fresh buffer's backfill actually starts.
    return boundary?.let { Pageability.Page(it, focusedGap?.id) } ?: Pageability.SeedLatest
}

/**
 * Newer-direction (PREPEND / CHATHISTORY AFTER) pageability.
 *
 * Newer paging exists only to close a focused unread/deep-link gap toward the recent window, so the
 * gap IS the direction: no gap means nothing to fetch. There is no seed path — without a gap edge
 * there is no interval to catch up on.
 *
 * @param focusedGap the gap newer paging is working on, from [focusedNewerGap]; re-read after a page
 * @param progress non-null only on the post-page call
 */
fun newerPageability(focusedGap: HistoryGapEntity?, progress: PageProgress?): Pageability {
    if (focusedGap == null) return Pageability.End("newer_gap_closed")
    if (!focusedGap.recoverable) {
        return Pageability.End(
            if (progress == null) "unrecoverable_focused_gap" else "exhausted_focused_gap",
        )
    }
    // The gap's older edge is what a newer page is requested AFTER; each persisted page pushes it
    // up until the gap closes.
    val boundary = ChatHistoryReference(focusedGap.olderMsgid, focusedGap.olderServerTime)
    if (progress != null && progress.insertedCount == 0 && !boundary.advancedFrom(progress.previous)) {
        // Anti-livelock guard, mirroring the older direction.
        return Pageability.End("no_prepend_progress")
    }
    return Pageability.Page(boundary, focusedGap.id)
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
