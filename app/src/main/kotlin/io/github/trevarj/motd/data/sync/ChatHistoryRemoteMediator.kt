package io.github.trevarj.motd.data.sync

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.BufferType
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
import io.github.trevarj.motd.irc.event.historyEventMetadataOrNull
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.service.ConnectionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

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
 * Every entry uses SKIP_INITIAL_REFRESH so the cached DB paints without network I/O; Paging3 then
 * drives REFRESH (empty-store LATEST seed, otherwise no-op) and scroll-triggered APPEND for older
 * history. Under Recent focus, PREPEND ends immediately because live events supply newer messages.
 * The loader owns availability, page-limit derivation, and all fetch concurrency.
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
    // Owns the fetch/persist/concurrency primitives. Defaulted so the existing positional test
    // construction stays valid; production always injects the shared singleton via the factory.
    private val loader: HistoryPageLoader = HistoryPageLoader(processor),
) : RemoteMediator<Int, MessageEntity>() {

    /**
     * Minimal seam over the live [io.github.trevarj.motd.irc.client.IrcClient] (mirrors
     * reconnect coordinator's history-source seam) so the load logic is unit-testable
     * against scripted responses without a socket. Resolved per-load so a client that connects after
     * the buffer opens is picked up on the next boundary hit. Shares [HistoryPageLoader]'s seam so a
     * scripted source can drive both directly.
     */
    interface HistorySource : HistoryPageLoader.HistorySource {
        override suspend fun availability(): HistoryAvailability
        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse
    }

    override suspend fun initialize(): InitializeAction =
        // Local cache is authoritative for normal entry and deep-link initial paint; the Around page
        // is pre-fetched by ChatJumpResolver. Paging drives REFRESH/APPEND explicitly afterward, and
        // the loader owns availability + concurrency for each fetch it performs.
        InitializeAction.SKIP_INITIAL_REFRESH

    override suspend fun load(loadType: LoadType, state: PagingState<Int, MessageEntity>): MediatorResult {
        return try {
            val buffer = bufferDao.observeById(bufferId)
                ?: return MediatorResult.Success(endOfPaginationReached = true)
            if (buffer.type == BufferType.SERVER) {
                // Console buffers have no CHATHISTORY target. With the mediator attached
                // unconditionally, mirror the UI's Hidden rule here or every console open would
                // emit junk `CHATHISTORY BEFORE <servername>` traffic.
                return MediatorResult.Success(endOfPaginationReached = true)
            }
            val networkId = buffer.networkId
            // The loader re-derives availability, page limit, and reference types from the source per
            // fetch and owns all wire serialization/coalescing, so no upfront availability gate or
            // per-buffer lock is needed here.
            when (loadType) {
                LoadType.REFRESH -> refresh(networkId, buffer.id, buffer.ircTarget)
                LoadType.PREPEND -> prepend(networkId, buffer.id, buffer.ircTarget)
                LoadType.APPEND -> append(
                    networkId,
                    buffer.id,
                    buffer.ircTarget,
                    buffer.historyComplete,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            MediatorResult.Error(e)
        }
    }

    private suspend fun refresh(
        networkId: Long,
        roomId: Long,
        target: String,
    ): MediatorResult {
        val newest = messageDao.newestTime(roomId)
        if (newest != null) {
            // Already have local history; the local PagingSource paints it. APPEND drives older.
            return MediatorResult.Success(endOfPaginationReached = false)
        }
        return loader.loadPage(
            networkId,
            roomId,
            target,
            HistoryPageLoader.Direction.LATEST,
            history,
            pageSize,
        ).toMediatorResult()
    }

    private suspend fun append(
        networkId: Long,
        roomId: Long,
        target: String,
        historyComplete: Boolean,
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
            return loader.loadPage(
                networkId,
                roomId,
                target,
                HistoryPageLoader.Direction.LATEST,
                history,
                pageSize,
            ).toMediatorResult()
        }
        return loader.loadPage(
            networkId,
            roomId,
            target,
            HistoryPageLoader.Direction.OLDER,
            history,
            pageSize,
            gapId = focusedGap?.id,
            boundary = oldest,
        ).toMediatorResult()
    }

    /** Grow an unread/deep-link segment toward the recent window. */
    private suspend fun prepend(
        networkId: Long,
        roomId: Long,
        target: String,
    ): MediatorResult {
        val gap = focusedNewerGap(historyGapDao?.forRoom(roomId).orEmpty())
            ?: return MediatorResult.Success(endOfPaginationReached = true)
        if (!gap.recoverable) return MediatorResult.Success(endOfPaginationReached = true)
        val boundary = ChatHistoryReference(gap.olderMsgid, gap.olderServerTime)
        return when (
            val page = loader.loadPage(
                networkId,
                roomId,
                target,
                HistoryPageLoader.Direction.NEWER,
                history,
                pageSize,
                gapId = gap.id,
                boundary = boundary,
            )
        ) {
            is HistoryPageLoader.PageResult.Loaded -> {
                // The focused newer gap shrank as this page was persisted; re-read it to decide
                // whether a recoverable remainder still justifies another PREPEND.
                val remaining = focusedNewerGap(historyGapDao?.forRoom(roomId).orEmpty())
                MediatorResult.Success(
                    endOfPaginationReached = remaining == null ||
                        !remaining.recoverable ||
                        page.endOfDirection,
                )
            }
            HistoryPageLoader.PageResult.Unsupported -> MediatorResult.Success(endOfPaginationReached = true)
            is HistoryPageLoader.PageResult.Unavailable -> MediatorResult.Error(page.cause)
            is HistoryPageLoader.PageResult.Failed -> MediatorResult.Error(page.cause)
        }
    }

    /** Map a loader outcome onto this direction's Paging result. */
    private fun HistoryPageLoader.PageResult.toMediatorResult(): MediatorResult = when (this) {
        is HistoryPageLoader.PageResult.Loaded ->
            MediatorResult.Success(endOfPaginationReached = endOfDirection)
        HistoryPageLoader.PageResult.Unsupported ->
            MediatorResult.Success(endOfPaginationReached = true)
        is HistoryPageLoader.PageResult.Unavailable -> MediatorResult.Error(cause)
        is HistoryPageLoader.PageResult.Failed -> MediatorResult.Error(cause)
    }

    private suspend fun focusedOlderGap(gaps: List<HistoryGapEntity>): HistoryGapEntity? {
        val resolved = gaps.map { it to gapNewerAnchor(it) }
        return when (val current = focus) {
            HistoryWindowFocus.Recent -> resolved.maxByOrNull { it.second }?.first
            is HistoryWindowFocus.Around -> resolved
                .filter { it.second <= current.anchor }
                .maxByOrNull { it.second }
                ?.first
        }
    }

    private suspend fun focusedNewerGap(gaps: List<HistoryGapEntity>): HistoryGapEntity? {
        if (focus !is HistoryWindowFocus.Around) return null
        val anchor = focus.anchor
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
    private val loader: HistoryPageLoader,
    private val historyCursorDao: HistoryCursorDao,
    private val historyGapDao: HistoryGapDao,
) : ChatHistoryMediatorFactory {
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
            loader = loader,
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
