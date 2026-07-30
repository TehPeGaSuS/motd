package io.github.trevarj.motd.agentwire

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_TAG
import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.AgentwireReassembler
import io.github.trevarj.motd.irc.agentwire.AgentwireValue
import io.github.trevarj.motd.irc.agentwire.agentwireMissingCaps
import io.github.trevarj.motd.irc.agentwire.decodeAgentwireValue
import io.github.trevarj.motd.irc.agentwire.parseAgentwireTopic
import io.github.trevarj.motd.irc.agentwire.readablePreview
import io.github.trevarj.motd.irc.agentwire.sendAgentwire
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.canSendClientTag
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.messageContextOrNull
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.ui.nav.ChatRoute
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val AGENTWIRE_INITIAL_HISTORY_SIZE = 20
private const val AGENTWIRE_HISTORY_PAGE_SIZE = 50
private const val AGENTWIRE_SYNC_RETRY_INITIAL_MS = 1_000L
private const val AGENTWIRE_SYNC_RETRY_MAX_MS = 10_000L

internal fun acceptsAgentwireEpoch(envelope: AgentwireEnvelope, currentEpoch: String?): Boolean =
    envelope.kind == "agent.hello" || envelope.history == true || currentEpoch == null ||
        envelope.epoch == currentEpoch

internal suspend fun retryAgentwireSync(
    isReady: () -> Boolean,
    issue: suspend (String) -> Unit,
    nextId: () -> String = { UUID.randomUUID().toString() },
    pause: suspend (Long) -> Unit = { delay(it) },
) {
    var retryDelay = AGENTWIRE_SYNC_RETRY_INITIAL_MS
    while (!isReady()) {
        issue(nextId())
        if (isReady()) return
        pause(retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(AGENTWIRE_SYNC_RETRY_MAX_MS)
    }
}

@HiltViewModel
class AgentwireViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val prefs: AgentwirePrefs,
    private val buffers: BufferRepository,
    private val connections: ConnectionManager,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ChatRoute>()
    private val instance = UUID.randomUUID().toString()
    private val reducer = AgentwireReducer()
    private val reassembler = AgentwireReassembler()
    private val _state = MutableStateFlow(AgentwireUiState())
    val state: StateFlow<AgentwireUiState> = _state.asStateFlow()
    private var sessionJob: Job? = null
    private var client: IrcClient? = null
    private var syncId: String? = null
    private var syncHello = false
    private var syncSnapshot = false
    private var historyRequested = false
    private val autoReviewConfirmedSessions = HashSet<String>()

    init {
        viewModelScope.launch {
            combine(
                prefs.enabled,
                buffers.observeBuffer(route.bufferId),
                connections.connectionStates,
            ) { enabled, buffer, states -> Triple(enabled, buffer, buffer?.let { states[it.networkId] }) }
                .collect { (enabled, buffer, connection) ->
                    val topic = buffer?.topic?.let(::parseAgentwireTopic)
                    val ready = connection as? IrcClientState.Ready
                    val missing = ready?.let {
                        agentwireMissingCaps(it.caps) + if (canSendClientTag(it.caps, it.isupport, AGENTWIRE_TAG)) {
                            emptySet()
                        } else {
                            setOf("CLIENTTAGDENY:$AGENTWIRE_TAG")
                        }
                    }.orEmpty()
                    val gate = when {
                        !enabled || buffer?.type != BufferType.CHANNEL || topic == null -> AgentwireGate.ORDINARY
                        ready != null && missing.isNotEmpty() -> AgentwireGate.BLOCKED
                        else -> AgentwireGate.ACTIVE
                    }
                    _state.update {
                        it.copy(
                            gate = gate,
                            channel = buffer?.displayName.orEmpty(),
                            title = topic?.title ?: buffer?.displayName ?: "Agentwire",
                            controllerAccount = topic?.account,
                            backend = topic?.backend,
                            missingCaps = missing,
                            connected = ready != null,
                        )
                    }
                    val nextClient = buffer?.let { connections.clientFor(it.networkId) }
                    if (gate == AgentwireGate.ACTIVE && ready != null && nextClient != null && nextClient !== client) {
                        startSession(nextClient)
                    } else if (gate != AgentwireGate.ACTIVE || ready == null) {
                        stopSession(disconnected = client != null)
                    }
                }
        }
    }

    fun viewTranscript() = _state.update { it.copy(transcriptOverride = true) }
    fun returnToHarness() = _state.update { it.copy(transcriptOverride = false) }
    fun clearError() = _state.update { it.copy(error = null) }

    fun submit(content: String) {
        if (content.isBlank()) return
        val kind = if (_state.value.busy && _state.value.settings["delivery"] == "steer") "turn.steer" else "turn.prompt"
        val data = buildJsonObject { put("content", content) }
        val localId = UUID.randomUUID().toString()
        _state.update { state ->
            state.copy(timeline = state.timeline + AgentwireTimelineItem(
                localId, "user.prompt", System.currentTimeMillis(), state.activeSid, state.currentTid,
                if (kind == "turn.steer") "Steer" else "You", content,
            ))
        }
        sendAction(kind, data = data, sid = _state.value.activeSid, id = localId)
    }

    fun cancelTurn() = sendAction("turn.cancel", sid = _state.value.activeSid, tid = _state.value.currentTid)
    fun clearQueue() = sendAction("queue.clear", sid = _state.value.activeSid)
    fun editQueue(iid: String, content: String) = sendAction(
        "queue.edit", data = buildJsonObject { put("content", content) }, sid = _state.value.activeSid, iid = iid,
    )
    fun moveQueue(iid: String, position: Int) = sendAction(
        "queue.move", data = buildJsonObject { put("position", position) }, sid = _state.value.activeSid, iid = iid,
    )
    fun deleteQueue(iid: String) = sendAction("queue.delete", sid = _state.value.activeSid, iid = iid)
    fun listWorkspaces(parent: String? = null) = sendAction(
        "workspace.list.request", data = parent?.let { buildJsonObject { put("parent", it) } },
    )
    fun listSessions(cwd: String? = null, cursor: String? = null) = sendAction(
        "session.list.request", data = buildJsonObject {
            cwd?.let { put("cwd", it) }
            cursor?.let { put("cursor", it) }
        }.takeIf { it.isNotEmpty() },
    )
    fun refreshSessionBrowser() {
        _state.update {
            it.copy(
                workspaceChildren = emptyMap(),
                sessions = emptyList(),
                loadedSessionDirectories = emptySet(),
            )
        }
        listWorkspaces()
        listSessions()
    }
    fun expandWorkspace(path: String, hasChildren: Boolean = true) {
        if (hasChildren) listWorkspaces(path)
        listSessions(path)
    }
    fun createSession(cwd: String) = sendAction("session.create", buildJsonObject { put("cwd", cwd) })
    fun attachSession(sid: String, cwd: String? = null) = sendAction(
        "session.attach", cwd?.let { buildJsonObject { put("cwd", it) } }, sid = sid,
    )
    fun detachSession() = sendAction("session.detach", sid = _state.value.activeSid)
    fun renameSession(sid: String, title: String) = sendAction(
        "session.rename", buildJsonObject { put("title", title) }, sid = sid,
    )
    fun forkSession(sid: String) = sendAction("session.fork", sid = sid)
    fun archiveSession(sid: String, archived: Boolean) = sendAction(
        if (archived) "session.archive" else "session.unarchive", sid = sid,
    )
    fun updateSettings(values: Map<String, String>) = sendAction(
        "settings.update", JsonObject(values.mapValues { JsonPrimitive(it.value) }), sid = _state.value.activeSid,
    )
    fun enableAutoReview() {
        _state.value.activeSid?.let(autoReviewConfirmedSessions::add)
        _state.update { it.copy(autoReviewConfirmed = true) }
        updateSettings(mapOf("approvalReviewer" to "auto_review"))
    }
    fun disableAutoReview() {
        _state.value.activeSid?.let(autoReviewConfirmedSessions::remove)
        _state.update { it.copy(autoReviewConfirmed = false) }
        updateSettings(mapOf("approvalReviewer" to "manual"))
    }
    fun respondApproval(rid: String, allow: Boolean) = sendAction(
        "request.respond", buildJsonObject { put("allow", allow) }, sid = _state.value.activeSid, rid = rid,
    )
    fun respondQuestions(rid: String, answers: List<JsonElement>) = sendAction(
        "request.respond", buildJsonObject { put("answers", JsonArray(answers)) }, sid = _state.value.activeSid, rid = rid,
    )
    fun skipRequest(rid: String) = sendAction("request.skip", sid = _state.value.activeSid, rid = rid)
    fun loadOlderHistory() {
        sendAction("history.request", buildJsonObject {
            _state.value.historyBeforeAt?.let { put("beforeAt", it) }
            put("limit", AGENTWIRE_HISTORY_PAGE_SIZE)
        })
    }

    private fun startSession(next: IrcClient) {
        stopSession(disconnected = client != null)
        client = next
        reducer.reset()
        reassembler.clear()
        syncHello = false
        syncSnapshot = false
        historyRequested = false
        _state.update {
            it.copy(
                syncing = true,
                epoch = null,
                botAccount = null,
                error = null,
                activeSid = null,
                cwd = null,
                busy = false,
                currentTid = null,
                actions = emptySet(),
                supportedSettings = emptySet(),
                modelOptions = emptyList(),
                workspaceChildren = emptyMap(),
                sessions = emptyList(),
                loadedSessionDirectories = emptySet(),
                queue = emptyList(),
                requests = emptyList(),
                timeline = it.timeline.filter { item -> item.kind == "user.prompt" },
                historyBeforeAt = null,
                autoReviewConfirmed = false,
            )
        }
        sessionJob = viewModelScope.launch {
            launch { next.broadcastEvents.collect(::ingest) }
            // Let the hot-flow collector attach before sync.request is emitted.
            delay(1)
            retryAgentwireSync(
                isReady = { syncHello && syncSnapshot },
                issue = { id ->
                    // Set the correlation ID before sending so a fast reply cannot race past it.
                    syncId = id
                    sendActionInternal("sync.request", id = id)
                },
            )
        }
    }

    private fun stopSession(disconnected: Boolean) {
        sessionJob?.cancel()
        sessionJob = null
        client = null
        reassembler.clear()
        if (disconnected) {
            val uncertain = _state.value.actionStatus.mapValues { (_, status) ->
                if (status == "sent" || status == "accepted") "outcome unknown" else status
            }
            _state.update { it.copy(syncing = false, epoch = null, botAccount = null, actionStatus = uncertain) }
        }
    }

    private suspend fun ingest(event: IrcEvent) {
        if (event is IrcEvent.PlaybackBatch || event is IrcEvent.HistoryBatch || event is IrcEvent.ReplayBatch) return
        val context = event.messageContextOrNull() ?: return
        val raw = context.clientTags[AGENTWIRE_TAG] ?: return
        val target = when (event) {
            is IrcEvent.ChatMessage -> event.target
            is IrcEvent.TagMessage -> event.target
            else -> return
        }
        if (!target.equals(_state.value.channel, ignoreCase = true)) return
        val account = context.account?.takeUnless { it == "*" } ?: return
        if (account.equals(_state.value.controllerAccount, ignoreCase = true)) return
        val decoded = decodeAgentwireValue(raw).getOrElse {
            _state.update { state -> state.copy(error = "Invalid Agentwire message: ${it.message}") }
            return
        }
        val envelope = when (decoded) {
            is AgentwireValue.Envelope -> decoded.value
            is AgentwireValue.Fragment -> reassembler.accept(decoded.value).getOrElse {
                _state.update { state -> state.copy(error = "Invalid Agentwire fragments: ${it.message}") }
                return
            } ?: return
        }
        if (envelope.type != "event") return
        val pinned = _state.value.botAccount
        if (pinned == null) {
            if (envelope.kind != "agent.hello" || envelope.reply != syncId) return
            _state.update { it.copy(botAccount = account) }
        } else if (!account.equals(pinned, ignoreCase = true)) return
        val epoch = _state.value.epoch
        if (!acceptsAgentwireEpoch(envelope, epoch)) return
        _state.update { reducer.reduce(it, envelope) }
        if (envelope.kind == "session.page") {
            val next = envelope.data?.string("next")
            if (next != null) listSessions(envelope.data?.string("cwd"), next)
        }
        val activeSid = _state.value.activeSid
        _state.update {
            it.copy(autoReviewConfirmed = activeSid != null && activeSid in autoReviewConfirmedSessions)
        }
        if (envelope.reply == syncId && envelope.kind == "agent.hello") syncHello = true
        if (envelope.reply == syncId && envelope.kind == "channel.snapshot") syncSnapshot = true
        if (syncHello && syncSnapshot && !historyRequested) {
            historyRequested = true
            sendActionInternal(
                "history.request",
                buildJsonObject { put("limit", AGENTWIRE_INITIAL_HISTORY_SIZE) },
            )
        }
    }

    private fun sendAction(
        kind: String,
        data: JsonObject? = null,
        sid: String? = null,
        tid: String? = null,
        iid: String? = null,
        rid: String? = null,
        id: String = UUID.randomUUID().toString(),
    ) {
        viewModelScope.launch { sendActionInternal(kind, data, sid, tid, iid, rid, id) }
    }

    private suspend fun sendActionInternal(
        kind: String,
        data: JsonObject? = null,
        sid: String? = null,
        tid: String? = null,
        iid: String? = null,
        rid: String? = null,
        id: String = UUID.randomUUID().toString(),
    ): String? {
        val current = _state.value
        if (kind != "sync.request" && kind !in current.actions) return null
        val activeClient = client ?: return null
        val envelope = AgentwireEnvelope(
            kind = kind, type = "action", id = id, at = System.currentTimeMillis(), instance = instance,
            epoch = if (kind == "sync.request") null else current.epoch ?: return null,
            device = prefs.deviceId(), sid = sid, tid = tid, iid = iid, rid = rid, data = data,
        )
        val sent = runCatching {
            activeClient.sendAgentwire(current.channel, envelope, envelope.readablePreview())
        }.getOrElse {
            _state.update { state -> state.copy(error = it.message ?: "Unable to send Agentwire action") }
            false
        }
        if (sent && kind != "sync.request") {
            _state.update { it.copy(actionStatus = it.actionStatus + (id to "sent")) }
        }
        return id.takeIf { sent }
    }
}
