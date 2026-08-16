package io.github.trevarj.motd.e2e

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.SearchRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistorySyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout

/**
 * Waits for one buffer's history-sync episode: it must become active and then settle.
 *
 * `AwaitingConnection` counts as active on purpose — a foreground with no live connection is a real
 * outage, and the badge it paints is part of the episode the caller is waiting out. Note that a
 * catch-up pass publishes nothing at all until discovery proves a room changed, so "active" now
 * begins at the first changed discovery page rather than at pass registration; a pass that finds
 * nothing to fetch produces no episode to observe, by design.
 */
class HistorySyncProbe(
    private val history: HistoryResyncController,
    private val milestones: E2eMilestoneRecorder,
) {
    suspend fun awaitCycle(bufferId: Long, timeoutMs: Long = 45_000) {
        try {
            withTimeout(timeoutMs) {
                var active = false
                history.syncStatus(bufferId).first { status ->
                    val isActive = status == HistorySyncStatus.Queued ||
                        status == HistorySyncStatus.AwaitingConnection ||
                        status == HistorySyncStatus.Syncing
                    if (isActive) active = true
                    active && !isActive
                }
            }
            milestones.record("history_sync_settled", "buffer=$bufferId")
        } catch (timeout: TimeoutCancellationException) {
            milestones.record("history_sync_timeout", "buffer=$bufferId")
            throw AssertionError("history sync readiness timed out for buffer=$bufferId", timeout)
        }
    }
}

class ConnectionProbe(private val connections: ConnectionManager, private val milestones: E2eMilestoneRecorder) {
    suspend fun awaitReady(id: Long, requiredCaps: Set<String>, timeoutMs: Long = 30_000): IrcClientState.Ready =
        withTimeout(timeoutMs) {
            connections.connectionStates.first { states ->
                when (val state = states[id]) {
                    is IrcClientState.Ready -> {
                        milestones.record("connection_ready", "network=$id caps=${state.caps.sorted().joinToString(",")}")
                        requiredCaps.all { cap -> state.caps.any { it == cap || it.startsWith("$cap=") } }
                    }
                    is IrcClientState.Failed -> {
                        milestones.record("connection_failed", "network=$id fatal=${state.fatal}")
                        if (state.fatal) error("fatal connection state")
                        false
                    }
                    null -> false
                    else -> {
                        milestones.record("connection_state", "network=$id state=${state::class.simpleName}")
                        false
                    }
                }
            }
            connections.connectionStates.value[id] as IrcClientState.Ready
        }

    suspend fun awaitDisconnected(id: Long, timeoutMs: Long = 15_000) {
        withTimeout(timeoutMs) {
            connections.connectionStates.first { states ->
                when (states[id]) {
                    null, IrcClientState.Disconnected -> true
                    else -> false
                }
            }
        }
        milestones.record("connection_disconnected", "network=$id")
    }
}

class BufferProbe(private val buffers: BufferRepository, private val milestones: E2eMilestoneRecorder) {
    suspend fun awaitJoinedChannel(networkId: Long, channel: String, timeoutMs: Long = 20_000): Long =
        try {
            withTimeout(timeoutMs) {
                buffers.observeChatList().first { rows ->
                    rows.any { row -> row.networkId == networkId && row.type == BufferType.CHANNEL && row.displayName.equals(channel, true) }
                }.first { it.networkId == networkId && it.type == BufferType.CHANNEL && it.displayName.equals(channel, true) }
                    .bufferId.also { milestones.record("buffer_joined", "network=$networkId buffer=$it") }
            }
        } catch (timeout: TimeoutCancellationException) {
            milestones.record("buffer_timeout", "network=$networkId")
            throw AssertionError("joined channel readiness timed out for network=$networkId", timeout)
        }
}

/** Uses the public search repository to observe the canonical event written by EventProcessor. */
class MessageLifecycleProbe(
    private val search: SearchRepository,
    private val milestones: E2eMilestoneRecorder,
) {
    suspend fun awaitCanonical(token: String, bufferId: Long, timeoutMs: Long = 20_000): MessageEntity =
        awaitCanonicalMatch(token, bufferId, timeoutMs, requireSelf = true) { it.text == token }

    suspend fun awaitCanonicalFromAnySender(
        token: String,
        bufferId: Long,
        timeoutMs: Long = 20_000,
    ): MessageEntity = awaitCanonicalMatch(token, bufferId, timeoutMs, requireSelf = false) { it.text == token }

    suspend fun awaitCanonicalContaining(
        query: String,
        expectedSubstring: String,
        bufferId: Long,
        timeoutMs: Long = 20_000,
    ): MessageEntity = awaitCanonicalMatch(query, bufferId, timeoutMs, requireSelf = true) {
        it.text.contains(expectedSubstring)
    }

    private suspend fun awaitCanonicalMatch(
        query: String,
        bufferId: Long,
        timeoutMs: Long,
        requireSelf: Boolean,
        matches: (MessageEntity) -> Boolean,
    ): MessageEntity =
        try {
            withTimeout(timeoutMs) {
                search.search(query, bufferId).map { it.hits }.first { hits ->
                    hits.count { hit ->
                        (!requireSelf || hit.message.isSelf) && matches(hit.message) && hit.message.msgid != null &&
                            hit.message.pendingLabel == null && !hit.message.failed
                    } == 1
                }.single { hit ->
                    (!requireSelf || hit.message.isSelf) && matches(hit.message) && hit.message.msgid != null &&
                        hit.message.pendingLabel == null && !hit.message.failed
                }.message.also { milestones.record("canonical_message", "buffer=$bufferId event=${it.id}") }
            }
        } catch (timeout: TimeoutCancellationException) {
            milestones.record("canonical_timeout", "buffer=$bufferId")
            throw AssertionError("canonical message readiness timed out for buffer=$bufferId", timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
}

/** Observes fixture rows through the same bounded chat-only search surface as the UI. */
class MessageRunProbe(
    private val search: SearchRepository,
    private val milestones: E2eMilestoneRecorder,
) {
    suspend fun awaitRecentRows(
        token: String,
        bufferId: Long,
        minimumCount: Int,
        maximumCount: Int,
        expectedNewestOrdinal: Int,
        requiredText: String,
        excludedText: String,
        timeoutMs: Long = 45_000,
    ): List<MessageEntity> = try {
        withTimeout(timeoutMs) {
            search.search(token, bufferId).map { it.hits }.first { hits ->
                val rows = hits.map { it.message }.filter { it.text.startsWith("$token row") }
                rows.any { it.text == requiredText }
            }.map { it.message }
                .filter { it.text.startsWith("$token row") }
                .also { rows ->
                    check(rows.size in minimumCount..maximumCount) {
                        "bounded recent history count ${rows.size} is outside $minimumCount..$maximumCount"
                    }
                    check(rows.none { it.text == excludedText }) {
                        "bounded recent history unexpectedly contains $excludedText"
                    }
                    validateRows(token, bufferId, rows, expectedNewestOrdinal)
                }
        }
    } catch (timeout: TimeoutCancellationException) {
        milestones.record("history_run_timeout", "buffer=$bufferId count=$minimumCount..$maximumCount")
        throw AssertionError(
            "bounded recent history rows timed out for buffer=$bufferId count=$minimumCount..$maximumCount",
            timeout,
        )
    }

    /**
     * Waits for the bounded fixture window to stop growing before validating its size.
     *
     * Every snapshot is recorded on the way past, not only the one that settles, because this probe
     * CANNOT succeed early: `debounce(stableMs).first { requiredText present }` either settles on a
     * window that has been quiet for [stableMs] and contains [requiredText], or it times out. A bare
     * `TimeoutCancellationException` from that shape is almost content-free — it cannot distinguish
     * "the transition never started" from "it started and stopped somewhere else" — so the failure
     * carries the last observation instead: `count=/oldest=/newest=`, plus the same values as a
     * milestone so a red run is diffable against the green baseline CI already uploads.
     */
    @OptIn(FlowPreview::class)
    suspend fun awaitStableRecentRows(
        token: String,
        bufferId: Long,
        minimumCount: Int,
        maximumCount: Int,
        expectedNewestOrdinal: Int,
        requiredText: String,
        excludedText: String,
        stableMs: Long = 1_500,
        timeoutMs: Long = 45_000,
    ): List<MessageEntity> {
        var observed: List<MessageEntity> = emptyList()
        var snapshots = 0
        return try {
            withTimeout(timeoutMs) {
                search.search(token, bufferId).map { it.hits }
                    .map { hits ->
                        hits.map { it.message }.filter { it.text.startsWith("$token row") }
                    }
                    .distinctUntilChangedBy { rows -> rows.map { it.id } }
                    .onEach { rows ->
                        observed = rows
                        snapshots++
                    }
                    .debounce(stableMs)
                    .first { rows -> rows.any { it.text == requiredText } }
                    .also { rows ->
                        check(rows.size in minimumCount..maximumCount) {
                            "settled recent history count ${rows.size} is outside $minimumCount..$maximumCount"
                        }
                        check(rows.none { it.text == excludedText }) {
                            "settled recent history unexpectedly contains $excludedText"
                        }
                        validateRows(token, bufferId, rows, expectedNewestOrdinal)
                        milestones.record("history_run_stable", "buffer=$bufferId count=${rows.size}")
                    }
            }
        } catch (timeout: TimeoutCancellationException) {
            val seen = observed.describe(token)
            milestones.record(
                "history_run_stable_timeout",
                "buffer=$bufferId want=$minimumCount..$maximumCount snapshots=$snapshots $seen",
            )
            throw AssertionError(
                "settled recent history rows timed out for buffer=$bufferId " +
                    "want=$minimumCount..$maximumCount snapshots=$snapshots $seen required=$requiredText",
                timeout,
            )
        }
    }

    /** The last observation, as the three facts that identify which terminal state was reached. */
    private fun List<MessageEntity>.describe(token: String): String {
        val ordered = sortedBy { it.text.substringAfter("$token row").toIntOrNull() ?: Int.MIN_VALUE }
        return "count=$size oldest=${ordered.firstOrNull()?.text ?: "none"} " +
            "newest=${ordered.lastOrNull()?.text ?: "none"}"
    }

    suspend fun awaitRows(
        token: String,
        bufferId: Long,
        count: Int,
        expectedExtras: Set<String>,
        expectedNewestOrdinal: Int,
        timeoutMs: Long = 45_000,
    ): List<MessageEntity> =
        try {
            withTimeout(timeoutMs) {
                search.search(token, bufferId).map { it.hits }.first { hits ->
                    val messages = hits.map { it.message }
                    val rows = messages.filter { it.text.startsWith("$token row") }
                    val extras = messages
                        .filterNot { it.text.startsWith("$token row") }
                        .map { it.text }
                    rows.size == count && extras.size == expectedExtras.size && extras.toSet() == expectedExtras
                }.map { it.message }.let { messages ->
                    val rows = messages.filter { it.text.startsWith("$token row") }
                    val extras = messages
                        .filterNot { it.text.startsWith("$token row") }
                        .map { it.text }
                    check(extras.size == expectedExtras.size && extras.toSet() == expectedExtras) {
                        "fixture window contains unexpected non-row messages"
                    }
                    rows.also { validateRows(token, bufferId, it, expectedNewestOrdinal) }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            milestones.record("history_run_timeout", "buffer=$bufferId count=$count")
            throw AssertionError("canonical history row window timed out for buffer=$bufferId count=$count", timeout)
        }

    private fun validateRows(
        token: String,
        bufferId: Long,
        rows: List<MessageEntity>,
        expectedNewestOrdinal: Int,
    ) {
        check(rows.map { it.id }.distinct().size == rows.size) { "fixture run contains duplicate event ids" }
        check(rows.all { it.msgid != null }) { "fixture run contains a row without canonical msgid identity" }
        check(rows.map { it.msgid }.distinct().size == rows.size) { "fixture run contains duplicate msgids" }
        check(rows.map { it.text }.distinct().size == rows.size) { "fixture run contains duplicate bodies" }
        val ordered = rows.sortedBy { it.text.substringAfter("$token row").toInt() }
        val ordinals = ordered.map { it.text.substringAfter("$token row").toInt() }
        val expectedOrdinals = (expectedNewestOrdinal - rows.size + 1..expectedNewestOrdinal).toList()
        check(ordinals == expectedOrdinals) { "fixture run is not a contiguous newest-first suffix" }
        check(ordered.zipWithNext().all { (older, newer) -> older.anchor() < newer.anchor() }) {
            "fixture run is not in canonical chronological order"
        }
        milestones.record("history_run_canonical", "buffer=$bufferId count=${rows.size}")
    }

    private fun MessageEntity.anchor(): TimelineAnchor = TimelineAnchor(serverTime, id, timelineOrder)
}
