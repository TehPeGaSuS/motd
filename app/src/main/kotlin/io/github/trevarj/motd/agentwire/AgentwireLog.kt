package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import kotlinx.serialization.json.JsonObject

/**
 * Bound of the in-memory payload log. Eviction is lossy on purpose: anything dropped is still
 * recoverable through a resync snapshot or a `history.request` backfill.
 */
internal const val AGENTWIRE_LOG_CAPACITY = 2_000

/** Kind prefixes whose full payload is worth keeping after the timeline has evicted the item. */
private val AGENTWIRE_LOG_KINDS =
    listOf(
        "tool.",
        "assistant.completed",
        "user.prompt",
        "plan.updated",
        "turn.failed",
        "request.",
    )

data class AgentwireLogEntry(
    val id: String,
    val at: Long,
    val kind: String,
    val title: String,
    val sid: String? = null,
    val tid: String? = null,
    val input: String? = null,
    val output: String? = null,
    val diff: String? = null,
    val status: String? = null,
    val exitCode: Int? = null,
    val success: Boolean? = null,
    /** Non-tool content (assistant text, plan preview, request summary) when it was captured. */
    val body: String? = null,
)

/**
 * Bounded ring of the most recent Agentwire payloads, keyed so a tool's later lifecycle event
 * replaces its earlier one in place rather than accumulating three rows per tool call.
 *
 * The log is additive to the timeline, not a replacement for it: the timeline keeps its own
 * (truncated) copy of a payload while the item survives [AGENTWIRE_TIMELINE_CAP], and the log
 * answers for it long after that item has been evicted.
 */
class AgentwireLogStore(
    private val capacity: Int = AGENTWIRE_LOG_CAPACITY,
) {
    // Insertion-ordered: re-putting an existing key keeps its original position, so an updated
    // entry stays where it started instead of jumping ahead of newer events.
    private val items = LinkedHashMap<String, AgentwireLogEntry>()

    @Synchronized
    fun append(entry: AgentwireLogEntry) {
        items[entry.id] = entry
        while (items.size > capacity) {
            val oldest = items.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    /** Newest-first snapshot; the UI reads this only while the log sheet is open. */
    @Synchronized
    fun entries(): List<AgentwireLogEntry> = items.values.reversed()

    @Synchronized
    fun clear() {
        items.clear()
    }
}

/** Captures an envelope's full payload, whatever the timeline card ends up showing of it. */
internal fun AgentwireLogStore.capture(envelope: AgentwireEnvelope): Boolean {
    val entry = agentwireLogEntry(envelope) ?: return false
    append(entry)
    return true
}

internal fun agentwireLogEntry(envelope: AgentwireEnvelope): AgentwireLogEntry? {
    if (AGENTWIRE_LOG_KINDS.none { envelope.kind.startsWith(it) }) return null
    val payload = envelope.data ?: JsonObject(emptyMap())
    val tool = envelope.kind.startsWith("tool.")
    return AgentwireLogEntry(
        id = envelope.logId(),
        at = envelope.at,
        kind = envelope.kind,
        title = logTitle(envelope.kind, payload),
        sid = envelope.sid,
        tid = envelope.tid,
        input = payload.string("input"),
        output = payload.string("output"),
        diff = payload.string("diff"),
        status = payload.string("status"),
        exitCode = payload.int("exitCode"),
        success = payload.bool("success"),
        body = if (tool) null else logBody(envelope.kind, payload),
    )
}

/** Tool lifecycle events collapse onto one entry; every other kind is one row per envelope. */
private fun AgentwireEnvelope.logId(): String =
    if (kind.startsWith("tool.")) {
        "tool:$sid:$tid:${iid ?: data?.string("id") ?: id}"
    } else {
        id
    }

/**
 * Mirrors the timeline's title derivation deliberately rather than sharing it: the log is a
 * separate, longer-lived record, and coupling it to the reducer's internals would make either
 * one hard to change without silently rewriting the other.
 */
private fun logTitle(
    kind: String,
    payload: JsonObject,
): String =
    when {
        kind == "user.prompt" -> "You"
        kind.startsWith("assistant.") -> "Assistant"
        kind.startsWith("turn.") -> kind.substringAfter('.').replaceFirstChar(Char::uppercase)
        kind.startsWith("tool.") -> payload.string("label") ?: payload.string("kind") ?: "Tool"
        kind == "plan.updated" -> "Plan"
        kind.startsWith("request.") -> payload.string("type")?.replaceFirstChar(Char::uppercase) ?: "Request"
        else -> kind
    }

/** Non-tool content only: a tool's text is addressed by its own input/output/diff fields. */
private fun logBody(
    kind: String,
    payload: JsonObject,
): String? =
    when {
        kind == "plan.updated" -> logPlanPreview(payload)
        payload.bool("omitted") == true -> "Content omitted because it may contain a secret"
        else -> payload.string("content") ?: payload.string("summary") ?: payload.string("message")
    }

private fun logPlanPreview(payload: JsonObject): String? {
    val summary = payload.string("summary")
    val completed = payload.int("completedSteps")
    val total = payload.int("totalSteps")
    val progress =
        if (completed != null && total != null && total > 0) {
            "$completed of $total steps complete"
        } else {
            null
        }
    return listOfNotNull(summary, progress).takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}

/**
 * Filters a log snapshot: case-insensitive substring across every captured text field, kind prefix
 * membership (`"tool"`, `"assistant"`, …) and an optional session.
 */
fun agentwireLogQuery(
    entries: List<AgentwireLogEntry>,
    text: String = "",
    kinds: Set<String> = emptySet(),
    sid: String? = null,
): List<AgentwireLogEntry> =
    entries.filter { entry ->
        (sid == null || entry.sid == sid) &&
            (kinds.isEmpty() || kinds.any { entry.kind.startsWith(it) }) &&
            (
                text.isBlank() ||
                    sequenceOf(entry.title, entry.input, entry.output, entry.diff, entry.body)
                        .any { it?.contains(text, ignoreCase = true) == true }
            )
    }
