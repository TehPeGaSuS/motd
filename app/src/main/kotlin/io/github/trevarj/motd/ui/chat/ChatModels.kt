package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.visibility.JOIN_PART_QUIT_KINDS
import io.github.trevarj.motd.data.visibility.CONVERSATION_KINDS
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.history.TimelineSeam
import io.github.trevarj.motd.data.history.seamAbove
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.components.HistoryGapState
import io.github.trevarj.motd.ui.components.ReactionChip
import androidx.paging.LoadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

// --- timeline message filtering (plans/13 §2.4/§2.5) ---

/** JOIN/PART/QUIT kinds hidden when `showJoinPartQuit == false`. */
val JPQ_KINDS: Set<MessageKind> = JOIN_PART_QUIT_KINDS

/**
 * Behavioral filter spec derived from observed Settings and passed into each repository Pager.
 */
typealias MessageFilterSpec = MessageVisibilitySpec

/**
 * Every seam the room currently has, plus the gaps a fill is running for.
 *
 * The two travel together because the divider's state is a function of both: the seam supplies the
 * gap's identity and recoverability, the in-flight set supplies whether it is spinning right now.
 */
data class TimelineSeamState(
    val seams: List<TimelineSeam> = emptyList(),
    val filling: Set<Long> = emptySet(),
) {
    /**
     * How one seam's divider renders.
     *
     * Recoverability is checked FIRST. An unrecoverable gap has nothing left to fetch, so it can
     * never be in flight; ordering the other way would let a stale in-flight id paint a spinner on
     * a seam that will never move.
     */
    fun stateFor(seam: TimelineSeam): HistoryGapState = when {
        !seam.recoverable -> HistoryGapState.Unrecoverable
        seam.gapId in filling -> HistoryGapState.Loading
        else -> HistoryGapState.Recoverable
    }
}

/** One seam as a row renders it: which gap to fill on tap, and the state its divider draws. */
data class RowSeam(val gapId: Long, val state: HistoryGapState)

/** One hands-free fill the autopilot has decided to start. */
internal data class GapAutopilotArming(
    val roomId: Long,
    val gapId: Long,
    val position: TimelineAnchor,
)

/**
 * Decides when a seam gets filled without the user tapping it.
 *
 * The case this exists for is reconnect catch-up: the bouncer replays a newest page, a gap opens
 * between it and what the client already had, and the user should not have to notice a divider and
 * tap it to get their own missed conversation back. So the NEWEST recoverable seam — the reconnect
 * one — is filled hands-free.
 *
 * What it must NOT become is a background history crawler. Two rules keep it honest, and they are
 * both about NOT arming:
 *
 *  1. **One arming per seam, not per emission.** [HistoryGapFillCoordinator][
 *     io.github.trevarj.motd.data.sync.HistoryGapFillCoordinator] bounds a single fill to its page
 *     budget, so re-arming on every seam update would turn a bounded fill into an unbounded loop.
 *     A fill that spends its budget leaves the seam open with its newer edge RECEDED, i.e. moved
 *     older, so [armedThrough] rejects it: the seam stays visible and tappable and the rest of that
 *     gap is fetched only if the user asks.
 *  2. **Newer than anything already armed.** Closing the newest gap can promote an older seam to
 *     "newest recoverable". That seam is old history nobody asked for, and fetching it unprompted is
 *     precisely the regression this design is guarding against — before the divider existed, nothing
 *     ever fetched a deep gap on its own. Requiring a strictly newer position means a genuine
 *     reconnect gap (which always lands at the newest end) arms, and a promotion from below never
 *     does.
 *
 * [visibleSession] is only the "the room is on screen" gate; it deliberately does NOT reset
 * [armedThrough]. Backgrounding and resuming the app is not new information about history, so it
 * must not spend another budget on a seam this instance already worked on. What DOES re-arm is the
 * only thing that should: an instance of this class lives with one ChatViewModel, so re-entering the
 * room starts fresh — the same one-budget-per-open the timeline has always done — and everything
 * else that arms is a genuinely newer seam.
 *
 * [entrySettled] is the ordering constraint, and it is not optional. Normal entry FREEZES what was
 * unread at the moment the room opened — the divider position and its "N+" label — and it resolves
 * that from the store. A fill racing it rewrites the store first, so the frozen boundary lands on
 * rows the autopilot had just fetched instead of on what the user actually arrived to. Entry
 * resolves first, then history is filled underneath it.
 *
 * The one thing that does NOT spend an arming is a fill that achieved nothing; see [releaseStalled].
 */
internal class HistoryGapAutopilot {
    private var armedThrough: TimelineAnchor? = null
    private var armedFrom: TimelineAnchor? = null
    private var releases = 0

    fun arm(
        roomId: Long,
        visibleSession: Long?,
        availability: HistoryAvailability,
        entrySettled: Boolean,
        seams: List<TimelineSeam>,
    ): GapAutopilotArming? {
        if (visibleSession == null) return null
        if (!entrySettled) return null
        // Nothing to page against while the network is negotiating or offline; the next Ready
        // emission re-evaluates, so a room opened before its connection settles still catches up.
        if (availability !is HistoryAvailability.Ready) return null
        // Seams are ordered oldest-first, so the last recoverable one is the newest.
        val newest = seams.lastOrNull { it.recoverable } ?: return null
        armedThrough?.let { if (newest.position <= it) return null }
        armedFrom = armedThrough
        armedThrough = newest.position
        return GapAutopilotArming(roomId, newest.gapId, newest.position)
    }

    /**
     * Hand [armed]'s arming back, because the fill it started achieved literally nothing.
     *
     * Spending the single arming on a page that inserted no rows and did not move its boundary makes
     * the strictly-newer rule permanent for the wrong reason: nothing about the seam changed, so no
     * later seam is newer, so hands-free catch-up is retired for the rest of the visit while the
     * interval it was supposed to fetch is still missing, still recoverable, and still on screen.
     * A fill that came back empty-handed is a statement about that attempt, not about the seam.
     *
     * Bounded three ways, and all three have to hold for this not to become a retry loop:
     *  - only [io.github.trevarj.motd.data.sync.GapFillProgress.STALLED] reaches here, which is the
     *    anti-livelock stop with zero inserts. A fill that spent its budget, closed the seam, or
     *    failed on the wire keeps its arming spent;
     *  - [RELEASE_BUDGET] is a hard count for the life of this instance, i.e. for one room visit. It
     *    is never reset — not by a newer seam, not by backgrounding — so the hands-free fills a visit
     *    can start is capped at `1 + RELEASE_BUDGET`, each still bounded by the coordinator's own
     *    page budget;
     *  - releasing only rewinds the watermark. It does not itself start anything: the next arming
     *    still has to come from an emission of the seam flow, so with the room quiescent nothing
     *    happens at all, and after the contending fetch lands the seam that re-arms is the RECEDED
     *    one — the very case the strictly-newer rule would otherwise reject forever.
     *
     * A superseding arming (a genuinely newer seam armed in the meantime) is left alone; that one is
     * legitimately spent and the stale release must not resurrect the seam beneath it.
     */
    fun releaseStalled(armed: GapAutopilotArming) {
        if (armedThrough != armed.position) return
        if (releases >= RELEASE_BUDGET) return
        releases++
        armedThrough = armedFrom
    }

    internal companion object {
        /**
         * Stalled fills whose arming is returned, per room visit. Two, because one covers a single
         * contending fetch and the second covers that fetch's own follow-on page; a third would be
         * indistinguishable from retrying on hope.
         */
        internal const val RELEASE_BUDGET = 2
    }
}

/**
 * The seam drawn above [row]'s content in the reversed timeline, or null when that slot draws
 * nothing.
 *
 * Every seam call site in `MessageList` goes through this, so the composables are one-line wrappers
 * around it and a unit test asserting on it is asserting on the rendered slot.
 *
 * [olderNeighbor] must be the same neighbor the caller's own dividers are computed against — for a
 * collapsed system run that is the row just older than the WHOLE run, not the next index.
 */
fun rowSeam(
    row: MessageEntity,
    olderNeighbor: MessageEntity?,
    seams: TimelineSeamState,
): RowSeam? = seamAbove(row, olderNeighbor, seams.seams)
    ?.let { RowSeam(it.gapId, seams.stateFor(it)) }

/** Frozen normal-entry boundary; [lowerBound] means older unread rows are not loaded yet. */
data class UnreadEntrySnapshot(
    val marker: TimelineAnchor,
    val loadedCount: Int,
    val lowerBound: Boolean,
)

/**
 * Rebuild the frozen entry boundary from its flat SavedState projection.
 *
 * [computed] is state in its own right, not a null check: it separates "this visit never froze a
 * boundary" from "this visit froze the absence of one". Recomputing the second case after process
 * death would raise a divider for messages that arrived AFTER entry, which is precisely what
 * freezing on entry exists to prevent, so a restored absence is returned as an absence.
 */
internal fun restoredUnreadEntrySnapshot(
    computed: Boolean,
    markerServerTime: Long,
    markerEventId: Long,
    markerTimelineOrder: Long,
    loadedCount: Int,
    lowerBound: Boolean,
): UnreadEntrySnapshot? {
    if (!computed || markerServerTime <= 0) return null
    return UnreadEntrySnapshot(
        marker = TimelineAnchor(markerServerTime, markerEventId, markerTimelineOrder),
        loadedCount = loadedCount.coerceAtLeast(1),
        lowerBound = lowerBound,
    )
}

/** Match a stored actor using its persisted account/casemapped identity, never display spelling. */
fun MessageEntity.matchesConfiguredActor(
    configured: Set<String>,
    identityRules: IrcIdentityRules,
): Boolean {
    if (configured.isEmpty()) return false
    val normalized = configured.mapTo(hashSetOf()) { identityRules.normalize(it.trim()) }
    val accounts = configured.mapTo(hashSetOf()) { it.trim() }
    return normalizedActor in normalized ||
        senderAccount?.let { it in accounts } == true
}

/** Fool treatment is limited to incoming conversation rows. */
fun isFoolMessage(
    message: MessageEntity,
    fools: Set<String>,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): Boolean = message.kind in CONVERSATION_KINDS &&
    !message.isSelf &&
    message.matchesConfiguredActor(fools, identityRules)

/**
 * Policy predicate: drops JPQ rows when hidden, and drops fool rows only in HIDE mode.
 * System-event kinds are never fool-treated (JPQ visibility governs those). COLLAPSE keeps the row
 * so it can render as a tap-to-expand placeholder in the timeline.
 */
fun keepMessage(
    msg: MessageEntity,
    spec: MessageFilterSpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): Boolean = MessageVisibilityPolicy(spec, identityRules).timeline(msg)

/** Grouping window: consecutive same-sender messages within this span share one header. */
const val GROUP_WINDOW_MS: Long = 3 * 60 * 1000

/** Tone bucket for the latency readout (#34); pure so the thresholds are unit-testable. */
enum class LagTone { GOOD, DEGRADED, BAD }

/** Classify a PING/PONG round-trip into a display tone. Thresholds chosen for IRC-scale latency. */
fun lagTone(lagMs: Long): LagTone = when {
    lagMs < 300 -> LagTone.GOOD
    lagMs < 1_500 -> LagTone.DEGRADED
    else -> LagTone.BAD
}

/**
 * Scroll-offset slack (px) within which the reverse list still counts as "at bottom" for autoscroll.
 * Small so a barely-nudged newest row keeps auto-following, but the user is not pinned once they
 * deliberately scroll up. Compose scroll offsets are in raw pixels.
 */
const val AUTOSCROLL_BOTTOM_TOLERANCE_PX: Int = 64
internal const val MAX_PLACEHOLDER_PROBES: Int = 500
internal const val TARGET_MATERIALIZATION_TIMEOUT_MS = 30_000L
internal const val TOP_ALIGNMENT_TOLERANCE_PX = 1

/**
 * Upper bound on measure-correct passes when snapping the entry row to the viewport top. One pass
 * suffices on a quiet layout; a pass whose scroll a racing Paging generation presentation clamps is
 * observed on the next frame and re-corrected. The cap keeps a layout that legitimately cannot
 * align (content shorter than the viewport) from spinning until the materialization timeout.
 */
internal const val TOP_ALIGNMENT_MAX_PASSES = 8

/**
 * Decide whether an incoming message should pin the reverse list to the newest row (index 0). Only
 * autoscroll when the user is already at/near the bottom ([atBottom]) AND an already-populated
 * window grew ([newCount] > [oldCount]) — never yank a user who has scrolled up to read history.
 * The first Paging page is deliberately excluded: a reverse list starts at index 0 already, and
 * animating it to index 0 while the enter transition is running adds needless layout work.
 * Own-send scrolls unconditionally at the call site and does not route through this helper.
 */
fun shouldAutoscrollToNewest(atBottom: Boolean, oldCount: Int, newCount: Int): Boolean =
    atBottom && oldCount > 0 && newCount > oldCount

/** Which jump the scroll-to-bottom FAB performs. */
sealed interface ScrollToBottomFabJump {
    /** Jump to the nearest unread @mention below the viewport (the FAB's mention walk). */
    data class Mention(val target: ChatPositionTarget) : ScrollToBottomFabJump
    /** Jump straight to the newest row. */
    object Newest : ScrollToBottomFabJump
}

/**
 * Resolves the scroll-to-bottom FAB action. A long-press always skips the mention walk and goes to
 * newest; a tap follows the nearest unread [mentionTarget] when one is pending below the viewport,
 * otherwise it also goes to newest. Pure so the routing is unit-testable without composition.
 */
fun scrollToBottomFabJump(
    longPress: Boolean,
    mentionTarget: ChatPositionTarget?,
): ScrollToBottomFabJump =
    if (longPress || mentionTarget == null) ScrollToBottomFabJump.Newest
    else ScrollToBottomFabJump.Mention(mentionTarget)

/**
 * Tracks the user's decision to follow live arrivals independently from the reverse list's
 * transient physical position. Paging inserts and programmatic scrolls can both move index zero
 * without representing user intent, so deriving this state directly from the current bottom
 * position is racy.
 */
internal class AutoFollowTracker(initialItemCount: Int) {
    var following: Boolean = true
        private set

    private var itemCount: Int = initialItemCount
    private var newestEffectiveId: Long? = null

    val presentedItemCount: Int
        get() = itemCount

    /** Consume the first post-entry Paging snapshot without treating it as a live arrival. */
    fun reset(itemCount: Int, atBottom: Boolean, newestEffectiveId: Long? = null) {
        this.itemCount = itemCount
        this.newestEffectiveId = newestEffectiveId
        following = atBottom
    }

    /** Explicit send/FAB actions opt back into following the newest row. */
    fun requestFollow() {
        following = true
    }

    /**
     * Update follow intent only for real user scrolling. Programmatic motion and Paging anchor
     * shifts must not disable it. A user scroll that settles back at the bottom opts in again.
     */
    fun onScrollStateChanged(scrolling: Boolean, programmatic: Boolean, atBottom: Boolean) {
        if (programmatic) return
        following = if (scrolling) false else atBottom
    }

    /** Record a new presented count and return whether the viewport should pin to index zero. */
    fun onItemCountChanged(newItemCount: Int): Boolean {
        val shouldFollow = shouldAutoscrollToNewest(following, itemCount, newItemCount)
        itemCount = newItemCount
        return shouldFollow
    }

    /**
     * Follow a newly inserted meaningful row by identity, not by the volatile Paging item count.
     * Room invalidation may briefly publish an empty snapshot, and a bounded loaded window may
     * replace one old row with one new row without changing its count. Neither transition should
     * break live following. Auto-generated row ids are monotonic, so a lower identity (for example
     * exposing an older row after deletion) is not mistaken for a live arrival.
     */
    fun onTimelineChanged(newItemCount: Int, newNewestEffectiveId: Long?): Boolean {
        return onTimelineChangedWithEntry(newItemCount, newNewestEffectiveId).shouldFollow
    }

    /**
     * Classify a timeline update for both viewport following and the one-shot entrance animation.
     * The entry id is only exposed while the user is following the newest row; initial/history
     * updates and changes made while reading older messages must remain visually quiet.
     */
    fun onTimelineChangedWithEntry(
        newItemCount: Int,
        newNewestEffectiveId: Long?,
    ): TimelineChange {
        val previousNewestId = newestEffectiveId
        val shouldFollow = following && previousNewestId != null &&
            newNewestEffectiveId != null && newNewestEffectiveId > previousNewestId
        itemCount = newItemCount
        // An empty invalidation snapshot is not a real timeline transition. Retain the last
        // meaningful identity so the repopulated snapshot can still be classified as live/old.
        if (newNewestEffectiveId != null &&
            (previousNewestId == null || newNewestEffectiveId > previousNewestId)
        ) {
            newestEffectiveId = newNewestEffectiveId
        }
        return TimelineChange(
            shouldFollow = shouldFollow,
            liveEntryId = newNewestEffectiveId.takeIf { shouldFollow },
        )
    }
}

/** The small piece of timeline state that is allowed to cross into row rendering. */
internal data class TimelineChange(
    val shouldFollow: Boolean,
    val liveEntryId: Long?,
)

/** Timeline invalidations must retain in-flight entries while independent burst rows arrive. */
internal fun appendLiveEntryId(current: Set<Long>, arrived: Long?): Set<Long> =
    if (arrived == null || arrived in current) current else current + arrived

/** A disposed row consumes only its own entrance identity. */
internal fun consumeLiveEntryId(current: Set<Long>, consumed: Long): Set<Long> = current - consumed

/** Replacing a collapsed system-run head is an in-place summary update, not a new visual row. */
internal fun extendsSystemRun(
    liveEntryId: Long?,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
): Boolean {
    liveEntryId ?: return false
    val index = (0 until minOf(itemCount, MAX_PLACEHOLDER_PROBES))
        .firstOrNull { peek(it)?.id == liveEntryId } ?: return false
    if (index + 1 >= itemCount) return false
    val current = peek(index) ?: return false
    val older = peek(index + 1) ?: return false
    return isSystemKind(current.kind) && isSystemKind(older.kind)
}

fun newestEffectiveMessageId(
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): Long? = (0 until minOf(itemCount, MAX_PLACEHOLDER_PROBES)).firstNotNullOfOrNull { index ->
    peek(index)?.takeIf(policy::effectiveBottom)?.id
}

/**
 * Reverse-list bottom with any raw tail ignored by policy treated as already settled.
 *
 * "Ignored" means MATERIALIZED AND IGNORED. Every index below the viewport must be readable and
 * must be a row this [policy] does not treat as the effective bottom; an unloaded placeholder
 * (`peek == null`) blocks the bottom outright. This is the whole safety property of the predicate,
 * because its consumer acknowledges the ROOM's newest anchor: unknown is not "already read", and
 * skipping nulls would let a viewport parked deep in history — with the newest pages dropped by
 * `maxSize` and therefore null underneath it — claim the conversation's bottom and upload a
 * MARKREAD for messages that were never displayed. A deep jump lands in exactly that state.
 *
 * Live following is untouched by the stricter rule. A user genuinely at the bottom sits at index 0,
 * so [belowViewport] is empty and the loop cannot reject anything; a user sitting above a short
 * ignored tail is within Paging's prefetch window, so those rows are loaded. Anything further than
 * that errs toward "not at the bottom", which only ever withholds an acknowledgement, shows the
 * newest FAB, and saves a scroll position.
 */
fun isAtEffectiveBottom(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): Boolean {
    if (firstVisibleOffset > AUTOSCROLL_BOTTOM_TOLERANCE_PX) return false
    val belowViewport = minOf(firstVisibleIndex, itemCount)
    if (belowViewport > MAX_PLACEHOLDER_PROBES) return false
    for (index in 0 until belowViewport) {
        val row = peek(index) ?: return false
        if (policy.effectiveBottom(row)) return false
    }
    return true
}

/** Prefer an eligible row at or older than the viewport; used to avoid saving fool anchors. */
fun nearestAnchorRow(
    firstVisibleIndex: Int,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): Pair<Int, MessageEntity>? {
    val olderEnd = minOf(itemCount, firstVisibleIndex + MAX_PLACEHOLDER_PROBES)
    for (index in firstVisibleIndex until olderEnd) {
        val row = peek(index) ?: continue
        if (policy.anchor(row)) return index to row
    }
    val newerEnd = maxOf(0, firstVisibleIndex - MAX_PLACEHOLDER_PROBES)
    for (index in minOf(firstVisibleIndex - 1, itemCount - 1) downTo newerEnd) {
        val row = peek(index) ?: continue
        if (policy.anchor(row)) return index to row
    }
    return null
}

/** One exact destination model shared by deep links and saved positions. */
data class ChatPositionTarget(
    val index: Int,
    val offset: Int = 0,
    val expectedEventId: Long? = null,
    val expectedMsgid: String? = null,
    val serverTime: Long = 0,
    val highlightMsgid: String? = null,
    val fromSavedPosition: Boolean = false,
    /**
     * This entry target is an intentional destination (e.g. the buffer's last-read marker), so it
     * must displace the viewport even when the retained list state already sits at the bottom.
     */
    val forceScrollOnEntry: Boolean = false,
    /**
     * On entry, place the first-unread row at the TOP of the viewport (mature-chat open-at-unread),
     * with the remaining unread continuing below it. The placement is realized in [ChatScreen]:
     * load the target off-screen (no scroll), measure how many rows fit, then snap the viewport so
     * the first unread tops the window. Only the read-marker entry target sets this.
     */
    val placeAtTop: Boolean = false,
    /** Opaque ViewModel request identity; stale UI completions must not consume a newer jump. */
    val requestToken: Long = 0,
)

/** Identity-free targets describe an insertion point, which may sit just past the last row. */
internal fun materializableTargetIndex(
    requestedIndex: Int,
    itemCount: Int,
    hasExactIdentity: Boolean,
): Int? = when {
    requestedIndex in 0 until itemCount -> requestedIndex
    !hasExactIdentity && requestedIndex == itemCount && itemCount > 0 -> itemCount - 1
    else -> null
}

/** Find a materialized row by the stable LazyColumn key, never by its pre-layout index. */
internal fun materializedTargetVisibleIndex(
    visibleItems: List<Pair<Any, Int>>,
    eventId: Long,
): Int? = visibleItems.firstOrNull { (key, _) -> key == eventId }?.second

internal fun shouldShowNewestFab(
    atBottom: Boolean,
    autoScrolling: Boolean,
): Boolean = !atBottom && !autoScrolling

/**
 * Viewport acknowledgement is only honest when the viewport's bottom is the conversation's bottom.
 *
 * The mark-read effect reads at-bottom from the CURRENT paging snapshot but acknowledges the room's
 * newest stored row, so everything rests on [atBottom] meaning "there is provably nothing unseen
 * below me". It used to carry a second gate for the one case where those two disagreed: a bounded
 * deep-jump island, whose index 0 was the island's bottom rather than the room's, so reaching it
 * marked newer messages read and uploaded a MARKREAD to every other client.
 *
 * Bounded islands are retired — the timeline is one unbounded list — and that gate is deliberately
 * NOT replaced by a constant. The same disagreement now appears as unloaded rows below the viewport,
 * and it is [isAtEffectiveBottom] that rules them out: a null placeholder below the viewport is not
 * a row the user has seen, so it blocks the bottom. Weakening that predicate re-opens this defect,
 * with no second gate left to catch it.
 */
internal fun shouldMarkReadFromViewport(
    atBottom: Boolean,
    initialPositionSettled: Boolean,
    viewportReadEnabled: Boolean,
): Boolean = viewportReadEnabled && initialPositionSettled && atBottom

/**
 * Newest row the timeline has actually placed on screen, or null while that cannot be proven.
 *
 * [renderedIndex]/[renderedKey] come from the last measure pass; [peek] reads the CURRENT Paging
 * snapshot. Those two disagree whenever rows were presented without being measured — Paging keeps
 * presenting while the screen is paused, and a prepend shifts every index — so an index on its own
 * can name a row that was never displayed. The laid-out row carries its own key, and requiring it
 * to still be the row at that index is what makes the pairing trustworthy. From there the scan
 * walks OLDER only: rows below the rendered position are the ignored tail the effective bottom
 * already treats as settled, and they were not on screen either.
 */
internal fun renderedBottomAnchor(
    renderedIndex: Int,
    renderedKey: Any?,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): TimelineAnchor? {
    if (renderedIndex < 0 || renderedIndex >= itemCount) return null
    if (peek(renderedIndex)?.id != renderedKey) return null
    val olderEnd = minOf(itemCount, renderedIndex + MAX_PLACEHOLDER_PROBES)
    for (index in renderedIndex until olderEnd) {
        val row = peek(index) ?: continue
        if (policy.anchor(row)) return TimelineAnchor(row.serverTime, row.id, row.timelineOrder)
    }
    return null
}

/**
 * The anchor one run of the viewport mark-read effect may acknowledge.
 *
 * A steady-state run acknowledges [rawNewest], the room's newest stored row, and has to keep doing
 * so: an ignored raw tail below the viewport is already settled and only that anchor retires it.
 *
 * A resumed run is not steady state. `viewportReadEnabled` keys the effect, so the pause -> resume
 * flip restarts it, and by then everything that arrived while the screen was away is already in
 * [rawNewest] and in the Paging snapshot while nothing has measured it — the acknowledgement would
 * be driven by arrival rather than by display, uploading a MARKREAD for a backlog the user never
 * saw. Clamping that one run to [renderedNewest] lets a resume confirm only rows the timeline
 * actually put on screen. It cannot over-acknowledge either: the clamp only ever moves the anchor
 * older, so [shouldMarkReadFromViewport] still decides whether anything is acknowledged at all.
 */
internal fun viewportMarkReadAnchor(
    rawNewest: TimelineAnchor?,
    renderedNewest: TimelineAnchor?,
    resumed: Boolean,
): TimelineAnchor? {
    val raw = rawNewest ?: return null
    if (!resumed) return raw
    val rendered = renderedNewest ?: return null
    return minOf(rendered, raw)
}

data class ChatScrollPosition(
    val index: Int,
    val offset: Int,
    val msgid: String?,
    val serverTime: Long,
    val rowId: Long,
)

/**
 * Normal entry scroll: an explicit saved viewport or last-read marker always restores. A plain
 * unsaved target only repairs list state retained physically off-bottom; it must not displace an
 * already-bottom conversation.
 */
fun shouldScrollToInitialTarget(target: ChatPositionTarget, atBottom: Boolean): Boolean =
    target.fromSavedPosition || target.forceScrollOnEntry || !atBottom

/**
 * Index to bring to the bottom (start) of a reversed viewport so that [firstUnreadIndex] lands
 * `rowsFit - 1` rows above it, i.e. at the top of the viewport. Clamps to 0 when fewer than `rowsFit`
 * unread rows exist below the target (the list cannot scroll past index 0, so the first unread
 * then stays in view within the lower viewport with read history above it). Caller must guard
 * `rowsFit >= 1`; an empty measurement would otherwise scroll past the first unread.
 */
internal fun firstUnreadTopAnchorIndex(firstUnreadIndex: Int, rowsFit: Int): Int =
    (firstUnreadIndex - (rowsFit - 1)).coerceAtLeast(0)

/** Canonical local identity is checked before the case-sensitive opaque wire msgid. */
fun positionTargetMatches(target: ChatPositionTarget, actual: MessageEntity?): Boolean {
    actual ?: return false
    if (target.expectedEventId != null && actual.id != target.expectedEventId) return false
    if (target.expectedMsgid != null && actual.msgid != target.expectedMsgid) return false
    return true
}

internal data class TargetMaterialization<T>(
    val item: T?,
    val loading: Boolean,
    val addressable: Boolean = true,
    val failed: Boolean = false,
    /** Changes when Paging replaces or materially shifts the loaded snapshot. */
    val generation: Any? = null,
)

/** Re-request cadence for a target whose Paging load hint produced no observable load. */
internal const val TARGET_REHINT_INTERVAL_MS = 1_000L

/** Request exactly one placeholder and wait for that position, without scanning the dataset. */
internal suspend fun <T> requestAndAwaitTarget(
    index: Int,
    request: suspend (Int) -> Boolean,
    snapshots: Flow<TargetMaterialization<T>>,
    rehintIntervalMs: Long = TARGET_REHINT_INTERVAL_MS,
): T? {
    val before = snapshots.first()
    if (!request(index)) return null
    var observedLoading = before.loading
    return withTimeoutOrNull(TARGET_MATERIALIZATION_TIMEOUT_MS) {
        while (true) {
            var streamEnded = false
            val terminal = withTimeoutOrNull(rehintIntervalMs) {
                snapshots.firstOrNull { snapshot ->
                    observedLoading = observedLoading || snapshot.loading
                    val replaced = snapshot.generation != before.generation
                    val newFailure = snapshot.failed && (!before.failed || observedLoading || replaced)
                    snapshot.item != null || newFailure ||
                        (!snapshot.addressable && !snapshot.loading) ||
                        ((observedLoading || replaced) && !snapshot.loading)
                }.also { streamEnded = it == null }
            }
            when {
                terminal != null -> return@withTimeoutOrNull terminal.item
                streamEnded -> return@withTimeoutOrNull null
                // A whole interval passed with no terminal snapshot and no load ever observed for a
                // parked placeholder viewport: Paging can drop the single viewport hint when it
                // races the generation's initial prepend/refresh, and nothing else will ever load
                // the target. Re-issue the idempotent request so the hint is re-recorded instead of
                // sitting quiescent until the outer cap.
                else -> if (!request(index)) return@withTimeoutOrNull null
            }
        }
        // withTimeoutOrNull cancels the loop at the materialization cap.
        @Suppress("UNREACHABLE_CODE")
        null
    }
}

data class ReplyJumpRequest(val msgid: String)

sealed interface ChatUiEvent {
    data object InvalidCommand : ChatUiEvent
    data object ReactionBlocked : ChatUiEvent
    data object ReactionTargetUnavailable : ChatUiEvent
    data object ReactionSendFailed : ChatUiEvent
    data object SendRejected : ChatUiEvent
    data object NotInChannel : ChatUiEvent
    data class ReplyJumpUnavailable(val request: ReplyJumpRequest) : ChatUiEvent
    data object ConversationLayoutWriteFailed : ChatUiEvent
}

/** Database-backed conversation layout and the global setting it may inherit. */
data class ConversationLayoutState(
    val global: LayoutDensity = LayoutDensity.COMFORTABLE,
    val override: LayoutDensity? = null,
) {
    val effective: LayoutDensity get() = override ?: global
}

data class QueuedChatUiEvent(val id: Long, val value: ChatUiEvent)

/** StateFlow-backed FIFO so recreation replays every unacknowledged event exactly once. */
internal class ChatUiEventQueue {
    private val lock = Any()
    private var nextId = 0L
    private val _pending = MutableStateFlow<List<QueuedChatUiEvent>>(emptyList())
    val pending = _pending.asStateFlow()

    fun enqueue(value: ChatUiEvent): QueuedChatUiEvent = synchronized(lock) {
        QueuedChatUiEvent(++nextId, value).also { event ->
            _pending.value = _pending.value + event
        }
    }

    fun acknowledge(id: Long) = synchronized(lock) {
        _pending.value = _pending.value.filterNot { it.id == id }
    }
}

internal fun ChatUiEvent.hasRetryAction(): Boolean =
    this is ChatUiEvent.ReplyJumpUnavailable

/** Run a snackbar action before acknowledging its replay-safe queued event. */
internal fun handleChatUiEventResult(
    event: QueuedChatUiEvent,
    actionPerformed: Boolean,
    retryReplyJump: (ReplyJumpRequest) -> Unit,
    acknowledge: (Long) -> Unit,
) {
    if (actionPerformed) {
        when (val value = event.value) {
            is ChatUiEvent.ReplyJumpUnavailable -> retryReplyJump(value.request)
            else -> Unit
        }
    }
    acknowledge(event.id)
}

/**
 * Footer state for the older end of the reverse timeline — the BOTTOM of the list, past the oldest
 * retained row. Scroll-driven paging drives APPEND automatically, so the footer only reflects the
 * current [LoadState.append] plus the connection's history availability; there is no explicit "load
 * older" affordance here.
 *
 * Interior history gaps are not this footer's business. The timeline is presented unbounded, so a
 * gap is a seam drawn between two materialized rows with its own tappable [HistoryGapState] divider
 * ([rowSeam]); it never reaches the bottom of the list and never shows up in [LoadState.append].
 */
sealed interface ChatHistoryUiState {
    /** Nothing to show: server/no buffer, or a Ready timeline mid-history. */
    data object Hidden : ChatHistoryUiState

    /** An APPEND page is in flight (shimmer). */
    data object Loading : ChatHistoryUiState

    /** A recoverable append error; the footer offers `items.retry()`. */
    data object Retry : ChatHistoryUiState

    /** History is unreachable: [offline] true when disconnected/fatal, false while negotiating. */
    data class Unavailable(val offline: Boolean) : ChatHistoryUiState

    /** The network does not advertise CHATHISTORY. */
    data object Unsupported : ChatHistoryUiState

    /** Persisted protocol completion: the true start of history. */
    data object ConfirmedStart : ChatHistoryUiState
}

internal fun chatHistoryUiState(
    bufferType: BufferType?,
    connectionState: IrcClientState?,
    availability: HistoryAvailability,
    append: LoadState,
    historyComplete: Boolean,
): ChatHistoryUiState {
    if (bufferType == null || bufferType == BufferType.SERVER) return ChatHistoryUiState.Hidden
    // A final capability decision supersedes a stale mediator error/loading state.
    if (availability == HistoryAvailability.Unsupported) return ChatHistoryUiState.Unsupported
    if (append is LoadState.Loading) return ChatHistoryUiState.Loading
    if (append is LoadState.Error) {
        return when (availability) {
            HistoryAvailability.NegotiatingOrOffline ->
                ChatHistoryUiState.Unavailable(offline = isHistoryOffline(connectionState))
            else -> ChatHistoryUiState.Retry
        }
    }
    if (append.endOfPaginationReached && historyComplete) {
        return ChatHistoryUiState.ConfirmedStart
    }
    return when (availability) {
        HistoryAvailability.Unsupported -> ChatHistoryUiState.Unsupported
        HistoryAvailability.NegotiatingOrOffline ->
            ChatHistoryUiState.Unavailable(offline = isHistoryOffline(connectionState))
        // A Ready timeline pages older on scroll. End-of-pagination without persisted completion
        // (an unmoving boundary, say) has no affordance: the retry belongs to the wire error state,
        // not to a footer that would claim history exists. An unrecoverable gap no longer reaches
        // here at all — it ends nothing, it draws a seam.
        is HistoryAvailability.Ready -> ChatHistoryUiState.Hidden
    }
}

private fun isHistoryOffline(connectionState: IrcClientState?): Boolean = when (connectionState) {
    IrcClientState.Disconnected -> true
    is IrcClientState.Failed -> connectionState.fatal
    else -> false
}

/** Retries each offline mediator failure once when its connection generation is Ready. */
internal class HistoryReadyRetryGate {
    private var retriedError: Throwable? = null

    fun update(availability: HistoryAvailability, append: LoadState): Boolean {
        if (availability == HistoryAvailability.Unsupported) return false
        val error = (append as? LoadState.Error)?.error ?: return false
        if (error !is io.github.trevarj.motd.irc.client.IrcDisconnectedException) return false
        if (availability !is HistoryAvailability.Ready || retriedError === error) return false
        retriedError = error
        return true
    }
}

/**
 * Aggregate raw [ReactionEntity] rows into per-msgid chip lists: one chip per emoji with its count
 * and whether [myNick] is among the reactors. Ordered by first appearance for stability.
 *
 * Ownership compares persisted actor keys. The authenticated account wins when known; otherwise
 * the supplied network rules produce the same casemapped nick key as EventProcessor.
 */
fun aggregateReactions(
    reactions: List<ReactionEntity>,
    myNick: String?,
    myAccount: String? = null,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): Map<String, List<ReactionChip>> {
    val myActorKeys = buildSet {
        myAccount?.takeUnless { it.isEmpty() || it == "*" }?.let { add("account:$it") }
        myNick?.let { nick ->
            add(identityRules.actorKey(nick, account = null))
            if (myAccount != null) add(identityRules.actorKey(nick, myAccount))
        }
    }
    val myNormalizedNick = myNick?.let(identityRules::normalize)
    // msgid -> emoji -> (count, mine)
    val byMsg = LinkedHashMap<String, LinkedHashMap<String, MutableReactionAgg>>()
    for (r in reactions) {
        val emojiMap = byMsg.getOrPut(r.targetMsgid) { LinkedHashMap() }
        val agg = emojiMap.getOrPut(r.emoji) { MutableReactionAgg() }
        agg.count++
        if (
            r.actorKey in myActorKeys ||
            (myAccount == null && myNormalizedNick != null &&
                identityRules.normalize(r.sender) == myNormalizedNick)
        ) {
            agg.mine = true
        }
    }
    return byMsg.mapValues { (_, emojiMap) ->
        emojiMap.map { (emoji, agg) -> ReactionChip(emoji, agg.count, agg.mine) }
    }
}

private class MutableReactionAgg(var count: Int = 0, var mine: Boolean = false)
