package io.github.trevarj.motd.service

/**
 * One catch-up candidate: a room the pass could fetch a newest page for.
 *
 * [knownBufferId] is null for a target discovery named that this device has no room for yet (a DM
 * the pass will create when it fetches it). [latestMessageTime] is what discovery advertised, and
 * null when only an open buffer contributed the target — discovery said nothing about it.
 */
internal data class SyncTarget(
    val knownBufferId: Long?,
    val name: String,
    val latestMessageTime: Long?,
    val pinned: Boolean = false,
)

/** A [SyncTarget] paired with the one question that decides whether it is fetched at all. */
internal data class CatchUpCandidate(val target: SyncTarget, val changed: Boolean)

/**
 * How one catch-up pass spends its fan-out.
 *
 * [waveOne] is fetched concurrently, with chrome and per-buffer status, in exactly this order.
 * [waveTwo] is the overflow: same fetches, but paced and silent, so a hundred-room account cannot
 * turn a reconnect into a hundred-request burst or a hundred-row spinner. [settledUnchanged] are
 * rooms the pass proved are already current and will not touch — their status settles immediately
 * instead of sitting queued behind work that is never coming.
 */
internal data class CatchUpWavePlan(
    val waveOne: List<SyncTarget>,
    val waveTwo: List<SyncTarget>,
    val settledUnchanged: List<Long>,
)

/**
 * Has this room moved since the device last fetched it?
 *
 * A room with no stored cursor has never been fetched at all — a fresh JOIN, a first sync, a room
 * that only ever received live messages — so it is always changed: skipping it would leave it
 * permanently empty. Otherwise the question is whether discovery advertised anything newer than the
 * cursor, with [reachedAdvertisedTolerance]'s second-level tolerance, because stored server-time
 * tags can carry second precision while TARGETS advertises milliseconds.
 *
 * A target discovery did not describe ([advertisedLatest] null) but that the pass knows about
 * (an open buffer) is unchanged as far as the server is concerned: discovery enumerated the whole
 * window and did not mention it.
 */
internal fun targetChanged(
    advertisedLatest: Long?,
    cursorNewest: Long?,
    hasCursor: Boolean,
): Boolean = when {
    !hasCursor -> true
    advertisedLatest == null -> false
    else -> !reachedAdvertisedTolerance(cursorNewest, advertisedLatest)
}

/**
 * Advertised-latest comparison with sub-second tolerance: a stored newest in the same second as the
 * advertisement means the page that would be fetched is already local.
 */
internal fun reachedAdvertisedTolerance(stored: Long?, advertised: Long): Boolean =
    stored != null && stored / 1000 >= advertised / 1000

/**
 * The order a pass admits targets in, and therefore the order the user sees rooms fill in.
 *
 * The chat being looked at first — it is the one place a stale timeline is actually visible — then
 * pinned rooms, then whatever discovery says moved most recently. `sortedWith` is stable, so
 * targets discovery said nothing about keep their incoming (buffer-id) order at the tail.
 */
internal fun catchUpOrder(foregroundBufferId: Long?): Comparator<SyncTarget> =
    compareByDescending<SyncTarget> {
        foregroundBufferId != null && it.knownBufferId == foregroundBufferId
    }
        .thenByDescending { it.pinned }
        .thenByDescending { it.latestMessageTime != null }
        .thenByDescending { it.latestMessageTime ?: Long.MIN_VALUE }

/**
 * Split a pass's candidates into what it fetches now, what it trickles in behind, and what it does
 * not fetch at all.
 *
 * The unchanged partition is the point of the whole exercise: before it existed, every reconnect
 * re-requested the newest page of every open buffer, which on a quiet account is a burst of requests
 * that insert nothing and a chat list full of spinners describing work with no outcome. Discovery
 * already answered "which rooms moved" in its first response; this is that answer being used.
 *
 * The wave-one bound exists for the opposite case. An account where everything moved would otherwise
 * put every room into one concurrent burst; capping it keeps the visible top of the list fast and
 * leaves the rest to a paced sweep the reader never has to watch.
 */
internal fun planCatchUpWaves(
    candidates: List<CatchUpCandidate>,
    foregroundBufferId: Long?,
    waveOneLimit: Int = WAVE_ONE_LIMIT,
): CatchUpWavePlan {
    val (changed, unchanged) = candidates.partition { it.changed }
    val ordered = changed.map { it.target }.sortedWith(catchUpOrder(foregroundBufferId))
    val limit = waveOneLimit.coerceAtLeast(1)
    return CatchUpWavePlan(
        waveOne = ordered.take(limit),
        waveTwo = ordered.drop(limit),
        settledUnchanged = unchanged.mapNotNull { it.target.knownBufferId },
    )
}

/**
 * Rooms fetched with chrome and concurrency before the rest is trickled in.
 *
 * Two adaptive rounds at the starting width: enough that a normal account's whole visible list is
 * current within two round trips, small enough that the burst is bounded on an account where
 * everything moved at once.
 */
internal const val WAVE_ONE_LIMIT = 12
