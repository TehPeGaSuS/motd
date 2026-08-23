package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.AgentwireReassembler
import io.github.trevarj.motd.irc.agentwire.AgentwireValue
import io.github.trevarj.motd.irc.agentwire.decodeAgentwireValue
import io.github.trevarj.motd.irc.agentwire.encodeAgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.parseAgentwireTopic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-implementation conformance against the upstream Agentwire reference implementation.
 *
 * Every envelope in the corpus was built and encoded by agentwire's own `protocol.py`, and every
 * expected state was produced by agentwire's own reference renderer. Disagreeing with this file
 * means disagreeing with the bridge, which is the failure mode that is otherwise only discoverable
 * against a live deployment. Regenerate with `test/agentwire/generate-conformance.py`.
 */
class AgentwireConformanceTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `every corpus envelope decodes and re-encodes byte for byte`() {
        CORPORA.forEach { name ->
            corpus(name).forEach { step ->
                val tag = step.tag
                val decoded = decodeAgentwireValue(tag)
                assertTrue("${step.kind} in $name failed to decode: ${decoded.exceptionOrNull()}", decoded.isSuccess)
                val envelope = (decoded.getOrThrow() as AgentwireValue.Envelope).value
                assertEquals("${step.kind} in $name did not survive a re-encode", tag, encodeAgentwireEnvelope(envelope))
            }
        }
    }

    @Test
    fun `the reducer agrees with the reference renderer at every step`() {
        CORPORA.forEach { name ->
            val reducer = AgentwireReducer()
            var state = activeState()
            corpus(name).forEach { step ->
                val envelope = (decodeAgentwireValue(step.tag).getOrThrow() as AgentwireValue.Envelope).value
                state = reducer.reduce(state, envelope)
                val where = "${step.kind} in $name"
                assertEquals("$where: epoch", step.state.text("epoch"), state.epoch)
                assertEquals("$where: backend", step.state.text("backend") ?: TOPIC_BACKEND, state.backend)
                assertEquals("$where: sid", step.state.text("sid"), state.activeSid)
                assertEquals("$where: tid", step.state.text("tid"), state.currentTid)
                assertEquals("$where: busy", step.state.flag("busy") ?: false, state.busy)
                assertEquals("$where: settings", step.state.strings("settings"), state.settings)
                assertEquals("$where: queue", step.state.queueIds(), state.queue.map { it.iid })
                assertEquals("$where: open requests", step.state.requestIds(), state.requests.map { it.rid }.sorted())
            }
        }
    }

    @Test
    fun `an oversized envelope fragments and reassembles to the same bytes on both sides`() {
        val document = json.parseToJsonElement(resource("fragmented.json")).jsonObject
        val expected = document.getValue("envelope").jsonPrimitive.content
        val fragments = document.getValue("fragments").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("the corpus must exercise real fragmentation", fragments.size > 1)

        val reassembler = AgentwireReassembler()
        var reassembled: AgentwireEnvelope? = null
        fragments.forEach { raw ->
            val fragment = (decodeAgentwireValue(raw).getOrThrow() as AgentwireValue.Fragment).value
            reassembled = reassembler.accept(fragment).getOrThrow() ?: reassembled
        }

        assertNotNull("fragments produced by the bridge must reassemble here", reassembled)
        assertEquals(expected, encodeAgentwireEnvelope(reassembled!!))
    }

    @Test
    fun `the corpus topic activates and advertises only what Claude accepts`() {
        val document = json.parseToJsonElement(resource("claude-session.json")).jsonObject
        val topic = parseAgentwireTopic(document.getValue("topic").jsonPrimitive.content)
        assertEquals(TOPIC_BACKEND, topic?.backend)
        assertEquals("agentwire", topic?.agentAccount)

        val hello = (decodeAgentwireValue(corpus("claude-session").first().tag).getOrThrow() as AgentwireValue.Envelope).value
        val state = AgentwireReducer().reduce(activeState(), hello)
        // Claude takes its model from deployment configuration, so the settings UI must be driven
        // by this list rather than by the full safe-setting vocabulary.
        assertEquals(setOf("delivery"), state.supportedSettings)
        // The abridged upstream fixtures omit `actions`; without it every outbound action is
        // refused before it reaches the wire, so this is the assertion that matters most.
        assertTrue("turn.prompt must be advertised", "turn.prompt" in state.actions)
        assertTrue("request.respond must be advertised", "request.respond" in state.actions)
        assertTrue(
            "optional lifecycle actions are not advertised by this bridge",
            "session.fork" !in state.actions,
        )
    }

    private fun activeState() =
        AgentwireUiState(
            gate = AgentwireGate.ACTIVE,
            channel = "#claude",
            controllerAccount = "trev",
            backendAccount = "agentwire",
            backend = TOPIC_BACKEND,
        )

    private data class Step(
        val kind: String,
        val tag: String,
        val state: JsonObject,
    )

    private fun corpus(name: String): List<Step> =
        json.parseToJsonElement(resource("$name.json")).jsonObject.getValue("steps").jsonArray.map { entry ->
            val step = entry.jsonObject
            Step(
                kind = step.getValue("kind").jsonPrimitive.content,
                tag = step.getValue("tag").jsonPrimitive.content,
                state = step.getValue("state").jsonObject,
            )
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.strings(key: String): Map<String, String> =
        (this[key] as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
            ?.toMap()
            .orEmpty()

    private fun JsonObject.queueIds(): List<String> = (this["queue"] as? JsonArray)?.mapNotNull { it.jsonObject.text("iid") }.orEmpty()

    private fun JsonObject.requestIds(): List<String> = (this["requests"] as? JsonObject)?.keys?.sorted().orEmpty()

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("agentwire/conformance/$name")) {
            "missing conformance resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    private companion object {
        const val TOPIC_BACKEND = "claude"
        val CORPORA = listOf("claude-session", "queue-and-acks")
    }
}
