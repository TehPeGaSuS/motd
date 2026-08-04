package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_TAG
import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.encodeAgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.fragmentAgentwireEnvelope
import io.github.trevarj.motd.irc.client.EventMapper
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

private const val SYNC_ID = "11111111-1111-4111-8111-111111111111"

/**
 * Locks [IgnoreReason] to the ingestor's declining paths. Before this, ten `return Ignored` sites
 * were indistinguishable from one another and from silence, which is what made a wedge require
 * journal forensics to diagnose.
 */
class AgentwireIgnoreReasonTest {
    @Test
    fun `playback and replay batches are declined as playback`() {
        val batch = IrcEvent.PlaybackBatch(
            source = IrcEvent.PlaybackSource.CHATHISTORY,
            target = "#codex",
            items = emptyList<IrcEvent.PlaybackItem>(),
        )
        assertEquals(IgnoreReason.PLAYBACK, why(state(), batch))
    }

    @Test
    fun `traffic without an agentwire tag is not protocol traffic`() {
        val plain = map("@account=agent :agent!u@h TAGMSG #codex")
        assertEquals(IgnoreReason.NOT_PROTOCOL, why(state(), plain))
    }

    @Test
    fun `another channel's agentwire traffic is a target mismatch`() {
        assertEquals(
            IgnoreReason.TARGET_MISMATCH,
            why(state(), inbound("agent", hello(SYNC_ID), target = "#other")),
        )
    }

    @Test
    fun `an unauthenticated sender has no usable account`() {
        assertEquals(IgnoreReason.MISSING_ACCOUNT, why(state(), inbound("*", hello(SYNC_ID))))
    }

    @Test
    fun `a topic without an agent field leaves no trust anchor`() {
        assertEquals(
            IgnoreReason.NO_TRUST_ANCHOR,
            why(state().copy(backendAccount = null), inbound("agent", hello(SYNC_ID))),
        )
    }

    @Test
    fun `the controller's own events are declined rather than rejected`() {
        assertEquals(IgnoreReason.CONTROLLER_EVENT, why(state(), inbound("controller", hello(SYNC_ID))))
    }

    @Test
    fun `an untrusted sender is rejected and named, never silently dropped`() {
        val result = AgentwireEventIngestor().ingest(state(), inbound("impostor", hello(SYNC_ID)), SYNC_ID)
        val rejected = result as AgentwireEventIngestor.Result.Rejected
        assertEquals("impostor", rejected.account)
        assertEquals(false, rejected.protocolFailure)
        assertEquals(
            "Ignoring agent events from account impostor. The channel topic trusts only agent=agent.",
            rejected.detail,
        )
    }

    @Test
    fun `an incomplete fragment assembly is pending, not lost`() {
        val big = event("request.opened", data = buildJsonObject { put("summary", "x".repeat(12_000)) })
        val first = fragmentAgentwireEnvelope(big).first()
        assertEquals(IgnoreReason.FRAGMENT_PENDING, why(state(), inboundRaw("agent", first)))
    }

    @Test
    fun `an action envelope is not an event`() {
        val action = AgentwireEnvelope(
            kind = "sync.request",
            type = "action",
            id = UUID.randomUUID().toString(),
            at = 1,
            instance = "peer",
            device = "device",
        )
        assertEquals(IgnoreReason.NOT_EVENT, why(state(), inbound("agent", action)))
    }

    @Test
    fun `an event arriving before a correlated hello is uncorrelated`() {
        assertEquals(IgnoreReason.UNCORRELATED_HELLO, why(state(), inbound("agent", event("turn.started"))))
    }

    @Test
    fun `a hello replying to a superseded sync id is stale`() {
        val stale = hello("22222222-2222-4222-8222-222222222222")
        assertEquals(IgnoreReason.STALE_REPLY, why(state(), inbound("agent", stale)))
    }

    @Test
    fun `a live event from a retired epoch is an epoch mismatch`() {
        val pinned = state().copy(botAccount = "agent", epoch = "epoch-2")
        assertEquals(
            IgnoreReason.EPOCH_MISMATCH,
            why(pinned, inbound("agent", event("turn.started", epoch = "epoch-1"))),
        )
    }

    @Test
    fun `the sync accept filter names its own drops`() {
        val pinned = state().copy(botAccount = "agent", epoch = "epoch-1")
        val result = AgentwireEventIngestor().ingest(
            state = pinned,
            event = inbound("agent", event("turn.started", epoch = "epoch-1")),
            syncId = SYNC_ID,
            accept = { false },
        )
        assertEquals(IgnoreReason.FILTERED, (result as AgentwireEventIngestor.Result.Ignored).why)
    }

    private fun why(state: AgentwireUiState, event: IrcEvent): IgnoreReason {
        val result = AgentwireEventIngestor().ingest(state, event, SYNC_ID)
        return (result as AgentwireEventIngestor.Result.Ignored).why
    }

    private fun state() = AgentwireUiState(
        channel = "#codex",
        controllerAccount = "controller",
        backendAccount = "agent",
    )

    private fun hello(reply: String) = event(
        kind = "agent.hello",
        reply = reply,
        data = buildJsonObject { put("epoch", "epoch-1") },
    )

    private fun event(
        kind: String,
        epoch: String? = null,
        reply: String? = null,
        data: JsonObject? = null,
    ) = AgentwireEnvelope(
        kind = kind,
        type = "event",
        id = UUID.randomUUID().toString(),
        at = 1,
        instance = "agent",
        epoch = epoch,
        reply = reply,
        data = data,
    )

    private fun inbound(account: String, envelope: AgentwireEnvelope, target: String = "#codex"): IrcEvent =
        inboundRaw(account, encodeAgentwireEnvelope(envelope), target)

    private fun inboundRaw(account: String, raw: String, target: String = "#codex"): IrcEvent = map(
        IrcMessage(
            tags = mapOf("account" to account, AGENTWIRE_TAG to raw),
            source = io.github.trevarj.motd.irc.proto.Prefix(account, "u", "h"),
            command = "TAGMSG",
            params = listOf(target),
        ).serialize(),
    )

    private fun map(line: String): IrcEvent =
        checkNotNull(EventMapper({ "me" }, { Isupport() }).map(IrcMessage.parse(line)))
}
