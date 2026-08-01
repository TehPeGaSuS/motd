package io.github.trevarj.motd.data.sync

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.HistoryCursorDao
import io.github.trevarj.motd.data.db.HistoryGapDao
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.HistoryWindowFocus
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.event.historyEventMetadataOrNull
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.service.ConnectionManager
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared network gate for reconnect discovery and Paging history requests. */
object CanonicalHistorySingleFlight {
    private val networkLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withNetwork(networkId: Long, block: suspend () -> T): T =
        networkLocks.getOrPut(networkId, ::Mutex).withLock { block() }
}

/**
 * CHATHISTORY-backed directional paging. The list is DESC (newest first): APPEND fetches older
 * messages via BEFORE, while a focused unread/deep-link island uses PREPEND + AFTER toward recent.
 *
 * REFRESH → if the buffer is empty and the network advertises chathistory, pull LATEST once.
 * APPEND  → older boundary; stop when historyComplete/no cap; when the buffer is empty (no oldest
 *           boundary yet) pull LATEST once to backfill on first open; otherwise BEFORE the oldest
 *           protocol page boundary. Completed empty pages and explicit end markers persist the
 *           confirmed start-of-history state through EventProcessor.
 *
 * We use SKIP_INITIAL_REFRESH so the cached DB paints instantly, which means Paging never calls
 * load(REFRESH) on open. On an empty store the local PagingSource yields an empty page and Paging
 * drives an APPEND past the end boundary — so the empty-buffer LATEST backfill lives in APPEND, not
 * only REFRESH, or a freshly-connected/cleared buffer would never fetch its recent history.
 */
@OptIn(ExperimentalPagingApi::class)
class ChatHistoryRemoteMediator(
    private val bufferId: Long,
    private val bufferDao: BufferDao,
    private val messageDao: MessageDao,
    private val processor: EventProcessor,
    private val history: HistorySource,
    private val pageSize: Int = 50,
    private val historyCursorDao: HistoryCursorDao? = null,
    private val historyGapDao: HistoryGapDao? = null,
    private val focus: HistoryWindowFocus = HistoryWindowFocus.Recent,
) : RemoteMediator<Int, MessageEntity>() {

    /**
     * Minimal seam over the live [io.github.trevarj.motd.irc.client.IrcClient] (mirrors
     * reconnect coordinator's history-source seam) so the load logic is unit-testable
     * against scripted responses without a socket. Resolved per-load so a client that connects after
     * the buffer opens is picked up on the next boundary hit.
     */
    interface HistorySource {
        suspend fun availability(): HistoryAvailability
        suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse
    }

    override suspend fun initialize(): InitializeAction =
        // Local cache is authoritative for the initial paint; only fetch on explicit boundary hit.
        InitializeAction.SKIP_INITIAL_REFRESH

    override suspend fun load(loadType: LoadType, state: PagingState<Int, MessageEntity>): MediatorResult {
        return locks.getOrPut(bufferId, ::Mutex).withLock {
            try {
                val buffer = bufferDao.observeById(bufferId)
                    ?: return MediatorResult.Success(endOfPaginationReached = true)
                val networkId = buffer.networkId
                val availability = history.availability()
                if (availability !is HistoryAvailability.Ready) {
                    return when (availability) {
                        HistoryAvailability.Unsupported -> MediatorResult.Success(endOfPaginationReached = true)
                        HistoryAvailability.NegotiatingOrOffline -> MediatorResult.Error(
                            IrcDisconnectedException("CHATHISTORY", "history is negotiating or offline"),
                        )
                        is HistoryAvailability.Ready -> error("unreachable")
                    }
                }
                val requestLimit = minOf(pageSize, availability.pageLimit).coerceAtLeast(1)
                CanonicalHistorySingleFlight.withNetwork(networkId) {
                    when (loadType) {
                        LoadType.REFRESH -> refresh(
                            networkId,
                            buffer.id,
                            buffer.ircTarget,
                            requestLimit,
                            availability.referenceTypes,
                        )
                        LoadType.PREPEND -> prepend(
                            networkId,
                            buffer.id,
                            buffer.ircTarget,
                            requestLimit,
                            availability.referenceTypes,
                        )
                        LoadType.APPEND -> append(
                            networkId,
                            buffer.id,
                            buffer.ircTarget,
                            buffer.historyComplete,
                            requestLimit,
                            availability.referenceTypes,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                MediatorResult.Error(e)
            }
        }
    }

    private suspend fun refresh(
        networkId: Long,
        roomId: Long,
        target: String,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
    ): MediatorResult {
        val newest = messageDao.newestTime(roomId)
        if (newest != null) {
            // Already have local history; the local PagingSource paints it. APPEND drives older.
            return MediatorResult.Success(endOfPaginationReached = false)
        }
        val response = fetchLatest(networkId, target, requestLimit, referenceTypes)
        return MediatorResult.Success(
            endOfPaginationReached = response.isComplete ||
                response.cannotSafelyPageBefore(referenceTypes, true, requestLimit),
        )
    }

    private suspend fun append(
        networkId: Long,
        roomId: Long,
        target: String,
        historyComplete: Boolean,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
    ): MediatorResult {
        val gaps = historyGapDao?.forRoom(roomId).orEmpty()
        val focusedGap = focusedOlderGap(gaps)
        if (focusedGap?.recoverable == false) {
            return MediatorResult.Success(endOfPaginationReached = true)
        }
        if (historyComplete && focusedGap == null) {
            return MediatorResult.Success(endOfPaginationReached = true)
        }
        val cursor = historyCursorDao?.byRoom(roomId)
        val oldest = focusedGap?.let { ChatHistoryReference(it.newerMsgid, it.newerServerTime) }
            ?: cursor?.let { ChatHistoryReference(it.oldestMsgid, it.oldestServerTime) }
            ?.takeIf { it.msgid != null || it.serverTime != null }
            ?: messageDao.oldestBoundary(roomId)?.let {
                ChatHistoryReference(it.msgid, it.serverTime)
            }
        if (oldest == null) {
            // Empty local store hit the end boundary on first open. With SKIP_INITIAL_REFRESH the
            // REFRESH backfill never fires, so seed the newest page here via LATEST. If the server
            // has history the inserted rows re-run the PagingSource; a later APPEND then pages older.
            val response = fetchLatest(networkId, target, requestLimit, referenceTypes)
            return MediatorResult.Success(
                endOfPaginationReached = response.isComplete ||
                    response.cannotSafelyPageBefore(referenceTypes, true, requestLimit),
            )
        }
        val selected = oldest.selector(referenceTypes, allowMsgid = true)
            ?: return MediatorResult.Error(
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
            messages(request)
        } catch (error: IrcCommandException) {
            if (selected.type != HistoryReferenceType.MSGID || error.code != INVALID_MSGREFTYPE) {
                throw error
            }
            val timestamp = oldest.selector(referenceTypes, allowMsgid = false) ?: throw error
            request = request.copy(bound1 = timestamp.value)
            responseMsgidAllowed = false
            messages(request)
        }
        if (
            !result.isComplete &&
            !result.hasUsableOldest(referenceTypes, responseMsgidAllowed)
        ) {
            return MediatorResult.Error(
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
            expectedRoomId = bufferId,
            historyGapId = focusedGap?.id,
        )
        if (result.isComplete) return MediatorResult.Success(endOfPaginationReached = true)
        if (
            result.cannotSafelyPageBefore(
                referenceTypes,
                responseMsgidAllowed,
                requestLimit,
                previous = selected,
            )
        ) {
            // A non-advancing cursor would refetch forever. A saturated timestamp-only page is
            // also ambiguous because BEFORE would skip any additional messages sharing its oldest
            // timestamp. Preserve the page, leave historyComplete false, and stop this mediator.
            return MediatorResult.Success(endOfPaginationReached = true)
        }
        return MediatorResult.Success(endOfPaginationReached = false)
    }

    /** Grow an unread/deep-link segment toward the recent window. */
    private suspend fun prepend(
        networkId: Long,
        roomId: Long,
        target: String,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
    ): MediatorResult {
        val gap = focusedNewerGap(historyGapDao?.forRoom(roomId).orEmpty())
            ?: return MediatorResult.Success(endOfPaginationReached = true)
        if (!gap.recoverable) return MediatorResult.Success(endOfPaginationReached = true)
        val boundary = ChatHistoryReference(gap.olderMsgid, gap.olderServerTime)
        val selected = boundary.selector(referenceTypes, allowMsgid = true)
            ?: return MediatorResult.Error(
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
            messages(request)
        } catch (error: IrcCommandException) {
            if (selected.type != HistoryReferenceType.MSGID || error.code != INVALID_MSGREFTYPE) {
                throw error
            }
            val timestamp = boundary.selector(referenceTypes, allowMsgid = false) ?: throw error
            request = request.copy(bound1 = timestamp.value)
            responseMsgidAllowed = false
            messages(request)
        }
        if (
            !result.isComplete &&
            !result.hasUsableNewest(referenceTypes, responseMsgidAllowed)
        ) {
            return MediatorResult.Error(
                IllegalStateException("CHATHISTORY AFTER returned no advertised primary-message boundary"),
            )
        }
        processor.persistHistoryPageResult(
            networkId,
            request,
            result.withAdvertisedBoundaries(referenceTypes, responseMsgidAllowed),
            expectedRoomId = bufferId,
            historyGapId = gap.id,
        )
        val remaining = focusedNewerGap(historyGapDao?.forRoom(roomId).orEmpty())
        if (
            remaining != null &&
            result.cannotSafelyPageAfter(
                referenceTypes,
                responseMsgidAllowed,
                requestLimit,
                previous = selected,
            )
        ) {
            return MediatorResult.Success(endOfPaginationReached = true)
        }
        return MediatorResult.Success(
            endOfPaginationReached = remaining == null || !remaining.recoverable || result.isComplete,
        )
    }

    /** Pull the most recent page for [target] and persist it through the sole IRC→Room writer. */
    private suspend fun fetchLatest(
        networkId: Long,
        target: String,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
    ): ChatHistoryResponse.Messages {
        val request = ChatHistoryRequest(
            ChatHistoryRequest.Subcommand.LATEST,
            target,
            limit = requestLimit,
        )
        val result = messages(request)
        if (!result.isComplete && !result.hasUsableOldest(referenceTypes, true)) {
            error("CHATHISTORY LATEST returned no advertised primary-message boundary")
        }
        processor.persistHistoryPage(
            networkId,
            request,
            result.withAdvertisedBoundaries(
                referenceTypes,
                allowMsgid = HistoryReferenceType.MSGID in referenceTypes,
            ),
            expectedRoomId = bufferId,
        )
        return result
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

    private suspend fun messages(request: ChatHistoryRequest): ChatHistoryResponse.Messages =
        (history.chathistory(request) as? ChatHistoryResponse.Messages)
            ?.boundedToRequest(request)
            ?: error("CHATHISTORY ${request.subcommand} returned a TARGETS response")

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

    private suspend fun focusedOlderGap(gaps: List<HistoryGapEntity>): HistoryGapEntity? {
        val resolved = gaps.map { it to gapNewerAnchor(it) }
        return when (val current = focus) {
            HistoryWindowFocus.Recent,
            HistoryWindowFocus.RecentPaging,
            -> resolved.maxByOrNull { it.second }?.first
            is HistoryWindowFocus.Around -> resolved
                .filter { it.second <= current.anchor }
                .maxByOrNull { it.second }
                ?.first
        }
    }

    private suspend fun focusedNewerGap(gaps: List<HistoryGapEntity>): HistoryGapEntity? {
        if (focus !is HistoryWindowFocus.Around) return null
        val anchor = (focus as HistoryWindowFocus.Around).anchor
        return gaps.map { it to gapOlderAnchor(it) }
            .filter { it.second >= anchor }
            .minByOrNull { it.second }
            ?.first
    }

    private suspend fun gapOlderAnchor(gap: HistoryGapEntity) = gap.olderMsgid
        ?.let { messageDao.byMsgid(bufferId, it) }
        ?.let { io.github.trevarj.motd.data.db.TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        ?: gap.olderEventId?.let { id ->
            messageDao.byCanonicalId(id)?.takeIf { it.bufferId == bufferId }
                ?.let { io.github.trevarj.motd.data.db.TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        }
        ?: gap.olderEventId?.let {
            io.github.trevarj.motd.data.db.TimelineAnchor(
                gap.olderServerTime,
                it,
                gap.olderTimelineOrder ?: it,
            )
        }
        ?: io.github.trevarj.motd.data.db.TimelineAnchor(
            gap.olderServerTime,
            Long.MIN_VALUE,
            Long.MIN_VALUE,
        )

    private suspend fun gapNewerAnchor(gap: HistoryGapEntity) = gap.newerMsgid
        ?.let { messageDao.byMsgid(bufferId, it) }
        ?.let { io.github.trevarj.motd.data.db.TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        ?: gap.newerEventId?.let { id ->
            messageDao.byCanonicalId(id)?.takeIf { it.bufferId == bufferId }
                ?.let { io.github.trevarj.motd.data.db.TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        }
        ?: gap.newerEventId?.let {
            io.github.trevarj.motd.data.db.TimelineAnchor(
                gap.newerServerTime,
                it,
                gap.newerTimelineOrder ?: it,
            )
        }
        ?: io.github.trevarj.motd.data.db.TimelineAnchor(
            gap.newerServerTime,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
        )

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

    companion object {
        private const val INVALID_MSGREFTYPE = "INVALID_MSGREFTYPE"
        private val locks = ConcurrentHashMap<Long, Mutex>()
    }
}

/** Enforce the client-requested primary bound even when a server over-delivers a batch. */
internal fun ChatHistoryResponse.Messages.boundedToRequest(
    request: ChatHistoryRequest,
    preferredAroundMsgid: String? = null,
): ChatHistoryResponse.Messages {
    if (primaryMessageCount <= request.limit) return this
    val primaryIndices = events.indices.filter { index ->
        events[index].historyEventMetadataOrNull()?.isContext != true
    }
    if (primaryIndices.size <= request.limit) return this
    val selectedIndices = when (request.subcommand) {
        ChatHistoryRequest.Subcommand.AFTER,
        ChatHistoryRequest.Subcommand.BETWEEN,
        -> primaryIndices.take(request.limit)
        ChatHistoryRequest.Subcommand.BEFORE,
        ChatHistoryRequest.Subcommand.LATEST,
        -> primaryIndices.takeLast(request.limit)
        ChatHistoryRequest.Subcommand.AROUND -> {
            val preferredPosition = preferredAroundMsgid?.let { preferred ->
                primaryIndices.indexOfFirst { index ->
                    events[index].historyEventMetadataOrNull()?.msgid == preferred
                }.takeIf { it >= 0 }
            }
            val targetPosition = preferredPosition ?: primaryIndices.indexOfFirst { index ->
                val metadata = events[index].historyEventMetadataOrNull()
                request.bound1 == metadata?.msgid?.let(ChatHistorySelectors::msgid) ||
                    metadata?.serverTime?.let(ChatHistorySelectors::timestamp) == request.bound1
            }
            check(targetPosition >= 0) {
                "CHATHISTORY AROUND over-delivered without the requested retained boundary"
            }
            val start = (targetPosition - request.limit / 2)
                .coerceIn(0, primaryIndices.size - request.limit)
            primaryIndices.subList(start, start + request.limit)
        }
        ChatHistoryRequest.Subcommand.TARGETS -> return this
    }
    val selected = selectedIndices.toSet()
    val retained = events.filterIndexed { index, event ->
        index in selected || event.historyEventMetadataOrNull()?.isContext == true
    }
    val references = selected.sorted().mapNotNull { index ->
        events[index].historyEventMetadataOrNull()?.let { metadata ->
            ChatHistoryReference(metadata.msgid, metadata.serverTime)
        }
    }
    fun ChatHistoryReference?.usable(): Boolean =
        this != null && (!msgid.isNullOrEmpty() || serverTime != null)
    val oldest = references.firstOrNull()
    val newest = references.lastOrNull()
    val hasRequiredContinuation = when (request.subcommand) {
        ChatHistoryRequest.Subcommand.AFTER,
        ChatHistoryRequest.Subcommand.BETWEEN,
        -> newest.usable()
        ChatHistoryRequest.Subcommand.BEFORE,
        ChatHistoryRequest.Subcommand.LATEST,
        -> oldest.usable()
        ChatHistoryRequest.Subcommand.AROUND -> oldest.usable() && newest.usable()
        ChatHistoryRequest.Subcommand.TARGETS -> true
    }
    check(hasRequiredContinuation) {
        "CHATHISTORY ${request.subcommand} over-delivered without a usable retained boundary"
    }
    return copy(
        events = retained,
        oldest = oldest,
        newest = newest,
        // The server may have reached its true boundary, but this client deliberately discarded
        // primary rows outside the requested window. Persist the retained page as non-terminal so
        // its oldest/newest cursor remains a durable route back to the omitted interval.
        endOfHistory = false,
        primaryMessageCount = selected.size,
    )
}

/**
 * Real mediator factory wired into [io.github.trevarj.motd.data.repo.MessageRepositoryImpl] via
 * the frozen [ChatHistoryMediatorFactory] contract; WP10 rebinds this over the WP1 no-op stub.
 */
@OptIn(ExperimentalPagingApi::class)
@Singleton
class ChatHistoryMediatorFactoryImpl @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val bufferDao: BufferDao,
    private val messageDao: MessageDao,
    private val processor: EventProcessor,
    private val historyCursorDao: HistoryCursorDao,
    private val historyGapDao: HistoryGapDao,
) : ChatHistoryMediatorFactory {
    override fun create(bufferId: Long): RemoteMediator<Int, MessageEntity> =
        ChatHistoryRemoteMediator(
            bufferId,
            bufferDao,
            messageDao,
            processor,
            historyFor(bufferId),
            historyCursorDao = historyCursorDao,
            historyGapDao = historyGapDao,
        )

    override fun create(
        bufferId: Long,
        focus: HistoryWindowFocus,
    ): RemoteMediator<Int, MessageEntity> =
        ChatHistoryRemoteMediator(
            bufferId,
            bufferDao,
            messageDao,
            processor,
            historyFor(bufferId),
            historyCursorDao = historyCursorDao,
            historyGapDao = historyGapDao,
            focus = focus,
        )

    // Resolve the live client lazily per call: the buffer can open before its network reaches
    // Ready, and clientFor(...) is only stable once connected. Missing/negotiating clients remain
    // retryable rather than masquerading as unsupported or a completed empty history response.
    private fun historyFor(bufferId: Long): ChatHistoryRemoteMediator.HistorySource =
        object : ChatHistoryRemoteMediator.HistorySource {
            private suspend fun client() =
                bufferDao.observeById(bufferId)?.networkId?.let { connectionManager.clientFor(it) }

            override suspend fun availability(): HistoryAvailability =
                client()?.historyAvailability ?: HistoryAvailability.NegotiatingOrOffline

            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse =
                client()?.chathistory(req) ?: throw IrcDisconnectedException("CHATHISTORY", null)
        }
}
