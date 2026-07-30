package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class AgentwireGate { LOADING, ORDINARY, BLOCKED, ACTIVE }

data class AgentwireListItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class AgentwireQueueItem(
    val iid: String,
    val content: String,
    val position: Int,
    val sid: String? = null,
    val rev: Long? = null,
)

data class AgentwireRequest(
    val rid: String,
    val type: String,
    val summary: String?,
    val redacted: Boolean,
    val inactive: Boolean,
    val canSkip: Boolean,
    val questions: List<AgentwireQuestion> = emptyList(),
    val sid: String? = null,
)

data class AgentwireQuestion(
    val id: String,
    val header: String?,
    val prompt: String,
    val options: List<String>,
    val multiple: Boolean,
    val custom: Boolean,
)

data class AgentwireTimelineItem(
    val id: String,
    val kind: String,
    val at: Long,
    val sid: String?,
    val tid: String?,
    val title: String,
    val body: String?,
    val running: Boolean = false,
    val success: Boolean? = null,
    val historical: Boolean = false,
    val data: JsonObject = JsonObject(emptyMap()),
)

data class AgentwireUiState(
    val gate: AgentwireGate = AgentwireGate.LOADING,
    val channel: String = "",
    val title: String = "Agentwire",
    val controllerAccount: String? = null,
    val backend: String? = null,
    val missingCaps: Set<String> = emptySet(),
    val connected: Boolean = false,
    val syncing: Boolean = false,
    val epoch: String? = null,
    val botAccount: String? = null,
    val activeSid: String? = null,
    val cwd: String? = null,
    val busy: Boolean = false,
    val currentTid: String? = null,
    val actions: Set<String> = emptySet(),
    val settings: Map<String, String> = emptyMap(),
    val workspaces: List<AgentwireListItem> = emptyList(),
    val sessions: List<AgentwireListItem> = emptyList(),
    val queue: List<AgentwireQueueItem> = emptyList(),
    val requests: List<AgentwireRequest> = emptyList(),
    val timeline: List<AgentwireTimelineItem> = emptyList(),
    val actionStatus: Map<String, String> = emptyMap(),
    val historyLoading: Boolean = false,
    val historyPage: String? = null,
    val olderHistoryAvailable: Boolean = true,
    val error: String? = null,
    val transcriptOverride: Boolean = false,
    val autoReviewConfirmed: Boolean = false,
)

class AgentwireReducer {
    private val seen = LinkedHashSet<String>()
    private val revisions = HashMap<String, Long>()

    fun reset() {
        seen.clear()
        revisions.clear()
    }

    fun reduce(state: AgentwireUiState, envelope: AgentwireEnvelope): AgentwireUiState {
        if (!seen.add(envelope.id)) return state
        val entityKey = listOfNotNull(envelope.kind.substringBeforeLast('.'), envelope.sid, envelope.iid, envelope.rid)
            .joinToString(":")
        envelope.rev?.let { rev ->
            val prior = revisions[entityKey]
            if (prior != null && rev <= prior) return state
            revisions[entityKey] = rev
        }
        val data = envelope.data ?: JsonObject(emptyMap())
        return when (envelope.kind) {
            "agent.hello" -> state.copy(
                syncing = true,
                epoch = envelope.epoch ?: data.string("epoch"),
                backend = data.string("backend") ?: state.backend,
                actions = data.stringList("actions").toSet(),
                settings = data.objectStrings("settings"),
                error = null,
            )
            "channel.snapshot" -> state.copy(
                syncing = false,
                activeSid = data.obj("binding")?.string("sid"),
                cwd = data.obj("binding")?.string("cwd"),
                busy = data.bool("busy") ?: false,
                currentTid = data.string("tid"),
                settings = data.objectStrings("settings").ifEmpty { state.settings },
                queue = data.array("queue")?.mapNotNull(::queueItem).orEmpty(),
            )
            "binding.changed" -> state.copy(
                activeSid = envelope.sid ?: data.string("sid") ?: data.obj("session")?.string("sid"),
                cwd = data.string("cwd") ?: data.obj("session")?.string("cwd"),
            )
            "session.snapshot", "session.status" -> state.copy(
                activeSid = envelope.sid ?: state.activeSid,
                cwd = data.string("cwd") ?: state.cwd,
                busy = data.bool("busy") ?: state.busy,
                currentTid = envelope.tid ?: data.string("tid") ?: state.currentTid,
                settings = data.objectStrings("settings").ifEmpty { state.settings },
            )
            "workspace.page" -> state.copy(workspaces = workspaceItems(data))
            "session.page" -> state.copy(sessions = pageItems(data, "sid"))
            "history.begin" -> state.copy(historyLoading = true, historyPage = data.string("page"))
            "history.end" -> state.copy(
                historyLoading = false,
                historyPage = data.string("page") ?: state.historyPage,
                olderHistoryAvailable = (data.int("count") ?: 0) >= 200,
            )
            "action.accepted", "action.succeeded", "action.failed", "action.uncertain" -> {
                val status = envelope.kind.substringAfter("action.")
                state.copy(
                    actionStatus = envelope.reply?.let { state.actionStatus + (it to status) } ?: state.actionStatus,
                    error = if (envelope.kind == "action.failed") data.string("message") ?: "Action failed" else state.error,
                )
            }
            "queue.snapshot" -> state.copy(queue = data.array("items")?.mapNotNull(::queueItem).orEmpty())
            "queue.item.added", "queue.item.updated", "queue.item.moved" -> {
                val item = queueItem(data) ?: return state
                state.copy(queue = (state.queue.filterNot { it.iid == item.iid } + item).sortedBy { it.position })
            }
            "queue.item.removed" -> state.copy(queue = state.queue.filterNot { it.iid == envelope.iid || it.iid == data.string("iid") })
            "request.opened" -> {
                val request = request(envelope, data) ?: return state
                state.copy(
                    requests = state.requests.filterNot { it.rid == request.rid } + request,
                    timeline = state.timeline.upsert(envelope.timelineItem()),
                )
            }
            "request.resolved" -> state.copy(
                requests = state.requests.filterNot { it.rid == envelope.rid || it.rid == data.string("rid") },
                timeline = state.timeline.upsert(envelope.timelineItem()),
            )
            "turn.started" -> state.copy(
                busy = true, currentTid = envelope.tid,
                timeline = state.timeline.upsert(envelope.timelineItem(running = true)),
            )
            "turn.completed", "turn.failed" -> state.copy(
                busy = false, currentTid = null,
                timeline = state.timeline.upsert(envelope.timelineItem(success = envelope.kind == "turn.completed")),
            )
            "assistant.delta" -> state.copy(timeline = state.timeline.appendDelta(envelope))
            "assistant.completed", "plan.updated", "tool.started", "tool.updated", "tool.completed",
            "usage.updated", "approval.review.started", "approval.review.completed" -> state.copy(
                timeline = state.timeline.upsert(
                    envelope.timelineItem(
                        running = envelope.kind.endsWith("started") || envelope.kind.endsWith("updated"),
                        success = if (envelope.kind == "tool.completed") data.bool("success") else null,
                    ),
                ),
            )
            else -> state
        }
    }

    private fun pageItems(data: JsonObject, identity: String): List<AgentwireListItem> {
        val values = data.array("items") ?: data.array(if (identity == "sid") "sessions" else "workspaces") ?: return emptyList()
        return values.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.string(identity) ?: return@mapNotNull null
            AgentwireListItem(id, item.string("title") ?: id, item.string("cwd"), item)
        }
    }

    private fun workspaceItems(data: JsonObject): List<AgentwireListItem> = data.array("items").orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val path = item.string("path") ?: return@mapNotNull null
        AgentwireListItem(path, item.string("name") ?: path, path, item)
    }
}

private fun queueItem(element: JsonElement): AgentwireQueueItem? {
    val data = element as? JsonObject ?: return null
    return AgentwireQueueItem(
        iid = data.string("iid") ?: return null,
        content = data.string("content").orEmpty(),
        position = data.int("position") ?: 0,
        sid = data.string("sid"),
        rev = data["rev"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() },
    )
}

private fun request(envelope: AgentwireEnvelope, data: JsonObject): AgentwireRequest? {
    val rid = envelope.rid ?: data.string("rid") ?: return null
    val questions = data.array("questions").orEmpty().mapNotNull { element ->
        val question = element as? JsonObject ?: return@mapNotNull null
        AgentwireQuestion(
            id = question.string("id") ?: return@mapNotNull null,
            header = question.string("header"),
            prompt = question.string("prompt").orEmpty(),
            options = question.stringList("options"),
            multiple = question.bool("multiple") ?: false,
            custom = question.bool("custom") ?: false,
        )
    }
    return AgentwireRequest(
        rid, data.string("type") ?: "approval", data.string("summary"),
        data.bool("redacted") ?: false, data.bool("inactive") ?: false,
        data.bool("canSkip") ?: false, questions, envelope.sid ?: data.string("sid"),
    )
}

private fun AgentwireEnvelope.timelineItem(running: Boolean = false, success: Boolean? = null): AgentwireTimelineItem {
    val payload = data ?: JsonObject(emptyMap())
    val title = when {
        kind.startsWith("assistant.") -> "Assistant"
        kind.startsWith("turn.") -> kind.substringAfter('.').replaceFirstChar(Char::uppercase)
        kind.startsWith("tool.") -> payload.string("kind") ?: "Tool"
        kind == "plan.updated" -> "Plan"
        kind == "usage.updated" -> "Usage"
        kind.startsWith("request.") -> payload.string("type")?.replaceFirstChar(Char::uppercase) ?: "Request"
        else -> kind
    }
    val body = payload.string("content") ?: payload.string("summary") ?: payload.string("message")
    return AgentwireTimelineItem(id, kind, at, sid, tid, title, body, running, success, history == true, payload)
}

private fun List<AgentwireTimelineItem>.upsert(item: AgentwireTimelineItem): List<AgentwireTimelineItem> {
    val stableId = item.tid?.let { tid ->
        when {
            item.kind.startsWith("assistant.") -> "assistant:$tid"
            item.kind.startsWith("tool.") -> item.data.string("id")?.let { "tool:$tid:$it" }
            item.kind.startsWith("turn.") -> "turn:$tid"
            else -> null
        }
    }
    if (stableId == null) return (this + item).sortedBy(AgentwireTimelineItem::at)
    val existing = indexOfFirst { old ->
        when {
            stableId.startsWith("assistant:") -> old.tid == item.tid && old.kind.startsWith("assistant.")
            stableId.startsWith("turn:") -> old.tid == item.tid && old.kind.startsWith("turn.")
            else -> old.tid == item.tid && old.kind.startsWith("tool.") && old.data.string("id") == item.data.string("id")
        }
    }
    return if (existing < 0) (this + item).sortedBy(AgentwireTimelineItem::at)
    else toMutableList().also { it[existing] = item }
}

private fun List<AgentwireTimelineItem>.appendDelta(envelope: AgentwireEnvelope): List<AgentwireTimelineItem> {
    val delta = envelope.data?.string("content").orEmpty()
    val existing = indexOfLast { it.tid == envelope.tid && it.kind.startsWith("assistant.") }
    if (existing < 0) return upsert(envelope.timelineItem(running = true))
    return toMutableList().also { items ->
        val prior = items[existing]
        items[existing] = prior.copy(body = prior.body.orEmpty() + delta, running = true)
    }
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
internal fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray
internal fun JsonObject.stringList(key: String): List<String> = array(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
internal fun JsonObject.objectStrings(key: String): Map<String, String> = obj(key).orEmpty().mapNotNull { (name, value) ->
    (value as? JsonPrimitive)?.contentOrNull?.let { name to it }
}.toMap()
