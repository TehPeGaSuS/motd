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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class HistorySyncProbe(
    private val history: HistoryResyncController,
    private val milestones: E2eMilestoneRecorder,
) {
    suspend fun awaitCycle(bufferId: Long, timeoutMs: Long = 45_000) {
        try {
            withTimeout(timeoutMs) {
                var active = false
                history.syncStatus(bufferId).first { status ->
                    val isActive = status == HistorySyncStatus.Checking || status == HistorySyncStatus.Syncing
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
                search.search(query, bufferId).first { hits ->
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

/** Observes an exact row-only fixture window through the same bounded search surface as the UI. */
class MessageRunProbe(
    private val search: SearchRepository,
    private val milestones: E2eMilestoneRecorder,
) {
    suspend fun awaitRows(token: String, bufferId: Long, count: Int, timeoutMs: Long = 45_000): List<MessageEntity> =
        try {
            withTimeout(timeoutMs) {
                search.search(token, bufferId).first { hits ->
                    hits.count { it.message.text.startsWith("$token row") } == count
                }.map { it.message }
                    .filter { it.text.startsWith("$token row") }
                    .also { rows ->
                        check(rows.map { it.id }.distinct().size == count) { "fixture run contains duplicate event ids" }
                        check(rows.map { it.msgid }.distinct().size == count) { "fixture run contains duplicate msgids" }
                        check(rows.map { it.text }.distinct().size == count) { "fixture run contains duplicate bodies" }
                        val ordered = rows.sortedBy { it.text.substringAfter("$token ") }
                        check(ordered.zipWithNext().all { (older, newer) -> older.anchor() < newer.anchor() }) {
                            "fixture run is not in canonical chronological order"
                        }
                        milestones.record("history_run_canonical", "buffer=$bufferId count=$count")
                    }
            }
        } catch (timeout: TimeoutCancellationException) {
            milestones.record("history_run_timeout", "buffer=$bufferId count=$count")
            throw AssertionError("canonical history row window timed out for buffer=$bufferId count=$count", timeout)
        }

    private fun MessageEntity.anchor(): TimelineAnchor = TimelineAnchor(serverTime, id, timelineOrder)
}
