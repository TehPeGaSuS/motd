package io.github.trevarj.motd.irc.agentwire

import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentwireProtocolTest {
    @Test
    fun `canonical topic fixture activates and preserves unknown options`() {
        val fixture = resource("agentwire/fixtures/topic.txt").trimEnd()
        val topic = parseAgentwireTopic(fixture.replace(" | ", ";ignored=value | "))

        assertEquals("trev", topic?.account)
        assertEquals("codex", topic?.backend)
        assertEquals("value", topic?.options?.get("ignored"))
        assertEquals("trev+mobile", parseAgentwireTopic("agentwire:v1;account=trev%2Bmobile;backend=codex")?.account)
        assertNull(parseAgentwireTopic("Welcome agentwire:v1;account=trev;backend=codex"))
        assertNull(parseAgentwireTopic("agentwire:v1;account=trev"))
        assertNull(parseAgentwireTopic("agentwire:v1;account=%ZZ;backend=codex"))
        assertNull(parseAgentwireTopic("agentwire:v1;account=%ff;backend=codex"))
    }

    @Test
    fun `canonical fixtures validate and re-encode byte for byte`() {
        listOf("hello.json", "prompt-action.json").forEach { name ->
            val fixture = resource("agentwire/fixtures/$name").trimEnd()
            val envelope = (decodeAgentwireValue(fixture).getOrThrow() as AgentwireValue.Envelope).value
            assertEquals(fixture, encodeAgentwireEnvelope(envelope))
        }
    }

    @Test
    fun `strict schema shape rejects unknown fields mismatched kinds and action without device`() {
        assertFailure("""{"v":1,"k":"agent.hello","t":"event","id":"11111111-1111-4111-8111-111111111111","at":1,"inst":"x","extra":1}""")
        assertFailure("""{"v":1,"k":"turn.prompt","t":"event","id":"11111111-1111-4111-8111-111111111111","at":1,"inst":"x"}""")
        assertFailure("""{"v":1,"k":"turn.prompt","t":"action","id":"11111111-1111-4111-8111-111111111111","at":1,"inst":"x"}""")
        assertFailure("""{"v":1,"k":"agent.hello","t":"event","id":"not-a-uuid","at":1,"inst":"x"}""")
        assertTrue(decodeAgentwireValue("""{"v":1.0,"k":"agent.hello","t":"event","id":"11111111-1111-4111-8111-111111111111","at":1.0,"inst":"x"}""").isSuccess)
        assertFailure("""{"v":1.5,"k":"agent.hello","t":"event","id":"11111111-1111-4111-8111-111111111111","at":1,"inst":"x"}""")
    }

    @Test
    fun `fragmented envelope reassembles out of order and tolerates exact duplicate`() {
        val envelope = event(content = "λ".repeat(8_000))
        val fragments = fragmentAgentwireEnvelope(envelope).map { raw ->
            (decodeAgentwireValue(raw).getOrThrow() as AgentwireValue.Fragment).value
        }
        assertTrue(fragments.size > 1)
        val reassembler = AgentwireReassembler()
        assertNull(reassembler.accept(fragments.last()).getOrThrow())
        assertNull(reassembler.accept(fragments.last()).getOrThrow())
        var decoded: AgentwireEnvelope? = null
        fragments.dropLast(1).reversed().forEach { decoded = reassembler.accept(it).getOrThrow() ?: decoded }
        assertEquals(envelope.id, decoded?.id)
        assertEquals(envelope.data, decoded?.data)
    }

    @Test
    fun `conflicting duplicate invalidates assembly`() {
        val fragments = fragmentAgentwireEnvelope(event("x".repeat(10_000))).map {
            (decodeAgentwireValue(it).getOrThrow() as AgentwireValue.Fragment).value
        }
        val reassembler = AgentwireReassembler()
        reassembler.accept(fragments.first()).getOrThrow()
        assertTrue(reassembler.accept(fragments.first().copy(b64 = fragments.first().b64.reversed())).isFailure)
        // The invalidated ID may start a fresh assembly rather than inheriting poisoned bytes.
        assertNull(reassembler.accept(fragments.first()).getOrThrow())
    }

    @Test
    fun `required capability comparison ignores capability values`() {
        val caps = AGENTWIRE_REQUIRED_CAPS.mapTo(mutableSetOf()) { cap ->
            if (cap == "draft/multiline") "$cap=max-bytes=4096" else cap
        }
        assertTrue(agentwireMissingCaps(caps).isEmpty())
        assertEquals(setOf("account-tag"), agentwireMissingCaps(caps - "account-tag"))
    }

    @Test
    fun `copied canonical resources match upstream content hashes`() {
        assertEquals("ee0b4f5a22de4ec958dcf32f987f3d895ed4d44d4c0fe7c7878005f6e4b466fc", sha(resourceBytes("agentwire/agentwire-v1.schema.json")))
        assertEquals("c240fa3101a54160f57c7badeb16866256aaa031b8bcef718d8d04c9d1f6bfc1", sha(resourceBytes("agentwire/fixtures/hello.json")))
        assertEquals("e354c350de1de04cf396aa595c90c46658db9e72b381fc54b7f7559ee7f38465", sha(resourceBytes("agentwire/fixtures/prompt-action.json")))
        assertEquals("058c5ad375bd89d3074349e4b01650525eb8138bd3d651d90236e72b4d4f964c", sha(resourceBytes("agentwire/fixtures/topic.txt")))
    }

    private fun event(content: String) = AgentwireEnvelope(
        kind = "assistant.completed",
        type = "event",
        id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        at = 1,
        instance = "instance",
        epoch = "epoch",
        sid = "session",
        data = buildJsonObject { put("content", content) },
    )

    private fun assertFailure(raw: String) {
        if (decodeAgentwireValue(raw).isSuccess) fail("expected validation failure: $raw")
    }

    private fun resource(path: String): String = resourceBytes(path).toString(Charsets.UTF_8)
    private fun resourceBytes(path: String): ByteArray = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)).readBytes()
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
