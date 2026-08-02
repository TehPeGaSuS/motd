package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Sole owner of a single CHATHISTORY page fetch: it builds the directional request from a
 * caller-supplied local boundary, applies the msgid→timestamp fallback, guards unsafe continuation,
 * persists the page through the sole IRC→Room writer ([EventProcessor]), and owns all fetch
 * concurrency (per-network wire serialization, per-direction coalescing, and the request timeout).
 *
 * Directional decisions — which boundary to page from, and how a per-focus gap constrains the
 * endOfPagination outcome — stay with the caller ([ChatHistoryRemoteMediator]); the loader only
 * turns a `(direction, boundary)` pair into one persisted page and reports whether that direction is
 * exhausted or must stop to avoid a refetch loop.
 */
@Singleton
class HistoryPageLoader @Inject constructor(
    private val processor: EventProcessor,
) {
    /**
     * Minimal seam over the live history transport (availability + a single labeled request),
     * resolved per call so a client that connects after a buffer opens is picked up on the next
     * boundary hit. Callers reuse this exact shape for their own source seams.
     */
    interface HistorySource {
        suspend fun availability(): HistoryAvailability
        suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse
    }

    /** The three directions a boundary can be paged toward. LATEST ignores [boundary]. */
    enum class Direction { OLDER, NEWER, LATEST }

    /** Outcome of a single page fetch. */
    sealed interface PageResult {
        /**
         * A page was fetched and persisted. [endOfDirection] is true when this direction is
         * exhausted (server end reached) or must stop to avoid a non-advancing/saturated refetch
         * loop; callers may still narrow that with their own per-focus gap accounting.
         */
        data class Loaded(val primaryCount: Int, val endOfDirection: Boolean) : PageResult

        /** The network does not advertise CHATHISTORY. */
        data object Unsupported : PageResult

        /** History is negotiating or offline; the request is retryable. */
        data class Unavailable(val cause: Throwable) : PageResult

        /** The server returned a response that could not be used as a durable boundary. */
        data class Failed(val cause: Throwable) : PageResult
    }

    // A separate per-network gate from the coordinator/mediator single-flight: later phases collapse
    // those onto this one, so the loader owns the wire serialization directly from the start.
    private val networkLocks = ConcurrentHashMap<Long, Mutex>()
    private val inFlight = ConcurrentHashMap<FlightKey, CompletableDeferred<PageResult>>()
    internal var requestTimeoutMs: Long = REQUEST_TIMEOUT_MS

    // TODO(phase3): the key omits the boundary, so distinct boundaries in one direction coalesce
    // onto whichever page is in flight. Either add the boundary to the key or document/pin the
    // any-fresh-page semantics when the outer mediator serialization is removed.
    private data class FlightKey(val networkId: Long, val roomId: RoomId, val direction: Direction)

    /**
     * Fetch and persist exactly one page for [roomId] (always canonical) in [direction] from
     * [boundary]. Concurrent identical `(network, room, direction)` requests coalesce onto one
     * in-flight fetch; distinct fetches on the same network still serialize on the wire.
     */
    suspend fun loadPage(
        networkId: Long,
        roomId: RoomId,
        target: String,
        direction: Direction,
        source: HistorySource,
        pageSize: Int = 50,
        gapId: Long? = null,
        boundary: ChatHistoryReference? = null,
    ): PageResult {
        val availability = source.availability()
        val ready = when (availability) {
            HistoryAvailability.Unsupported -> return PageResult.Unsupported
            HistoryAvailability.NegotiatingOrOffline -> return PageResult.Unavailable(
                IrcDisconnectedException("CHATHISTORY", "history is negotiating or offline"),
            )
            is HistoryAvailability.Ready -> availability
        }
        val requestLimit = minOf(pageSize, ready.pageLimit).coerceAtLeast(1)
        val referenceTypes = ready.referenceTypes
        return coalesced(FlightKey(networkId, roomId, direction)) {
            networkLocks.getOrPut(networkId, ::Mutex).withLock {
                when (direction) {
                    Direction.LATEST -> loadLatest(networkId, roomId, target, source, requestLimit, referenceTypes)
                    Direction.OLDER -> loadOlder(
                        networkId, roomId, target, source, requestLimit, referenceTypes, gapId, boundary,
                    )
                    Direction.NEWER -> loadNewer(
                        networkId, roomId, target, source, requestLimit, referenceTypes, gapId, boundary,
                    )
                }
            }
        }
    }

    /** Pull the most recent page and persist it through the sole IRC→Room writer. */
    private suspend fun loadLatest(
        networkId: Long,
        roomId: RoomId,
        target: String,
        source: HistorySource,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
    ): PageResult {
        val request = ChatHistoryRequest(
            ChatHistoryRequest.Subcommand.LATEST,
            target,
            limit = requestLimit,
        )
        val result = fetch(source, request)
        if (!result.isComplete && !result.hasUsableOldest(referenceTypes, true)) {
            return PageResult.Failed(
                IllegalStateException("CHATHISTORY LATEST returned no advertised primary-message boundary"),
            )
        }
        processor.persistHistoryPage(
            networkId,
            request,
            result.withAdvertisedBoundaries(
                referenceTypes,
                allowMsgid = HistoryReferenceType.MSGID in referenceTypes,
            ),
            expectedRoomId = roomId,
        )
        return PageResult.Loaded(
            result.primaryMessageCount,
            endOfDirection = result.isComplete ||
                result.cannotSafelyPageBefore(referenceTypes, true, requestLimit),
        )
    }

    /** Page older via BEFORE from [boundary], persisting into the optional focused [gapId]. */
    private suspend fun loadOlder(
        networkId: Long,
        roomId: RoomId,
        target: String,
        source: HistorySource,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
        gapId: Long?,
        boundary: ChatHistoryReference?,
    ): PageResult {
        val oldest = boundary ?: return PageResult.Failed(
            IllegalStateException("CHATHISTORY BEFORE requires a local boundary"),
        )
        val selected = oldest.selector(referenceTypes, allowMsgid = true)
            ?: return PageResult.Failed(
                IllegalStateException("CHATHISTORY BEFORE has no advertised local boundary selector"),
            )
        var request = ChatHistoryRequest(
            ChatHistoryRequest.Subcommand.BEFORE,
            target,
            bound1 = selected.value,
            limit = requestLimit,
        )
        var responseMsgidAllowed = selected.type == HistoryReferenceType.MSGID
        val result = try {
            fetch(source, request)
        } catch (error: IrcCommandException) {
            if (selected.type != HistoryReferenceType.MSGID || error.code != INVALID_MSGREFTYPE) {
                throw error
            }
            val timestamp = oldest.selector(referenceTypes, allowMsgid = false) ?: throw error
            request = request.copy(bound1 = timestamp.value)
            responseMsgidAllowed = false
            fetch(source, request)
        }
        if (
            !result.isComplete &&
            !result.hasUsableOldest(referenceTypes, responseMsgidAllowed)
        ) {
            return PageResult.Failed(
                IllegalStateException("CHATHISTORY BEFORE returned no advertised primary-message boundary"),
            )
        }
        // Apply the page as one IRC history batch. EventProcessor wraps HistoryBatch in a single
        // Room transaction, so Paging sees one invalidation instead of up to 50 row-by-row refreshes
        // while the user is entering or flinging through a channel.
        processor.persistHistoryPageResult(
            networkId,
            request,
            result.withAdvertisedBoundaries(referenceTypes, responseMsgidAllowed),
            expectedRoomId = roomId,
            historyGapId = gapId,
        )
        if (result.isComplete) return PageResult.Loaded(result.primaryMessageCount, endOfDirection = true)
        // A non-advancing cursor would refetch forever. A saturated timestamp-only page is also
        // ambiguous because BEFORE would skip any additional messages sharing its oldest timestamp.
        // Preserve the page, leave historyComplete false, and stop this direction.
        return PageResult.Loaded(
            result.primaryMessageCount,
            endOfDirection = result.cannotSafelyPageBefore(
                referenceTypes,
                responseMsgidAllowed,
                requestLimit,
                previous = selected,
            ),
        )
    }

    /** Grow toward the recent window via AFTER from [boundary], persisting into focused [gapId]. */
    private suspend fun loadNewer(
        networkId: Long,
        roomId: RoomId,
        target: String,
        source: HistorySource,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
        gapId: Long?,
        boundary: ChatHistoryReference?,
    ): PageResult {
        val newer = boundary ?: return PageResult.Failed(
            IllegalStateException("CHATHISTORY AFTER requires a local boundary"),
        )
        val selected = newer.selector(referenceTypes, allowMsgid = true)
            ?: return PageResult.Failed(
                IllegalStateException("CHATHISTORY AFTER has no advertised local boundary selector"),
            )
        var request = ChatHistoryRequest(
            ChatHistoryRequest.Subcommand.AFTER,
            target,
            bound1 = selected.value,
            limit = requestLimit,
        )
        var responseMsgidAllowed = selected.type == HistoryReferenceType.MSGID
        val result = try {
            fetch(source, request)
        } catch (error: IrcCommandException) {
            if (selected.type != HistoryReferenceType.MSGID || error.code != INVALID_MSGREFTYPE) {
                throw error
            }
            val timestamp = newer.selector(referenceTypes, allowMsgid = false) ?: throw error
            request = request.copy(bound1 = timestamp.value)
            responseMsgidAllowed = false
            fetch(source, request)
        }
        if (
            !result.isComplete &&
            !result.hasUsableNewest(referenceTypes, responseMsgidAllowed)
        ) {
            return PageResult.Failed(
                IllegalStateException("CHATHISTORY AFTER returned no advertised primary-message boundary"),
            )
        }
        processor.persistHistoryPageResult(
            networkId,
            request,
            result.withAdvertisedBoundaries(referenceTypes, responseMsgidAllowed),
            expectedRoomId = roomId,
            historyGapId = gapId,
        )
        return PageResult.Loaded(
            result.primaryMessageCount,
            endOfDirection = result.isComplete ||
                result.cannotSafelyPageAfter(
                    referenceTypes,
                    responseMsgidAllowed,
                    requestLimit,
                    previous = selected,
                ),
        )
    }

    /**
     * Coalesce concurrent identical fetches. The leader runs [block] in its *caller's* coroutine —
     * not a detached scope — so cancelling the leader also fails any joined followers. Unreachable
     * this phase (the mediator's outer single-flight + per-buffer locks already serialize identical
     * keys); before Phase 3 removes those, this must be hardened with a dedicated scope job or
     * follower-retry-on-leader-cancel.
     */
    private suspend fun coalesced(
        key: FlightKey,
        block: suspend () -> PageResult,
    ): PageResult {
        inFlight[key]?.let { return it.await() }
        val deferred = CompletableDeferred<PageResult>()
        inFlight.putIfAbsent(key, deferred)?.let { return it.await() }
        try {
            val result = block()
            deferred.complete(result)
            return result
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    private suspend fun fetch(source: HistorySource, request: ChatHistoryRequest): ChatHistoryResponse.Messages {
        // withTimeout crosses a coroutine boundary, so coroutine stacktrace recovery would hand back
        // a copy of whatever the source raised. RemoteMediator must let the original
        // CancellationException instance reach Paging untouched, so capture and rethrow the exact
        // throwable the source produced.
        var raised: Throwable? = null
        val response = try {
            withTimeout(requestTimeoutMs) {
                try {
                    source.chathistory(request)
                } catch (error: Throwable) {
                    raised = error
                    throw error
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Never let the timeout escape as a CancellationException: the mediator rethrows those
            // to Paging, whose accessor would keep this direction's LoadState stuck at Loading with
            // a stale pending request. Surface it as a retryable transport failure instead.
            throw IrcDisconnectedException("CHATHISTORY", "request timed out")
        } catch (error: Throwable) {
            throw raised ?: error
        }
        return (response as? ChatHistoryResponse.Messages)
            ?.boundedToRequest(request)
            ?: error("CHATHISTORY ${request.subcommand} returned a TARGETS response")
    }

    /** Keep stored cursors constrained to selectors the server actually advertised. */
    private fun ChatHistoryResponse.Messages.withAdvertisedBoundaries(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
    ): ChatHistoryResponse.Messages {
        if (allowMsgid && HistoryReferenceType.MSGID in referenceTypes) return this
        return copy(
            oldest = oldest?.copy(msgid = null),
            newest = newest?.copy(msgid = null),
        )
    }

    private val ChatHistoryResponse.Messages.isComplete: Boolean
        get() = endOfHistory || primaryMessageCount == 0

    private fun ChatHistoryResponse.Messages.hasUsableOldest(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
    ): Boolean = oldest?.selector(referenceTypes, allowMsgid) != null

    private fun ChatHistoryResponse.Messages.hasUsableNewest(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
    ): Boolean = newest?.selector(referenceTypes, allowMsgid) != null

    private fun ChatHistoryResponse.Messages.cannotSafelyPageBefore(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
        requestLimit: Int,
        previous: BoundarySelector? = null,
    ): Boolean {
        if (isComplete) return false
        val next = oldest?.selector(referenceTypes, allowMsgid) ?: return true
        return next.value == previous?.value ||
            (next.type == HistoryReferenceType.TIMESTAMP && primaryMessageCount >= requestLimit)
    }

    private fun ChatHistoryResponse.Messages.cannotSafelyPageAfter(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
        requestLimit: Int,
        previous: BoundarySelector,
    ): Boolean {
        if (isComplete) return false
        val next = newest?.selector(referenceTypes, allowMsgid) ?: return true
        return next.value == previous.value ||
            (next.type == HistoryReferenceType.TIMESTAMP && primaryMessageCount >= requestLimit)
    }

    private fun ChatHistoryReference.selector(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
    ): BoundarySelector? {
        val exactMsgid = msgid
        val exactServerTime = serverTime
        return when {
            allowMsgid && HistoryReferenceType.MSGID in referenceTypes && !exactMsgid.isNullOrEmpty() ->
                BoundarySelector(ChatHistorySelectors.msgid(exactMsgid), HistoryReferenceType.MSGID)
            HistoryReferenceType.TIMESTAMP in referenceTypes && exactServerTime != null ->
                BoundarySelector(ChatHistorySelectors.timestamp(exactServerTime), HistoryReferenceType.TIMESTAMP)
            else -> null
        }
    }

    private data class BoundarySelector(
        val value: String,
        val type: HistoryReferenceType,
    )

    private companion object {
        private const val INVALID_MSGREFTYPE = "INVALID_MSGREFTYPE"

        // Mirrors HistoryResyncCoordinator.REQUEST_TIMEOUT_MS; both collapse onto this loader later.
        private const val REQUEST_TIMEOUT_MS = 35_000L
    }
}
