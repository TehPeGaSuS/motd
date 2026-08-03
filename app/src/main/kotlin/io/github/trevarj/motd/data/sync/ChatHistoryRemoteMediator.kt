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
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.HistoryGapDao
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.history.GapAnchorResolver
import io.github.trevarj.motd.data.history.PageProgress
import io.github.trevarj.motd.data.history.Pageability
import io.github.trevarj.motd.data.history.focusedNewerGap
import io.github.trevarj.motd.data.history.focusedOlderGap
import io.github.trevarj.motd.data.history.newerPageability
import io.github.trevarj.motd.data.history.olderPageability
import io.github.trevarj.motd.data.history.openGapFloor
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.HistoryWindowFocus
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
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
 *           confirmed start-of-history state through EventProcessor. Under Recent focus this is the
 *           bottom-of-timeline ladder only — interior history gaps belong to
 *           [HistoryGapFillCoordinator], and the request is clamped strictly below every open gap so
 *           the two can never name the same interval; see [appendFocusedGap] and [appendGapFloor].
 *
 * Paging treats `endOfPaginationReached` as PERMANENT for a direction, so both directional loads
 * report it only when paging is genuinely finished (gap closed/unrecoverable, history complete, or a
 * page that made no progress) — never merely because the loader had to stop at one ambiguous
 * equal-timestamp page edge. See [appendResult].
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
    // Opt-in decision-point journal for the paging control flow. Fields carry classification, ids,
    // counts, timestamps, and msgid PRESENCE only — never message content or msgid values. This is
    // the observability that identified the unrecoverable-gap append stall on timestamp-only wires.
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
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

    // Resolves stored gap edges against the local store so focus selection ranks gaps by real
    // timeline positions. Shared with the repository's window geometry, which projects the SAME
    // edges through the opposite (non-clamping) role — see GapEdgeAnchor.
    private val gapAnchors = GapAnchorResolver(messageDao)

    override suspend fun initialize(): InitializeAction =
        // Local cache is authoritative for normal entry and deep-link initial paint; the Around page
        // is pre-fetched by ChatJumpResolver. Paging drives REFRESH/APPEND explicitly afterward, and
        // the loader owns availability + concurrency for each fetch it performs.
        InitializeAction.SKIP_INITIAL_REFRESH

    override suspend fun load(loadType: LoadType, state: PagingState<Int, MessageEntity>): MediatorResult {
        return try {
            val buffer = bufferDao.observeById(bufferId)
                ?: return endLoad(loadType, "missing_buffer")
            if (buffer.type == BufferType.SERVER) {
                // Console buffers have no CHATHISTORY target. With the mediator attached
                // unconditionally, mirror the UI's Hidden rule here or every console open would
                // emit junk `CHATHISTORY BEFORE <servername>` traffic.
                return endLoad(loadType, "server_buffer")
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
            }.also { result ->
                diagnostics.record("chat_history", "mediator_load_result") {
                    mapOf(
                        "load_type" to loadType.name,
                        "room_id" to bufferId,
                        "outcome" to when (result) {
                            is MediatorResult.Success ->
                                if (result.endOfPaginationReached) "end" else "more"
                            is MediatorResult.Error -> "error"
                            else -> "unknown"
                        },
                        "error_class" to (result as? MediatorResult.Error)
                            ?.throwable?.let { it::class.simpleName },
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            diagnostics.record("chat_history", "mediator_load_failed") {
                mapOf(
                    "load_type" to loadType.name,
                    "room_id" to bufferId,
                    "error_class" to e::class.simpleName,
                )
            }
            MediatorResult.Error(e)
        }
    }

    /**
     * End pagination locally with the decision recorded; [reason] is a fixed classification. The
     * field is named `end_reason` because DiagnosticLogger redacts any field literally named
     * `reason` (IRC quit/kick reasons are user content; this classification is not).
     */
    private fun endLoad(
        loadType: LoadType,
        reason: String,
        extra: Map<String, Any?> = emptyMap(),
    ): MediatorResult {
        diagnostics.record("chat_history", "mediator_load_ended") {
            mapOf("load_type" to loadType.name, "room_id" to bufferId, "end_reason" to reason) + extra
        }
        return MediatorResult.Success(endOfPaginationReached = true)
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
        val focusedGap = appendFocusedGap(roomId, gaps)
        val cursor = historyCursorDao?.byRoom(roomId)
        val pageability = olderPageability(
            focusedGap = focusedGap,
            historyComplete = historyComplete,
            cursorOldest = cursor?.let { ChatHistoryReference(it.oldestMsgid, it.oldestServerTime) },
            oldestLocalRow = messageDao.oldestBoundary(roomId)
                ?.let { ChatHistoryReference(it.msgid, it.serverTime) },
            progress = null,
            gapFloor = appendGapFloor(gaps),
        )
        return when (pageability) {
            is Pageability.End -> endLoad(LoadType.APPEND, pageability.reason)
            Pageability.SeedLatest -> {
                recordAppendBoundary(roomId, gaps, focusedGap, cursor, boundary = null)
                // Empty local store hit the end boundary on first open. With SKIP_INITIAL_REFRESH the
                // REFRESH backfill never fires, so seed the newest page here via LATEST. If the server
                // has history the inserted rows re-run the PagingSource; a later APPEND pages older.
                loader.loadPage(
                    networkId,
                    roomId,
                    target,
                    HistoryPageLoader.Direction.LATEST,
                    history,
                    pageSize,
                ).appendResult(roomId, previous = null)
            }
            is Pageability.Page -> {
                recordAppendBoundary(roomId, gaps, focusedGap, cursor, pageability.boundary)
                loader.loadPage(
                    networkId,
                    roomId,
                    target,
                    HistoryPageLoader.Direction.OLDER,
                    history,
                    pageSize,
                    gapId = pageability.focusedGapId,
                    boundary = pageability.boundary,
                ).appendResult(roomId, previous = pageability.boundary)
            }
        }
    }

    /**
     * The gap older paging is working on, or null when APPEND is not gap-directed.
     *
     * Recent is deliberately NOT gap-directed. Its window is unbounded, so the local PagingSource
     * only runs dry at the true oldest retained row — never at an interior seam — and the APPEND
     * Paging asks for is therefore always a request for backlog BELOW the bottom of the timeline.
     * Aiming it at a gap made it answer a question nobody asked, and one consequence was a real
     * defect: an unrecoverable gap anywhere in the room reported the whole direction permanently
     * finished, so scrolling to the bottom of the list could never fetch another page. Interior
     * seams are owned by [HistoryGapFillCoordinator], which is driven by taps and by the autopilot
     * rather than by Paging running out of rows.
     *
     * [HistoryWindowFocus.Around] keeps the gap direction: that window IS clamped at a gap, so
     * running out of local rows there really does mean "the gap below this island".
     */
    private suspend fun appendFocusedGap(
        roomId: Long,
        gaps: List<HistoryGapEntity>,
    ): HistoryGapEntity? = when (focus) {
        HistoryWindowFocus.Recent -> null
        is HistoryWindowFocus.Around -> focusedOlderGap(focus, gapAnchors.resolve(roomId, gaps))?.gap
    }

    /**
     * The floor that keeps this APPEND out of the coordinator's territory, or null when it has none.
     *
     * The split between the two demand sources has to be STRUCTURAL, not incidental, because on an
     * unbounded timeline the two ladders otherwise coincide at open: the coordinator pages BEFORE the
     * gap's newer edge, and the mediator's own ladder can arrive at exactly that reference (a
     * reconnect LATEST page unions its oldest row into the stored cursor, and that row IS the gap's
     * newer edge). Two fetches, one interval, one of them guaranteed to insert nothing.
     *
     * The rule is a partition of the timeline rather than an ordering: **the coordinator owns every
     * interval an open gap covers, and the mediator owns everything strictly below all of them.**
     * [openGapFloor] supplies the boundary that expresses it. Around focus is exempt because it is
     * already gap-directed — it IS the coordinator's counterpart for a clamped island, not a
     * competitor for the same interval.
     */
    private fun appendGapFloor(gaps: List<HistoryGapEntity>): ChatHistoryReference? = when (focus) {
        HistoryWindowFocus.Recent -> openGapFloor(gaps)
        is HistoryWindowFocus.Around -> null
    }

    /** The APPEND decision point: which gap was selected and which boundary the request carries. */
    private fun recordAppendBoundary(
        roomId: Long,
        gaps: List<HistoryGapEntity>,
        focusedGap: HistoryGapEntity?,
        cursor: HistoryCursorEntity?,
        boundary: ChatHistoryReference?,
    ) {
        diagnostics.record("chat_history", "append_boundary") {
            mapOf(
                "room_id" to roomId,
                "gap_count" to gaps.size,
                "focused_gap_id" to focusedGap?.id,
                "focused_gap_recoverable" to focusedGap?.recoverable,
                "has_cursor" to (cursor != null),
                "boundary_has_msgid" to (boundary?.msgid != null),
                "boundary_server_time" to boundary?.serverTime,
            )
        }
    }

    /**
     * Decide APPEND terminality from PROGRESS rather than from the loader's per-page cursor guard.
     *
     * [HistoryPageLoader.PageResult.Loaded.endOfDirection] conflates two different facts: "this
     * direction is exhausted" and "I cannot safely page again from THIS cursor" (an ambiguous
     * equal-timestamp boundary at a saturated page edge). Paging treats `endOfPaginationReached` as
     * permanently terminal for the direction, so reporting the second fact kills older backfill after
     * a single page on a timestamp-only wire (soju advertises `MSGREFTYPES=timestamp`), where every
     * saturated page trips it. Terminate only when older paging is genuinely finished:
     *  - the focused older gap became server-proven unrecoverable (Around focus only — Recent has no
     *    focused gap, so an unrecoverable seam elsewhere in the room can no longer end this
     *    direction), or
     *  - history is complete and no focused gap remains, or
     *  - the page made no progress at all.
     * Otherwise the boundary moved (or rows landed), so the next APPEND issues a different request
     * and the ambiguity that stopped this page no longer applies.
     */
    private suspend fun HistoryPageLoader.PageResult.appendResult(
        roomId: Long,
        previous: ChatHistoryReference?,
    ): MediatorResult {
        val page = this as? HistoryPageLoader.PageResult.Loaded ?: return toMediatorResult()
        // Re-read AFTER the persist, and deliberately so: this page may have shrunk or closed the
        // focused gap, receded the cursor, or proven history complete, and every one of those facts
        // is an input to terminality. The decision itself is pure, so the reads stay here in the open.
        val gaps = historyGapDao?.forRoom(roomId).orEmpty()
        val remaining = appendFocusedGap(roomId, gaps)
        val gapFloor = appendGapFloor(gaps)
        val historyComplete = bufferDao.observeById(roomId)?.historyComplete == true
        val cursorOldest = historyCursorDao?.byRoom(roomId)
            ?.let { ChatHistoryReference(it.oldestMsgid, it.oldestServerTime) }
        val oldestLocalRow = messageDao.oldestBoundary(roomId)
            ?.let { ChatHistoryReference(it.msgid, it.serverTime) }
        // Two questions off the one post-page snapshot: where the NEXT request would go, and whether
        // this page earned one. Only the second is progress-aware; the first supplies the boundary
        // the anti-livelock diagnostic reports.
        val ladder =
            olderPageability(remaining, historyComplete, cursorOldest, oldestLocalRow, null, gapFloor)
        val verdict = olderPageability(
            remaining,
            historyComplete,
            cursorOldest,
            oldestLocalRow,
            PageProgress(previous = previous, insertedCount = page.insertedCount),
            gapFloor,
        )
        return verdict.toMediatorResult(LoadType.APPEND, ladder, page)
    }

    /**
     * Map a post-page [Pageability] onto this direction's Paging result.
     *
     * Only the anti-livelock stop carries diagnostic extras, and it is the only end the boundary
     * [ladder] did not already reach on its own: a silent permanent stop is hard to diagnose in the
     * field, so record the page and the boundary that failed to move.
     */
    private fun Pageability.toMediatorResult(
        loadType: LoadType,
        ladder: Pageability,
        page: HistoryPageLoader.PageResult.Loaded,
    ): MediatorResult {
        if (this !is Pageability.End) return MediatorResult.Success(endOfPaginationReached = false)
        if (ladder is Pageability.End) return endLoad(loadType, reason)
        val next = ladder as? Pageability.Page
        return endLoad(
            loadType,
            reason,
            mapOf(
                "primary_count" to page.primaryCount,
                "end_of_direction" to page.endOfDirection,
                "focused_gap_id" to next?.focusedGapId,
                "boundary_has_msgid" to (next?.boundary?.msgid != null),
                "boundary_server_time" to next?.boundary?.serverTime,
            ),
        )
    }

    /** Grow an unread/deep-link segment toward the recent window. */
    private suspend fun prepend(
        networkId: Long,
        roomId: Long,
        target: String,
    ): MediatorResult {
        val focusedGap = focusedNewerGap(
            focus,
            gapAnchors.resolve(roomId, historyGapDao?.forRoom(roomId).orEmpty()),
        )?.gap
        // Newer paging never seeds, so anything but Page is terminal. These two pre-fetch terminals
        // (no gap under this focus, or one already proven empty) have never emitted a diagnostic, so
        // the End reason is deliberately dropped here rather than recorded.
        val start = newerPageability(focusedGap, progress = null) as? Pageability.Page
            ?: return MediatorResult.Success(endOfPaginationReached = true)
        val result = loader.loadPage(
            networkId,
            roomId,
            target,
            HistoryPageLoader.Direction.NEWER,
            history,
            pageSize,
            gapId = start.focusedGapId,
            boundary = start.boundary,
        )
        val page = result as? HistoryPageLoader.PageResult.Loaded ?: return result.toMediatorResult()
        // The focused newer gap shrank as this page was persisted; re-read it and apply the same
        // progress rule APPEND uses. A saturated timestamp-only catch-up page trips the loader's
        // cannotSafelyPageAfter guard, which says "not from this cursor", not "no newer history";
        // reporting it to Paging would permanently terminate PREPEND, leaving the reconnect gap open
        // and everything newer than it outside the Around window forever.
        val remaining = focusedNewerGap(
            focus,
            gapAnchors.resolve(roomId, historyGapDao?.forRoom(roomId).orEmpty()),
        )?.gap
        val ladder = newerPageability(remaining, progress = null)
        val verdict = newerPageability(
            remaining,
            PageProgress(previous = start.boundary, insertedCount = page.insertedCount),
        )
        return verdict.toMediatorResult(LoadType.PREPEND, ladder, page)
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
    private val diagnostics: DiagnosticLogger,
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
            diagnostics = diagnostics,
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
