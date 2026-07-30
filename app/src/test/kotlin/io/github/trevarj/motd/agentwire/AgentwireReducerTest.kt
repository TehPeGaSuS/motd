package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentwireReducerTest {
    @Test
    fun `bootstrap snapshots establish binding settings queue and advertised actions`() {
        val reducer = AgentwireReducer()
        var state = AgentwireUiState(syncing = true)
        state = reducer.reduce(state, event("agent.hello", epoch = "epoch-1", data = buildJsonObject {
            put("epoch", "epoch-1")
            put("backend", "codex")
            put("actions", JsonArray(listOf(JsonPrimitive("turn.prompt"), JsonPrimitive("history.request"))))
            put("settings", JsonArray(listOf(JsonPrimitive("delivery"), JsonPrimitive("model"))))
            put("settingOptions", buildJsonObject {
                put("model", JsonArray(listOf(buildJsonObject {
                    put("value", "gpt-test")
                    put("label", "GPT Test")
                    put("efforts", JsonArray(listOf(JsonPrimitive("low"), JsonPrimitive("high"))))
                    put("defaultEffort", "high")
                    put("default", true)
                })))
            })
        }))
        state = reducer.reduce(state, event("channel.snapshot", sid = "s1", data = buildJsonObject {
            put("binding", buildJsonObject { put("sid", "s1"); put("cwd", "/work") })
            put("busy", true)
            put("tid", "t1")
            put("queue", JsonArray(listOf(buildJsonObject {
                put("iid", "q1"); put("sid", "s1"); put("position", 0); put("content", "later")
            })))
        }))

        assertEquals("epoch-1", state.epoch)
        assertEquals("s1", state.activeSid)
        assertEquals("/work", state.cwd)
        assertTrue(state.busy)
        assertEquals("later", state.queue.single().content)
        assertTrue("turn.prompt" in state.actions)
        assertEquals(setOf("delivery", "model"), state.supportedSettings)
        assertEquals("gpt-test", state.modelOptions.single().value)
        assertEquals(listOf("low", "high"), state.modelOptions.single().efforts)
        assertTrue(state.modelOptions.single().default)
        assertFalse(state.syncing)
    }

    @Test
    fun `session pages merge for a workspace hierarchy`() {
        val reducer = AgentwireReducer()
        var state = reducer.reduce(AgentwireUiState(), event("session.page", data = buildJsonObject {
            put("cwd", "/work/one")
            put("items", JsonArray(listOf(buildJsonObject {
                put("sid", "s1"); put("cwd", "/work/one"); put("title", "One")
            })))
        }))
        state = reducer.reduce(state, event("session.page", data = buildJsonObject {
            put("cwd", "/work/two")
            put("items", JsonArray(listOf(buildJsonObject {
                put("sid", "s2"); put("cwd", "/work/two"); put("title", "Two")
            })))
        }))

        assertEquals(listOf("s1", "s2"), state.sessions.map(AgentwireListItem::id))
        assertEquals(setOf("/work/one", "/work/two"), state.loadedSessionDirectories)
    }

    @Test
    fun `continued session pages append within the same directory`() {
        val reducer = AgentwireReducer()
        var state = reducer.reduce(AgentwireUiState(), event("session.page", data = buildJsonObject {
            put("cwd", "/work")
            put("items", JsonArray(listOf(buildJsonObject {
                put("sid", "s1"); put("cwd", "/work"); put("title", "One")
            })))
        }))
        state = reducer.reduce(state, event("session.page", data = buildJsonObject {
            put("cwd", "/work")
            put("cursor", "100")
            put("items", JsonArray(listOf(buildJsonObject {
                put("sid", "s2"); put("cwd", "/work"); put("title", "Two")
            })))
        }))

        assertEquals(listOf("s1", "s2"), state.sessions.map(AgentwireListItem::id))
    }

    @Test
    fun `workspace pages retain lazy directory hierarchy`() {
        val reducer = AgentwireReducer()
        var state = reducer.reduce(AgentwireUiState(), event("workspace.page", data = buildJsonObject {
            put("items", JsonArray(listOf(buildJsonObject {
                put("path", "/work"); put("name", "work"); put("hasChildren", true)
            })))
        }))
        state = reducer.reduce(state, event("workspace.page", data = buildJsonObject {
            put("parent", "/work")
            put("items", JsonArray(listOf(buildJsonObject {
                put("path", "/work/project"); put("name", "project"); put("hasChildren", false)
            })))
        }))

        assertEquals("/work", state.workspaceChildren.getValue("").single().id)
        assertEquals("/work/project", state.workspaceChildren.getValue("/work").single().id)
    }

    @Test
    fun `revision ordering deduplicates stale queue updates and snapshots reconcile`() {
        val reducer = AgentwireReducer()
        var state = AgentwireUiState()
        state = reducer.reduce(state, event("queue.item.added", iid = "q1", rev = 2, data = queue("q1", "new", 0)))
        state = reducer.reduce(state, event("queue.item.updated", iid = "q1", rev = 1, data = queue("q1", "old", 0)))
        assertEquals("new", state.queue.single().content)

        state = reducer.reduce(state, event("queue.snapshot", data = buildJsonObject {
            put("items", JsonArray(listOf(queue("q2", "snapshot", 0))))
        }))
        assertEquals(listOf("q2"), state.queue.map(AgentwireQueueItem::iid))
    }

    @Test
    fun `binding detach clears the active session`() {
        val reducer = AgentwireReducer()
        val state = reducer.reduce(
            AgentwireUiState(activeSid = "s1", cwd = "/work", busy = true, currentTid = "t1"),
            event("binding.changed", data = buildJsonObject { put("sid", JsonNull) }),
        )

        assertEquals(null, state.activeSid)
        assertEquals(null, state.cwd)
        assertFalse(state.busy)
        assertEquals(null, state.currentTid)
    }

    @Test
    fun `turn assistant plan tool usage and request families reduce into harness state`() {
        val reducer = AgentwireReducer()
        var state = AgentwireUiState(activeSid = "s1")
        val kinds = listOf(
            "turn.started", "assistant.delta", "assistant.completed", "plan.updated", "tool.started",
            "tool.updated", "tool.completed", "usage.updated", "approval.review.started",
            "approval.review.completed", "turn.completed",
        )
        kinds.forEach { kind ->
            state = reducer.reduce(state, event(kind, sid = "s1", tid = "t1", iid = "i1", data = buildJsonObject {
                put("content", if (kind == "assistant.delta") "part" else "final")
                put("summary", "summary")
                put("kind", "shell")
                put("success", true)
            }))
        }
        state = reducer.reduce(state, event("request.opened", sid = "s1", rid = "r1", data = buildJsonObject {
            put("type", "approval"); put("summary", "Run command"); put("redacted", false); put("inactive", false)
        }))

        assertFalse(state.busy)
        assertTrue(state.timeline.any { it.kind == "assistant.completed" && it.body == "final" })
        assertTrue(state.timeline.any { it.kind == "tool.completed" && it.success == true })
        assertEquals("r1", state.requests.single().rid)

        state = reducer.reduce(state, event("request.resolved", rid = "r1"))
        assertTrue(state.requests.isEmpty())
    }

    @Test
    fun `replayed UUID is idempotent and action outcomes are never retried by reducer`() {
        val reducer = AgentwireReducer()
        val completed = event("assistant.completed", tid = "t1", data = buildJsonObject { put("content", "once") })
        var state = reducer.reduce(AgentwireUiState(), completed)
        state = reducer.reduce(state, completed.copy(history = true))
        assertEquals(1, state.timeline.size)

        state = reducer.reduce(state, event("action.accepted", reply = "a1"))
        state = reducer.reduce(state, event("action.uncertain", reply = "a1"))
        assertEquals("uncertain", state.actionStatus["a1"])
    }

    private fun queue(id: String, content: String, position: Int) = buildJsonObject {
        put("iid", id); put("content", content); put("position", position); put("sid", "s1")
    }

    private fun event(
        kind: String,
        sid: String? = null,
        tid: String? = null,
        iid: String? = null,
        rid: String? = null,
        rev: Long? = null,
        reply: String? = null,
        epoch: String = "epoch",
        data: kotlinx.serialization.json.JsonObject? = null,
    ) = AgentwireEnvelope(
        kind, "event", UUID.randomUUID().toString(), 1, "bridge", epoch, sid = sid,
        tid = tid, iid = iid, rid = rid, rev = rev, reply = reply, data = data,
    )
}
