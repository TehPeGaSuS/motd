package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryBackfillCursorEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.HistorySyncPrefs
import io.github.trevarj.motd.data.prefs.NoopHistorySyncPrefs
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.HistoryPageLoader
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.ChatHistoryTarget
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

sealed interface HistoryResyncState {
    data object Idle : HistoryResyncState
    data object WaitingForCapability : HistoryResyncState
    data class Running(val fetched: Int = 0, val limit: Int? = null) : HistoryResyncState
    data class Updated(val inserted: Int) : HistoryResyncState
    data object UpToDate : HistoryResyncState
    data object Unsupported : HistoryResyncState
    open class Failed(open val reason: String) : HistoryResyncState {
        open override fun equals(other: Any?): Boolean =
            other is Failed && javaClass == other.javaClass && reason == other.reason

        open override fun hashCode(): Int = reason.hashCode()

        open override fun toString(): String = "${javaClass.simpleName}(reason=$reason)"
    }
    data class Incomplete(
        val inserted: Int,
        override val reason: String,
        val awaitsTargetClassification: Boolean = false,
        val retryRecommended: Boolean = false,
    ) : Failed(reason)
    data class Capped(val inserted: Int, val limit: Int, override val reason: String) : Failed(reason)
}

/** Per-buffer progress and actionable failure state for automatic and user-requested history work. */
sealed interface HistorySyncStatus {
    /** Settled cleanly; the buffer carries no entry in the published map. */
    data object Idle : HistorySyncStatus
    /** Registered in the current pass, waiting for a fetch slot. */
    data object Queued : HistorySyncStatus
    /** A request for this buffer is on the wire right now. */
    data object Syncing : HistorySyncStatus
    /** The server permanently refuses this target (FAIL CHATHISTORY INVALID_TARGET). */
    data object Unavailable : HistorySyncStatus
    data class Partial(val reason: String) : HistorySyncStatus
    data class Failed(val reason: String) : HistorySyncStatus
}

/** Prevent a cancelled or superseded sync from publishing its initial transient status late. */
internal fun initialSyncStatusIfCurrent(
    current: Map<Long, HistorySyncStatus>,
    bufferId: Long,
    generation: Long,
    currentGeneration: Long?,
    status: HistorySyncStatus,
): Map<Long, HistorySyncStatus> = if (currentGeneration == generation) {
    current + (bufferId to status)
} else {
    current
}

private val EMPTY_SYNC_STATUSES: StateFlow<Map<Long, HistorySyncStatus>> = MutableStateFlow(emptyMap())

/** Chat-facing boundary for lifecycle-driven history reconciliation. */
interface HistoryResyncController {
    /**
     * Every buffer with a live or actionable history status. A buffer absent from the map is
     * settled, which is why [HistorySyncStatus.Idle] never appears as a value.
     */
    val syncStatuses: StateFlow<Map<Long, HistorySyncStatus>>
        get() = EMPTY_SYNC_STATUSES

    fun syncStatus(bufferId: Long): Flow<HistorySyncStatus> = syncStatuses
        .map { it[bufferId] ?: HistorySyncStatus.Idle }
        .distinctUntilChanged()

    suspend fun reconcileBuffer(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ): HistoryResyncState

    /**
     * Fetch the newest page without waiting behind network-wide discovery/backfill. This urgent
     * path promotes a just-sent local row before a reply or reaction needs its durable msgid.
     */
    suspend fun reconcilePendingMessage(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ): HistoryResyncState
}

/**
 * The sole reconnect/manual tail-revalidation entry point. The coordinator decides WHAT to fetch
 * (targets, ranges, ordering, gap recording, marker convergence) and what to report (states,
 * per-buffer sync status); every wire fetch goes through [HistoryPageLoader], whose bounded
 * per-network wire gate admits each individual CHATHISTORY request against scroll-driven Paging
 * (width 1 — strict serialization — unless labeled-response correlates concurrency). Equivalent
 * whole requests (a reconnect pass, a manual refresh) still coalesce onto one [ActiveFlight], but
 * only to back user-facing status and cancellation — not as a fetch lock: two concurrent
 * same-buffer LATEST fetches are safe because [EventProcessor] deduplicates rows by msgid/identity
 * and gap recording recognizes an already-recorded interval. IRC-derived rows still flow
 * exclusively through [EventProcessor].
 */
@Singleton
class HistoryResyncCoordinator @Inject constructor(
    private val db: MotdDatabase,
    private val processor: EventProcessor,
    private val syncPrefs: HistorySyncPrefs = NoopHistorySyncPrefs,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
    // The single wire-fetch primitive: every CHATHISTORY request the coordinator issues goes through
    // this shared singleton so reconnect/manual traversals share the loader's per-network wire gate
    // with scroll-driven Paging. Defaulted so tests keep the four-argument construction.
    private val loader: HistoryPageLoader = HistoryPageLoader(processor),
) : HistoryResyncController {
    // Reuses the loader's transport seam so a source can drive both the coordinator's orchestration
    // and the loader's fetch primitives directly, and adds the discovery/classification metadata the
    // reconnect pass needs (target normalization, channel detection, and a per-connection flight id).
    internal interface HistorySource : HistoryPageLoader.HistorySource {
        override suspend fun availability(): HistoryAvailability
        override suspend fun chathistory(request: ChatHistoryRequest): ChatHistoryResponse
        fun flightIdentity(): Any = this
        fun canClassifyTargets(): Boolean = true
        fun normalizeTarget(target: String): String = IrcIdentityRules().normalize(target)
        fun isChannelTarget(target: String): Boolean = IrcIdentityRules().isChannel(target)
    }

    private data class RequestKey(val networkId: Long, val bufferId: Long?)
    private data class RequestSpec(
        val key: RequestKey,
        val sourceIdentity: Any,
    )
    private data class ActiveFlight(
        val spec: RequestSpec,
        val deferred: Deferred<HistoryResyncState>,
    )
    private data class FlightRegistration(
        val flight: ActiveFlight,
        val ownsFlight: Boolean,
    )

    private sealed interface WorkStatus {
        data object Complete : WorkStatus
        data class Incomplete(
            val reason: String,
            val awaitsTargetClassification: Boolean = false,
        ) : WorkStatus
        data class Capped(val reason: String, val limit: Int) : WorkStatus
    }

    private data class WorkResult(
        val status: WorkStatus = WorkStatus.Complete,
        val highWater: Long? = null,
        val inserted: Int = 0,
    )

    private data class TargetPass(
        val inserted: Int,
        val status: WorkStatus,
        val highWater: Long?,
        val retryRecommended: Boolean,
    )

    /** One target's contribution to a pass; skips and refused targets contribute the neutral value. */
    private data class TargetOutcome(
        val inserted: Int = 0,
        val status: WorkStatus = WorkStatus.Complete,
        val highWater: Long? = null,
        val retryRecommended: Boolean = false,
    )

    private data class TargetDiscovery(
        val targets: List<ChatHistoryTarget>,
        val status: WorkStatus,
        val highWater: Long?,
    )

    private data class SyncTarget(
        val knownBufferId: Long?,
        val name: String,
        val latestMessageTime: Long?,
    )

    // Cancellation is non-suspending, so registration and removal share a synchronous monitor.
    private val activeGuard = Any()
    private val activeFlights = LinkedHashMap<RequestSpec, ActiveFlight>()
    private val _syncStatuses = MutableStateFlow<Map<Long, HistorySyncStatus>>(emptyMap())
    override val syncStatuses: StateFlow<Map<Long, HistorySyncStatus>> = _syncStatuses
    private val syncStatusGenerations = ConcurrentHashMap<Long, AtomicLong>()
    internal var requestTimeoutMs: Long = REQUEST_TIMEOUT_MS
    internal var targetsRequestLimit: Int = TARGETS_REQUEST_LIMIT

    /**
     * One pass's per-buffer status publication: registration, the generation guard that keeps a
     * cancelled or superseded pass from publishing late, and settlement. Work without a session
     * (the paced background backfill) publishes nothing at all.
     *
     * A labeled-response pass drives a bounded number of buffers concurrently, so within-pass
     * bookkeeping is monitor-guarded; the cross-pass race — a manual retry superseding a reconnect
     * pass, or the reverse — is still arbitrated by the per-buffer generation counter, not by this
     * bookkeeping.
     */
    private inner class SyncStatusSession {
        private val monitor = Any()
        // Registration order; an entry lives here until that buffer settles.
        private val generations = LinkedHashMap<Long, Long>()
        // Buffers whose requests are on the wire right now; each wears the whole-pass verdict.
        private val inFlight = LinkedHashSet<Long>()

        /** Register a buffer in this pass. Re-registration within one pass keeps the first turn. */
        fun queue(bufferId: Long) {
            synchronized(monitor) {
                if (generations.containsKey(bufferId)) return
                generations[bufferId] = beginSyncStatus(bufferId, HistorySyncStatus.Queued)
            }
        }

        /** This buffer's request is about to go on the wire. */
        fun syncing(bufferId: Long) {
            synchronized(monitor) {
                val generation = generations[bufferId] ?: return
                inFlight += bufferId
                publishSyncStatus(bufferId, generation, HistorySyncStatus.Syncing)
            }
        }

        /** Terminal for one buffer: [HistorySyncStatus.Idle] removes it, anything else persists. */
        fun settle(bufferId: Long, status: HistorySyncStatus) {
            synchronized(monitor) { settleLocked(bufferId, status) }
        }

        private fun settleLocked(bufferId: Long, status: HistorySyncStatus) {
            val generation = generations.remove(bufferId) ?: return
            inFlight -= bufferId
            finishSyncStatus(bufferId, generation, status)
        }

        /**
         * Pass end. Only buffers that actually had a request on the wire wear the pass verdict
         * (a cancelled sibling's request was genuinely in flight); every still-queued buffer is
         * simply dropped, because the catch-up retry loop re-runs the whole pass and painting a
         * whole-pass failure on untouched buffers would be a lie.
         */
        fun finish(result: HistoryResyncState) {
            val verdict = result.toSyncStatus()
            synchronized(monitor) {
                inFlight.toList().forEach { settleLocked(it, verdict) }
                generations.keys.toList().forEach { settleLocked(it, HistorySyncStatus.Idle) }
            }
        }
    }

    /**
     * Reconcile a visible chat. The request shares the exact same per-buffer single flight as the
     * reconnect network pass.
     */
    override suspend fun reconcileBuffer(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ): HistoryResyncState = reconcileBuffer(
        networkId = buffer.networkId,
        bufferId = buffer.id,
        target = buffer.ircTarget,
        source = ClientHistorySource(client),
        isCurrent = isCurrent,
    )

    override suspend fun reconcilePendingMessage(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ): HistoryResyncState = reconcilePendingMessage(
        networkId = buffer.networkId,
        bufferId = buffer.id,
        target = buffer.ircTarget,
        source = ClientHistorySource(client),
        isCurrent = isCurrent,
    )

    /**
     * A normal reconciliation owns the coarse per-network gate while it discovers targets and
     * repairs gaps. A user action that only needs the newest msgid must not queue behind that whole
     * pass. IrcClient still correlates labeled responses and serializes unlabeled CHATHISTORY at
     * the wire boundary, while EventProcessor remains the sole Room writer.
     */
    internal suspend fun reconcilePendingMessage(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean = { true },
    ): HistoryResyncState {
        val ready = when (val availability = source.availability()) {
            HistoryAvailability.Unsupported -> return HistoryResyncState.Unsupported
            HistoryAvailability.NegotiatingOrOffline -> return historyUnavailable()
            is HistoryAvailability.Ready -> availability
        }
        if (!isCurrent()) return staleConnection()
        val referenceTypes = ready.referenceTypes
        val msgidAllowed = HistoryReferenceType.MSGID in referenceTypes
        return try {
            val request = ChatHistoryRequest(
                subcommand = ChatHistoryRequest.Subcommand.LATEST,
                target = target,
                limit = ready.pageLimit.coerceAtMost(PAGE_LIMIT).coerceAtLeast(1),
            )
            // The loader admits this LATEST on the same per-network wire gate as every other
            // history fetch. Because a permit is held per wire request (never for a whole discovery
            // pass), an urgent pending promotion interleaves between a network resync's pages instead
            // of queuing behind the entire pass — the guarantee the old bespoke bypass provided.
            val latest = loader.fetchMessages(
                networkId,
                source,
                request,
                referenceTypes,
                msgidAllowed,
                timeoutMs = PENDING_MESSAGE_TIMEOUT_MS,
                allowConcurrent = ready.supportsConcurrentRequests,
            )
            if (!isCurrent()) return staleConnection()
            val inserted = ingest(networkId, bufferId, request, latest)
            if (
                !latest.isTerminalPage() &&
                !latest.hasUsableDirectionalBoundary(
                    ChatHistoryRequest.Subcommand.LATEST,
                    referenceTypes,
                )
            ) {
                HistoryResyncState.Incomplete(
                    inserted,
                    "CHATHISTORY LATEST returned no usable primary-message boundary",
                )
            } else if (inserted > 0) {
                HistoryResyncState.Updated(inserted)
            } else {
                HistoryResyncState.UpToDate
            }
        } catch (_: TimeoutCancellationException) {
            HistoryResyncState.Failed("Pending message history refresh timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleConnectionException) {
            staleConnection()
        } catch (error: Exception) {
            HistoryResyncState.Failed(
                error.message?.take(160) ?: "Pending message history refresh failed",
            )
        }
    }

    suspend fun resyncNetwork(
        networkId: Long,
        openBuffers: List<Pair<Long, String>>,
        client: IrcClient,
        isCurrent: () -> Boolean,
        // null means "everything": the first pass enumerates from epoch and leaves no backfill.
        initialLookbackMs: Long? = INITIAL_SYNC_LOOKBACK_MS,
    ): HistoryResyncState {
        if (!client.targetClassificationReady.value) {
            withTimeoutOrNull(TARGET_CLASSIFICATION_WAIT_TIMEOUT_MS) {
                client.targetClassificationReady.first { it }
            }
        }
        if (!isCurrent()) return staleConnection()
        return resyncNetwork(
            networkId,
            openBuffers,
            ClientHistorySource(client),
            isCurrent,
            initialLookbackMs,
        )
    }

    suspend fun backfillTargets(networkId: Long, client: IrcClient, isCurrent: () -> Boolean) {
        if (!client.targetClassificationReady.value) {
            withTimeoutOrNull(TARGET_CLASSIFICATION_WAIT_TIMEOUT_MS) {
                client.targetClassificationReady.first { it }
            } ?: return
        }
        if (!isCurrent()) return
        backfillTargets(networkId, ClientHistorySource(client), isCurrent)
    }

    /**
     * Paced background enumeration of targets older than the initial-sync window. Resumes from the
     * durable per-network cursor, seeds every discovered target with the same single newest page
     * the reconnect pass uses, and never touches the reconnect watermark or publishes per-buffer
     * status. A transport failure or a superseded connection simply leaves the cursor where it
     * last advanced; the next Ready session resumes from there.
     */
    internal suspend fun backfillTargets(
        networkId: Long,
        source: HistorySource,
        isCurrent: () -> Boolean,
    ) {
        val cursorDao = db.historyBackfillCursorDao()
        val cursor = cursorDao.byNetwork(networkId) ?: return
        if (cursor.complete) return
        if (cursor.upperBound <= Instant.EPOCH.toEpochMilli()) {
            cursorDao.markComplete(networkId)
            return
        }
        val ready = source.availability() as? HistoryAvailability.Ready ?: return
        if (!source.canClassifyTargets()) return
        diagnostics.record("history", "backfill_started") {
            mapOf("network_id" to networkId, "upper_bound" to cursor.upperBound)
        }
        val discovery = try {
            discoverTargets(
                networkId = networkId,
                source = source,
                upper = cursor.upperBound,
                lower = Instant.EPOCH.toEpochMilli(),
                onPageEnd = { page, nextUpper ->
                    // Seed before persisting the boundary: a killed process may re-enumerate a
                    // page (target dedup absorbs that) but can never skip one unseeded.
                    seedBackfillPage(networkId, page, source, isCurrent)
                    cursorDao.advance(networkId, nextUpper)
                },
                betweenPages = {
                    if (!isCurrent()) throw StaleConnectionException()
                    delay(BACKFILL_TARGETS_PACE_MS)
                },
                allowConcurrent = ready.supportsConcurrentRequests,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleConnectionException) {
            return
        } catch (error: Exception) {
            diagnostics.record("history", "backfill_failed") {
                mapOf(
                    "network_id" to networkId,
                    "error_fp" to diagnostics.fingerprint(error.message),
                )
            }
            return
        }
        // The terminal page gets no onPageEnd; this final sweep seeds it, and targets already
        // seeded earlier skip cheaply on their stored room cursor.
        try {
            seedBackfillPage(networkId, discovery.targets, source, isCurrent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        if (discovery.status == WorkStatus.Complete) cursorDao.markComplete(networkId)
        diagnostics.record("history", "backfill_finished") {
            mapOf(
                "network_id" to networkId,
                "targets" to discovery.targets.size,
                "complete" to (discovery.status == WorkStatus.Complete),
            )
        }
    }

    private suspend fun seedBackfillPage(
        networkId: Long,
        page: List<ChatHistoryTarget>,
        source: HistorySource,
        isCurrent: () -> Boolean,
    ) {
        if (page.isEmpty()) return
        syncTargets(
            networkId = networkId,
            targets = mergeSyncTargets(emptyList(), page, source),
            source = source,
            isCurrent = isCurrent,
            hasDiscoveryWatermark = true,
            paceBetweenTargetsMs = BACKFILL_SEED_PACE_MS,
        )
    }

    internal suspend fun resyncNetwork(
        networkId: Long,
        openBuffers: List<Pair<Long, String>>,
        source: HistorySource,
        isCurrent: () -> Boolean = { true },
        initialLookbackMs: Long? = INITIAL_SYNC_LOOKBACK_MS,
    ): HistoryResyncState = coalesced(
        RequestSpec(
            RequestKey(networkId, null),
            sourceIdentity = source.flightIdentity(),
        ),
    ) {
        diagnostics.record("history", "network_sync_started") {
            mapOf("network_id" to networkId, "open_buffers" to openBuffers.size)
        }
        val ready = when (val availability = source.availability()) {
            HistoryAvailability.Unsupported -> return@coalesced HistoryResyncState.Unsupported
            HistoryAvailability.NegotiatingOrOffline -> return@coalesced historyUnavailable()
            is HistoryAvailability.Ready -> availability
        }
        val session = SyncStatusSession()
        openBuffers.forEach { (bufferId, _) -> session.queue(bufferId) }
        // A room row's newest message is not a reliable reconnect cursor: a newer push-delivered
        // message in one buffer can otherwise hide an older missed message in another. The wall
        // clock bounds discovery but is never persisted; only completed server response metadata
        // can advance the dedicated whole-network cursor.
        val previousSync = syncPrefs.lastSuccessfulSync(networkId)
        // First sync: bound discovery to the user's chosen window instead of epoch. A large bouncer
        // account advertises years of targets, and eagerly enumerating and seeding all of them froze
        // onboarding. Everything older trickles in behind the durable backfill cursor instead. A
        // null lookback is the explicit "everything" choice: enumerate from epoch in this one pass.
        val firstSyncLower = initialLookbackMs
            ?.let { Instant.now().toEpochMilli() - it }
            ?: Instant.EPOCH.toEpochMilli()
        val lower = (previousSync ?: firstSyncLower)
            .minus(TARGETS_FUZZ_MS)
            .coerceAtLeast(Instant.EPOCH.toEpochMilli())
        val upper = Instant.now().toEpochMilli() + TARGETS_FUZZ_MS
        if (previousSync == null && initialLookbackMs != null) {
            // +1 because TARGETS BETWEEN excludes both selectors: the backfill interval must
            // include a target advertised exactly at this pass's lower boundary. An unbounded first
            // pass reaches epoch itself, so there is nothing older left to seed a cursor for.
            db.historyBackfillCursorDao().seed(
                HistoryBackfillCursorEntity(networkId = networkId, upperBound = lower + 1),
            )
        }
        val result = try {
            val discovery = if (source.canClassifyTargets()) {
                discoverTargets(
                    networkId,
                    source,
                    upper,
                    lower,
                    allowConcurrent = ready.supportsConcurrentRequests,
                )
            } else {
                TargetDiscovery(
                    targets = emptyList(),
                    status = WorkStatus.Incomplete(
                        "CHATHISTORY TARGETS deferred until CHANTYPES negotiation settles",
                        awaitsTargetClassification = true,
                    ),
                    highWater = null,
                )
            }
            val mergedTargets = mergeSyncTargets(openBuffers, discovery.targets, source)
            val targetPass = syncTargets(
                networkId = networkId,
                targets = mergedTargets,
                source = source,
                isCurrent = isCurrent,
                hasDiscoveryWatermark = previousSync != null,
                session = session,
            )
            val inserted = targetPass.inserted
            val status = discovery.status.merge(targetPass.status)
            val highWater = maxHighWater(
                previousSync,
                discovery.highWater,
                targetPass.highWater,
            )
            if (status == WorkStatus.Complete && isCurrent() && highWater != null) {
                syncPrefs.setLastSuccessfulSync(networkId, highWater)
            }
            status.toState(inserted, retryRecommended = targetPass.retryRecommended)
        } catch (_: TimeoutCancellationException) {
            HistoryResyncState.Failed("History refresh timed out")
        } catch (cancelled: CancellationException) {
            session.finish(HistoryResyncState.Idle)
            throw cancelled
        } catch (_: StaleConnectionException) {
            staleConnection()
        } catch (error: Exception) {
            HistoryResyncState.Failed(
                error.message?.take(160) ?: "History refresh failed",
            )
        }
        diagnostics.record("history", "network_sync_finished") {
            mapOf(
                "network_id" to networkId,
                "targets" to openBuffers.size,
                "result" to result::class.simpleName,
            )
        }
        session.finish(if (isCurrent()) result else HistoryResyncState.Idle)
        result
    }

    /**
     * Enumerate the complete TARGETS interval before the network cursor is advanced. TARGETS has
     * BETWEEN ordering semantics. Each next upper bound overlaps the oldest returned millisecond;
     * target identity deduplication absorbs that replay while preserving same-timestamp ties. A
     * saturated tie that cannot move beyond the overlap is explicitly incomplete.
     */
    private suspend fun discoverTargets(
        networkId: Long,
        source: HistorySource,
        upper: Long,
        lower: Long,
        // Backfill hooks: [onPageEnd] runs after a page's boundary advances (seed-then-persist so a
        // killed process never skips enumerated targets), [betweenPages] paces the next request.
        onPageEnd: (suspend (page: List<ChatHistoryTarget>, nextUpper: Long) -> Unit)? = null,
        betweenPages: (suspend () -> Unit)? = null,
        allowConcurrent: Boolean = false,
    ): TargetDiscovery {
        val limit = source.pageLimit().coerceAtLeast(1)
        val targets = LinkedHashMap<String, ChatHistoryTarget>()
        var pageUpper = upper
        var highWater: Long? = null
        var previousTie: Pair<Long, Set<String>>? = null
        var requestsInChunk = 0
        var status: WorkStatus = WorkStatus.Complete
        val chunkLimit = targetsRequestLimit.coerceAtLeast(1)
        while (true) {
            val response = loader.fetchTargets(
                networkId,
                source,
                ChatHistoryRequest(
                    subcommand = ChatHistoryRequest.Subcommand.TARGETS,
                    target = "*",
                    bound1 = ChatHistorySelectors.timestamp(pageUpper),
                    bound2 = ChatHistorySelectors.timestamp(lower),
                    limit = limit,
                ),
                requestTimeoutMs,
                allowConcurrent = allowConcurrent,
            )
            requestsInChunk++
            val page = response.targets
            page.forEach { target ->
                val key = source.normalizeTarget(target.name)
                val existing = targets[key]
                if (existing == null || target.latestMessageTime > existing.latestMessageTime) {
                    targets[key] = target
                }
                highWater = maxHighWater(highWater, target.latestMessageTime)
            }
            if (response.endOfHistory || page.isEmpty()) {
                return TargetDiscovery(targets.values.toList(), status, highWater)
            }

            val oldest = page.minOf { it.latestMessageTime }
            val tiedKeys = page.asSequence()
                .filter { it.latestMessageTime == oldest }
                .map { source.normalizeTarget(it.name) }
                .toSet()
            if (previousTie == (oldest to tiedKeys)) {
                if (page.size < limit && oldest > lower) {
                    // Soju 0.10.x omits draft/chathistory-end. Move beyond its repeated short tie
                    // page so older targets are still recovered, but never call the pass complete:
                    // IRCv3 permits a server to return fewer than the requested limit, so another
                    // same-time target could remain undisclosed.
                    status = status.merge(
                        WorkStatus.Incomplete(
                            "CHATHISTORY TARGETS could not prove a timestamp tie was exhausted",
                        ),
                    )
                    pageUpper = oldest
                    previousTie = null
                    onPageEnd?.invoke(page, pageUpper)
                    if (requestsInChunk >= chunkLimit) {
                        requestsInChunk = 0
                        yield()
                    }
                    betweenPages?.invoke()
                    continue
                }
                return TargetDiscovery(
                    targets.values.toList(),
                    WorkStatus.Incomplete(
                        "CHATHISTORY TARGETS saturated a timestamp tie and could not advance",
                    ),
                    highWater,
                )
            }
            previousTie = oldest to tiedKeys

            // BETWEEN excludes both timestamp selectors. Move one millisecond past the oldest
            // timestamp so every tied target is replayed and deduplicated instead of skipped.
            val nextUpper = oldest.takeIf { it < Long.MAX_VALUE }?.plus(1)
            if (nextUpper == null || nextUpper >= pageUpper || nextUpper <= lower) {
                val reason = if (page.size >= limit && nextUpper != null && nextUpper >= pageUpper) {
                    "CHATHISTORY TARGETS saturated a timestamp tie and could not advance"
                } else {
                    "CHATHISTORY TARGETS returned an unusable boundary"
                }
                return TargetDiscovery(
                    targets.values.toList(),
                    WorkStatus.Incomplete(reason),
                    highWater,
                )
            }
            pageUpper = nextUpper
            onPageEnd?.invoke(page, pageUpper)
            if (requestsInChunk >= chunkLimit) {
                diagnostics.record("history", "targets_sync_continued") {
                    mapOf("targets" to targets.size, "high_water" to highWater)
                }
                requestsInChunk = 0
                yield()
            }
            betweenPages?.invoke()
        }
    }

    private fun mergeSyncTargets(
        openBuffers: List<Pair<Long, String>>,
        discovered: List<ChatHistoryTarget>,
        source: HistorySource,
    ): List<SyncTarget> {
        val targets = LinkedHashMap<String, SyncTarget>()
        openBuffers.forEach { (bufferId, name) ->
            targets[source.normalizeTarget(name)] = SyncTarget(bufferId, name, null)
        }
        discovered.forEach { target ->
            val key = source.normalizeTarget(target.name)
            val existing = targets[key]
            targets[key] = if (existing == null) {
                SyncTarget(null, target.name, target.latestMessageTime)
            } else {
                existing.copy(
                    latestMessageTime = existing.latestMessageTime
                        ?.let { maxOf(it, target.latestMessageTime) }
                        ?: target.latestMessageTime,
                )
            }
        }
        return targets.values.sortedWith(
            compareByDescending<SyncTarget> { it.latestMessageTime != null }
                .thenByDescending { it.latestMessageTime ?: Long.MIN_VALUE },
        )
    }

    internal suspend fun reconcileBuffer(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean = { true },
    ): HistoryResyncState {
        val ready = when (val availability = source.availability()) {
            HistoryAvailability.Unsupported -> return HistoryResyncState.Unsupported
            HistoryAvailability.NegotiatingOrOffline -> return historyUnavailable()
            is HistoryAvailability.Ready -> availability
        }
        if (!isCurrent()) return staleConnection()
        return coalesced(
            RequestSpec(
                RequestKey(networkId, bufferId),
                source.flightIdentity(),
            ),
        ) {
            // A user retry or JOIN seed is its own single-buffer pass; the generation guard lets it
            // supersede a stale reconnect-pass entry for the same buffer, and vice versa.
            val session = SyncStatusSession()
            session.queue(bufferId)
            val result = try {
                val work = syncRecentTarget(
                    networkId = networkId,
                    bufferId = bufferId,
                    target = target,
                    source = source,
                    isCurrent = isCurrent,
                    discoveredLatestMessageTime = null,
                    session = session,
                    allowConcurrent = ready.supportsConcurrentRequests,
                )
                work.status.toState(work.inserted)
            } catch (_: TimeoutCancellationException) {
                HistoryResyncState.Failed("History refresh timed out")
            } catch (cancelled: CancellationException) {
                session.finish(HistoryResyncState.Idle)
                throw cancelled
            } catch (_: StaleConnectionException) {
                staleConnection()
            } catch (error: Exception) {
                HistoryResyncState.Failed(error.message?.take(160) ?: "History refresh failed")
            }
            session.finish(if (isCurrent()) result else HistoryResyncState.Idle)
            result
        }
    }

    private suspend fun syncTargets(
        networkId: Long,
        targets: List<SyncTarget>,
        source: HistorySource,
        isCurrent: () -> Boolean,
        hasDiscoveryWatermark: Boolean,
        // Null publishes nothing: the paced background backfill must stay invisible.
        session: SyncStatusSession? = null,
        paceBetweenTargetsMs: Long = 0,
    ): TargetPass {
        val ready = when (val availability = source.availability()) {
            HistoryAvailability.Unsupported -> error("History support disappeared during reconciliation")
            HistoryAvailability.NegotiatingOrOffline -> error("History support became unavailable")
            is HistoryAvailability.Ready -> availability
        }
        if (!isCurrent()) throw StaleConnectionException()
        val outcomes = if (ready.supportsConcurrentRequests && paceBetweenTargetsMs == 0L) {
            // Bounded fan-out: labeled-response correlates concurrent CHATHISTORY, so a reconnect
            // pass may keep several targets on the wire. The coordinator-side permit is
            // load-bearing: a fetch's timeout starts when it is CALLED and includes gate wait, so
            // launching every target's fetch at once would start every timeout clock at once and
            // mass-expire the tail of a large pass. [mergeSyncTargets] sorts newest-first and the
            // fair semaphore admits in launch order, so the newest targets still start first.
            val permits = Semaphore(HistoryPageLoader.MAX_CONCURRENT_WIRE_REQUESTS)
            coroutineScope {
                targets.map { targetSpec ->
                    async {
                        permits.withPermit {
                            syncOneTarget(
                                networkId = networkId,
                                targetSpec = targetSpec,
                                source = source,
                                isCurrent = isCurrent,
                                hasDiscoveryWatermark = hasDiscoveryWatermark,
                                session = session,
                                paceBeforeFetchMs = 0,
                                allowConcurrent = true,
                            )
                        }
                    }
                }.awaitAll()
            }
        } else {
            // Strictly sequential: connections without labeled-response, and the paced backfill
            // seed, keep today's one-at-a-time order.
            targets.map { targetSpec ->
                syncOneTarget(
                    networkId = networkId,
                    targetSpec = targetSpec,
                    source = source,
                    isCurrent = isCurrent,
                    hasDiscoveryWatermark = hasDiscoveryWatermark,
                    session = session,
                    paceBeforeFetchMs = paceBetweenTargetsMs,
                    allowConcurrent = false,
                )
            }
        }
        // Fold in list order so the first Incomplete reason (newest-first) stays deterministic.
        return TargetPass(
            inserted = outcomes.sumOf { it.inserted },
            status = outcomes.fold(WorkStatus.Complete as WorkStatus) { acc, outcome ->
                acc.merge(outcome.status)
            },
            highWater = maxHighWater(*outcomes.map { it.highWater }.toTypedArray()),
            retryRecommended = outcomes.any { it.retryRecommended },
        )
    }

    /**
     * One target's share of a pass: resolve its room, skip cheaply when nothing changed, fetch the
     * newest page, and settle its status. Throws [StaleConnectionException] (aborting the pass and
     * cancelling fan-out siblings) when the connection is superseded; contains target-scoped
     * permanent refusals so one bad target cannot abort the pass.
     */
    private suspend fun syncOneTarget(
        networkId: Long,
        targetSpec: SyncTarget,
        source: HistorySource,
        isCurrent: () -> Boolean,
        hasDiscoveryWatermark: Boolean,
        session: SyncStatusSession?,
        paceBeforeFetchMs: Long,
        allowConcurrent: Boolean,
    ): TargetOutcome {
        if (!isCurrent()) throw StaleConnectionException()
        val target = targetSpec.name
        val canonicalRoomId = targetSpec.knownBufferId ?: if (source.isChannelTarget(target)) {
            return TargetOutcome()
        } else {
            processor.ensureHistoryQuery(networkId, target, source.normalizeTarget(target))
        }
        // A target discovered mid-pass registers here; without this it would sync with no
        // status at all, because only the pass's open buffers were registered up front.
        session?.queue(canonicalRoomId)
        val roomCursor = db.historyCursorDao().byRoom(canonicalRoomId)
        if (
            hasDiscoveryWatermark &&
            targetSpec.latestMessageTime == null &&
            roomCursor != null
        ) {
            // Nothing to fetch: settle now instead of leaving a spinner up until pass end.
            session?.settle(canonicalRoomId, HistorySyncStatus.Idle)
            return TargetOutcome()
        }
        // The advertised newest is already stored: nothing new to fetch regardless of the
        // watermark. This keeps first-run retries and the paced backfill from re-requesting a
        // page for every target they have already seeded.
        val advertisedLatest = targetSpec.latestMessageTime
        if (
            advertisedLatest != null &&
            roomCursor?.newestServerTime?.let { it >= advertisedLatest } == true
        ) {
            session?.settle(canonicalRoomId, HistorySyncStatus.Idle)
            return TargetOutcome()
        }
        if (paceBeforeFetchMs > 0) delay(paceBeforeFetchMs)
        val targetResult = try {
            syncRecentTarget(
                networkId = networkId,
                bufferId = canonicalRoomId,
                target = target,
                source = source,
                isCurrent = isCurrent,
                discoveredLatestMessageTime = targetSpec.latestMessageTime,
                session = session,
                allowConcurrent = allowConcurrent,
            )
        } catch (refused: IrcCommandException) {
            // A target-scoped permanent refusal (services such as ChanServ typically answer
            // FAIL CHATHISTORY INVALID_TARGET) must not abort the pass: letting it escape
            // skipped every remaining target, marked every open buffer Failed, and left an
            // unrecoverable retry banner because the next attempt reissues the same request.
            if (refused.code != HistoryPageLoader.INVALID_TARGET) throw refused
            diagnostics.record("history", "target_history_refused") {
                mapOf(
                    "network_id" to networkId,
                    "room_id" to canonicalRoomId,
                    "target_fp" to diagnostics.fingerprint(source.normalizeTarget(target)),
                    "code" to refused.code,
                )
            }
            // The server will never serve this target; a retry affordance would be a lie.
            session?.settle(canonicalRoomId, HistorySyncStatus.Unavailable)
            return TargetOutcome()
        }
        // TARGETS describes the newest server event, which may be a JOIN or an event that is
        // intentionally filtered/rerouted during ingestion. Count either a durable local event
        // or an event observed in this response as reaching it; relying on the chat cursor alone
        // would retry forever for those valid cases.
        val newestStoredTime = maxHighWater(
            db.messageDao().latestBoundary(canonicalRoomId)?.serverTime,
            db.historyCursorDao().byRoom(canonicalRoomId)?.newestServerTime,
            targetResult.highWater,
        )
        val reachedAdvertisedLatest = targetSpec.latestMessageTime?.let { latest ->
            newestStoredTime?.let { it >= latest } == true
        }
        val effectiveStatus = if (
            reachedAdvertisedLatest == false && targetResult.status == WorkStatus.Complete
        ) {
            WorkStatus.Incomplete("CHATHISTORY did not reach the latest advertised message")
        } else {
            targetResult.status
        }
        session?.settle(
            canonicalRoomId,
            if (reachedAdvertisedLatest == true) {
                HistorySyncStatus.Idle
            } else {
                effectiveStatus.toSyncStatus()
            },
        )
        return TargetOutcome(
            inserted = targetResult.inserted,
            status = effectiveStatus,
            highWater = targetResult.highWater,
            retryRecommended = reachedAdvertisedLatest == false,
        )
    }

    /**
     * Seed one changed target newest-first. Each completed response is published immediately so a
     * visible chat can paint after one round trip; any older retained interval remains a durable
     * gap for directional Paging instead of being traversed during reconnect.
     */
    private suspend fun syncRecentTarget(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean,
        discoveredLatestMessageTime: Long?,
        session: SyncStatusSession? = null,
        allowConcurrent: Boolean = false,
    ): WorkResult {
        val room = db.bufferDao().observeById(bufferId) ?: throw StaleConnectionException()
        val referenceTypes = source.referenceTypes()
        val msgidAllowed = HistoryReferenceType.MSGID in referenceTypes
        val discardedBoundary = ChatHistoryReference(
            room.historyDiscardedThroughMsgid,
            room.historyDiscardedThroughTime,
        ).takeIf { it.msgid != null || it.serverTime != null }
        if (room.dismissed && discoveredLatestMessageTime == null) return WorkResult()
        val discardedThroughTime = discardedBoundary?.serverTime
        if (
            room.dismissed &&
            discardedThroughTime != null &&
            discoveredLatestMessageTime != null &&
            discoveredLatestMessageTime <= discardedThroughTime
        ) {
            return WorkResult()
        }

        val requestLimit = minOf(source.pageLimit(), RECENT_PAGE_SIZE)
        // Everything above can settle this target without a round trip, so only announce Syncing
        // once a request is genuinely about to go on the wire.
        session?.syncing(bufferId)
        val boundedLatest = discardedBoundary
            ?.takeIf { room.type == BufferType.QUERY }
            ?.let { floor ->
                fetchPageOrNullOnRejectedBoundary(
                    networkId = networkId,
                    target = target,
                    subcommand = ChatHistoryRequest.Subcommand.LATEST,
                    source = source,
                    boundary = floor,
                    secondBoundary = null,
                    referenceTypes = referenceTypes,
                    limit = requestLimit,
                    msgidAllowed = msgidAllowed,
                    allowConcurrent = allowConcurrent,
                )
            }
        val request = boundedLatest?.request ?: ChatHistoryRequest(
            subcommand = ChatHistoryRequest.Subcommand.LATEST,
            target = target,
            limit = requestLimit,
        )
        val page = boundedLatest?.response ?: loader.fetchMessages(
            networkId,
            source,
            request,
            referenceTypes,
            msgidAllowed,
            requestTimeoutMs,
            allowConcurrent = allowConcurrent,
        )
        if (!isCurrent()) throw StaleConnectionException()
        val inserted = ingest(networkId, bufferId, request, page)
        val highWater = page.highWater()
        if (page.isTerminalPage()) return WorkResult(highWater = highWater, inserted = inserted)
        if (page.oldest?.msgid == null && page.primaryMessageCount >= request.limit) {
            return WorkResult(
                WorkStatus.Incomplete("CHATHISTORY timestamp boundary is saturated"),
                highWater,
                inserted,
            )
        }

        if (page.directionalBoundary(ChatHistoryRequest.Subcommand.LATEST) == null) {
            return WorkResult(
                WorkStatus.Incomplete("CHATHISTORY LATEST returned no usable oldest boundary"),
                highWater,
                inserted,
            )
        }
        // ingest persisted this non-terminal oldest boundary as a durable gap. Automatic reconnect
        // stops here; only user-authorized paging or manual refresh may traverse it with BEFORE.
        return WorkResult(highWater = highWater, inserted = inserted)
    }

    /** Manual eager recovery retained for explicit Missing/All Available requests. */
    private suspend fun coalesced(
        spec: RequestSpec,
        block: suspend () -> HistoryResyncState,
    ): HistoryResyncState {
        val registration = synchronized(activeGuard) {
            val joined = activeFlights[spec]
            if (joined != null) {
                FlightRegistration(joined, ownsFlight = false)
            } else {
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    // Wire admission lives in the loader's per-network gate, acquired per fetch
                    // inside block(); this flight only owns request-level coalescing and
                    // user-facing status.
                    block()
                }
                val created = ActiveFlight(spec, deferred)
                activeFlights[spec] = created
                deferred.invokeOnCompletion {
                    removeActiveFlight(created)
                }
                FlightRegistration(created, ownsFlight = true)
            }
        }
        val flight = registration.flight
        if (registration.ownsFlight) flight.deferred.start()
        try {
            return flight.deferred.await()
        } finally {
            if (flight.deferred.isCompleted) {
                removeActiveFlight(flight)
            }
        }
    }

    private fun removeActiveFlight(flight: ActiveFlight) {
        synchronized(activeGuard) {
            if (activeFlights[flight.spec] === flight) {
                activeFlights.remove(flight.spec)
            }
        }
    }

    /**
     * [HistoryPageLoader.fetchPage] rethrows the server's original `INVALID_MSGREFTYPE` when a
     * rejected msgid boundary has no advertised timestamp fallback, so Paging surfaces those
     * diagnostics via MediatorResult.Error. Reconnect/manual orchestration instead treats that
     * unrecoverable local boundary exactly like a pre-check rejection (null): callers degrade to a
     * LATEST seed rather than failing the whole pass on a stale stored cursor.
     */
    private suspend fun fetchPageOrNullOnRejectedBoundary(
        networkId: Long,
        target: String,
        subcommand: ChatHistoryRequest.Subcommand,
        source: HistorySource,
        boundary: ChatHistoryReference,
        secondBoundary: ChatHistoryReference?,
        referenceTypes: Set<HistoryReferenceType>,
        limit: Int,
        msgidAllowed: Boolean,
        allowConcurrent: Boolean = false,
    ): HistoryPageLoader.FetchedPage? = try {
        loader.fetchPage(
            networkId = networkId,
            target = target,
            subcommand = subcommand,
            source = source,
            boundary = boundary,
            secondBoundary = secondBoundary,
            referenceTypes = referenceTypes,
            limit = limit,
            msgidAllowed = msgidAllowed,
            timeoutMs = requestTimeoutMs,
            allowConcurrent = allowConcurrent,
        )
    } catch (error: IrcCommandException) {
        // Only the exact no-fallback msgid rejection degrades; every other command error (including
        // a rejected timestamp selector) still propagates as a failure.
        val unrecoverableMsgidRejection = error.code == HistoryPageLoader.INVALID_MSGREFTYPE &&
            loader.selectorOf(boundary, referenceTypes, msgidAllowed = false) == null
        if (!unrecoverableMsgidRejection) throw error
        null
    }

    private suspend fun HistorySource.referenceTypes(): Set<HistoryReferenceType> =
        (availability() as? HistoryAvailability.Ready)?.referenceTypes ?: emptySet()

    private suspend fun HistorySource.pageLimit(): Int =
        ((availability() as? HistoryAvailability.Ready)?.pageLimit ?: PAGE_LIMIT)
            .coerceAtMost(PAGE_LIMIT)
            .coerceAtLeast(1)

    private suspend fun HistorySource.supportsReference(type: HistoryReferenceType): Boolean =
        (availability() as? HistoryAvailability.Ready)
            ?.referenceTypes
            ?.contains(type) == true

    private suspend fun latestBoundaryFromRoom(bufferId: Long): ChatHistoryReference? =
        db.messageDao().latestBoundary(bufferId)?.let { ChatHistoryReference(it.msgid, it.serverTime) }

    private suspend fun hasStoredChat(bufferId: Long): Boolean = db.messageDao().hasStoredChat(bufferId)

    private fun ChatHistoryResponse.Messages.isTerminalPage(): Boolean =
        endOfHistory || primaryMessageCount == 0

    private fun ChatHistoryResponse.Messages.directionalBoundary(
        subcommand: ChatHistoryRequest.Subcommand,
    ): ChatHistoryReference? = when (subcommand) {
        ChatHistoryRequest.Subcommand.AFTER -> newest
        ChatHistoryRequest.Subcommand.LATEST,
        ChatHistoryRequest.Subcommand.BEFORE,
        ChatHistoryRequest.Subcommand.BETWEEN,
        -> oldest
        ChatHistoryRequest.Subcommand.AROUND -> null
        ChatHistoryRequest.Subcommand.TARGETS -> error("TARGETS is not a message page")
    }

    private fun ChatHistoryResponse.Messages.hasUsableDirectionalBoundary(
        subcommand: ChatHistoryRequest.Subcommand,
        referenceTypes: Set<HistoryReferenceType>,
    ): Boolean = directionalBoundary(subcommand)
        ?.let { loader.selectorOf(it, referenceTypes, HistoryReferenceType.MSGID in referenceTypes) } != null

    private fun ChatHistoryResponse.Messages.highWater(): Long? =
        if (primaryMessageCount == 0) null else maxHighWater(oldest?.serverTime, newest?.serverTime)

    private suspend fun ingest(
        networkId: Long,
        expectedRoomId: RoomId,
        request: ChatHistoryRequest,
        page: ChatHistoryResponse.Messages,
        historyGapId: Long? = null,
    ): Int = ingestResult(networkId, expectedRoomId, request, page, historyGapId).inserted

    private suspend fun ingestResult(
        networkId: Long,
        expectedRoomId: RoomId,
        request: ChatHistoryRequest,
        page: ChatHistoryResponse.Messages,
        historyGapId: Long? = null,
    ): io.github.trevarj.motd.data.sync.PersistedHistoryPage {
        if (db.bufferDao().rawById(expectedRoomId) == null) throw StaleConnectionException()
        return processor.persistHistoryPageResult(
            networkId,
            request,
            page,
            expectedRoomId = expectedRoomId,
            historyGapId = historyGapId,
        )
    }

    /**
     * Fetching remains outside Room, while every page collected for one room is committed in one
     * transaction after traversal settles. This keeps Paging from observing partially reconciled
     * rows or intermediate ordering. Already-validated pages are retained on timeout/cancellation,
     * matching the previous eager-persistence behavior without exposing its incremental redraws.
     */
    private fun beginSyncStatus(bufferId: Long, status: HistorySyncStatus): Long {
        val generation = syncStatusGenerations
            .computeIfAbsent(bufferId) { AtomicLong() }
            .incrementAndGet()
        _syncStatuses.update { current ->
            initialSyncStatusIfCurrent(
                current = current,
                bufferId = bufferId,
                generation = generation,
                currentGeneration = syncStatusGenerations[bufferId]?.get(),
                status = status,
            )
        }
        return generation
    }

    private fun publishSyncStatus(bufferId: Long, generation: Long, status: HistorySyncStatus) {
        _syncStatuses.update { current ->
            if (syncStatusGenerations[bufferId]?.get() == generation) {
                current + (bufferId to status)
            } else {
                current
            }
        }
    }

    private fun finishSyncStatus(bufferId: Long, generation: Long, status: HistorySyncStatus) {
        _syncStatuses.update { current ->
            if (syncStatusGenerations[bufferId]?.get() != generation) {
                current
            } else if (status == HistorySyncStatus.Idle) {
                current - bufferId
            } else {
                current + (bufferId to status)
            }
        }
    }

    private fun WorkStatus.toState(
        inserted: Int,
        retryRecommended: Boolean = false,
    ): HistoryResyncState = when (this) {
        WorkStatus.Complete ->
            if (inserted > 0) HistoryResyncState.Updated(inserted) else HistoryResyncState.UpToDate
        is WorkStatus.Incomplete -> HistoryResyncState.Incomplete(
            inserted,
            reason,
            awaitsTargetClassification,
            retryRecommended,
        )
        is WorkStatus.Capped -> HistoryResyncState.Capped(inserted, limit, reason)
    }

    private fun WorkStatus.toSyncStatus(): HistorySyncStatus = when (this) {
        WorkStatus.Complete -> HistorySyncStatus.Idle
        is WorkStatus.Incomplete -> HistorySyncStatus.Partial(reason)
        is WorkStatus.Capped -> HistorySyncStatus.Partial(reason)
    }

    private fun HistoryResyncState.toSyncStatus(): HistorySyncStatus = when (this) {
        HistoryResyncState.Idle,
        is HistoryResyncState.Updated,
        HistoryResyncState.UpToDate,
        HistoryResyncState.Unsupported,
        -> HistorySyncStatus.Idle
        HistoryResyncState.WaitingForCapability -> HistorySyncStatus.Queued
        is HistoryResyncState.Running -> HistorySyncStatus.Syncing
        is HistoryResyncState.Incomplete -> HistorySyncStatus.Partial(reason)
        is HistoryResyncState.Capped -> HistorySyncStatus.Partial(reason)
        is HistoryResyncState.Failed -> HistorySyncStatus.Failed(reason)
    }

    private fun WorkStatus.merge(other: WorkStatus): WorkStatus = when {
        this is WorkStatus.Incomplete -> this
        other is WorkStatus.Incomplete -> other
        this is WorkStatus.Capped -> this
        other is WorkStatus.Capped -> other
        else -> WorkStatus.Complete
    }

    private fun maxHighWater(vararg values: Long?): Long? = values.filterNotNull().maxOrNull()

    private class ClientHistorySource(private val client: IrcClient) : HistorySource {
        override suspend fun availability(): HistoryAvailability = client.historyAvailability

        override fun flightIdentity(): Any = client

        override fun canClassifyTargets(): Boolean = client.targetClassificationReady.value

        override fun normalizeTarget(target: String): String = client.isupport.identityRules.normalize(target)

        override fun isChannelTarget(target: String): Boolean =
            client.isupport.identityRules.isChannel(target)

        override suspend fun chathistory(request: ChatHistoryRequest): ChatHistoryResponse =
            client.chathistory(request)
    }

    private class StaleConnectionException : Exception()

    private fun staleConnection(): HistoryResyncState.Failed =
        HistoryResyncState.Failed("Connection changed; try again")

    private fun historyUnavailable(): HistoryResyncState.Failed =
        HistoryResyncState.Failed("History support is still negotiating or the connection is offline")

    internal companion object {
        const val PAGE_LIMIT = 100
        const val RECENT_PAGE_SIZE = 50
        const val REQUEST_TIMEOUT_MS = 35_000L
        const val PENDING_MESSAGE_TIMEOUT_MS = 65_000L
        const val TARGETS_FUZZ_MS = 10_000L
        const val TARGET_CLASSIFICATION_WAIT_TIMEOUT_MS = 10_000L
        const val TARGETS_REQUEST_LIMIT = 100

        /** First-sync TARGETS window; everything older belongs to the paced backfill. */
        const val INITIAL_SYNC_LOOKBACK_MS = 30L * 24 * 60 * 60 * 1_000
        /** Delay between backfill TARGETS requests. */
        const val BACKFILL_TARGETS_PACE_MS = 2_000L
        /** Delay before each backfill per-target newest-page seed. */
        const val BACKFILL_SEED_PACE_MS = 500L
    }
}
