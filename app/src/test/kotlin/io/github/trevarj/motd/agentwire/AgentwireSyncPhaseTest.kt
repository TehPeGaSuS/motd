package io.github.trevarj.motd.agentwire

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_REQUIRED_CAPS
import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_TAG
import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.AgentwireValue
import io.github.trevarj.motd.irc.agentwire.decodeAgentwireValue
import io.github.trevarj.motd.irc.agentwire.encodeAgentwireEnvelope
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val NETWORK_ID = 7L
private const val BUFFER_ID = 11L
private const val CHANNEL = "#claude"
private const val BACKEND_ACCOUNT = "agent"

/**
 * The negative space no existing agentwire test covered: when correlated replies never arrive, or
 * the bridge answers with a definitive refusal, the UI must leave `syncing` within a bound and say
 * which of the distinguishable failures happened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AgentwireSyncPhaseTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `unanswered handshake ends as a named timeout and retry re-arms the budget`() = runTest(dispatcher) {
        val transport = RecordingTransport()
        val client = readyClient(transport)
        val viewModel = viewModel(client)
        advanceTimeBy(10)
        runCurrent()
        assertTrue(viewModel.state.value.sync is AgentwireSyncState.Syncing)
        assertTrue(syncRequests(transport).isNotEmpty())

        advanceTimeBy(AGENTWIRE_SYNC_BUDGET_MS - 1_000)
        runCurrent()
        assertTrue(
            "the spinner must still be running one second before the budget",
            viewModel.state.value.sync is AgentwireSyncState.Syncing,
        )

        advanceTimeBy(2_000)
        runCurrent()
        val failed = viewModel.state.value.sync as AgentwireSyncState.Failed
        val timeout = failed.failure as AgentwireSyncFailure.Timeout
        assertEquals(syncRequests(transport).size, timeout.attempts)

        // The loop is terminal: waiting longer must not produce more requests.
        val issued = syncRequests(transport).size
        advanceTimeBy(AGENTWIRE_SYNC_BUDGET_MS)
        runCurrent()
        assertEquals(issued, syncRequests(transport).size)

        viewModel.retrySync()
        runCurrent()
        assertTrue(viewModel.state.value.sync is AgentwireSyncState.Syncing)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue("retry must re-arm and send again", syncRequests(transport).size > issued)
    }

    @Test
    fun `action failed replying to the live sync id becomes a rejection and stops retrying`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val viewModel = viewModel(client)
            advanceTimeBy(10)
            runCurrent()
            val syncId = syncRequests(transport).last()

            transport.feed(tagMessage(BACKEND_ACCOUNT, actionFailed(syncId, "topic agent= does not match")))
            runCurrent()

            val failed = viewModel.state.value.sync as AgentwireSyncState.Failed
            val rejected = failed.failure as AgentwireSyncFailure.Rejected
            assertEquals("topic agent= does not match", rejected.detail)

            val issued = syncRequests(transport).size
            advanceTimeBy(2 * AGENTWIRE_SYNC_BUDGET_MS)
            runCurrent()
            assertEquals("a definitive refusal must end the retry loop", issued, syncRequests(transport).size)
            assertTrue(viewModel.state.value.sync is AgentwireSyncState.Failed)
        }

    private fun TestScope.viewModel(client: IrcClient, joined: Boolean = true): AgentwireViewModel =
        AgentwireViewModel(
            savedStateHandle = SavedStateHandle(mapOf("bufferId" to BUFFER_ID)),
            prefs = FakeAgentwirePrefs(),
            buffers = FakeBufferRepository(buffer(joined)),
            connections = FakeConnections(client),
            diagnostics = DiagnosticLogger.Noop,
            clock = AppClock { testScheduler.currentTime },
        )

    private fun buffer(joined: Boolean) = BufferEntity(
        id = BUFFER_ID,
        networkId = NETWORK_ID,
        name = CHANNEL,
        displayName = CHANNEL,
        type = BufferType.CHANNEL,
        topic = "agentwire:v1;account=controller;agent=$BACKEND_ACCOUNT;backend=claude | Claude",
        joined = joined,
    )

    private suspend fun TestScope.readyClient(transport: RecordingTransport): IrcClient {
        val client = IrcClient(
            IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
            TransportFactory { _, _, _, _, _ -> transport },
            CoroutineScope(SupervisorJob() + coroutineContext),
        )
        client.start()
        runCurrent()
        val caps = AGENTWIRE_REQUIRED_CAPS.joinToString(" ")
        transport.feed(":srv CAP * LS :$caps")
        runCurrent()
        transport.feed(":srv CAP me ACK :$caps")
        transport.feed(":srv 005 me CHANTYPES=# :supported")
        transport.feed(":srv 001 me :Welcome")
        runCurrent()
        check(client.state.value is IrcClientState.Ready) { "client is ${client.state.value}" }
        transport.sent.clear()
        return client
    }

    /** Ids of the `sync.request` envelopes this device actually wrote to the wire. */
    private fun syncRequests(transport: RecordingTransport): List<String> = transport.sent.mapNotNull { line ->
        val tag = runCatching { IrcMessage.parse(line) }.getOrNull()?.tags?.get(AGENTWIRE_TAG) ?: return@mapNotNull null
        val envelope = (decodeAgentwireValue(tag).getOrNull() as? AgentwireValue.Envelope)?.value
        envelope?.takeIf { it.kind == "sync.request" }?.id
    }

    /** Serialized so tag values are escaped: envelope JSON contains spaces and semicolons. */
    private fun tagMessage(account: String, envelope: AgentwireEnvelope): String = IrcMessage(
        tags = mapOf("account" to account, AGENTWIRE_TAG to encodeAgentwireEnvelope(envelope)),
        source = Prefix(account, "u", "h"),
        command = "TAGMSG",
        params = listOf(CHANNEL),
    ).serialize()

    private fun actionFailed(reply: String, message: String) = AgentwireEnvelope(
        kind = "action.failed",
        type = "event",
        id = UUID.randomUUID().toString(),
        at = 1,
        instance = "bridge",
        reply = reply,
        data = buildJsonObject { put("message", message) },
    )

    private class RecordingTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()

        override suspend fun connect() = Unit
        override val incoming = inbound.consumeAsFlow()
        override suspend fun send(line: String) {
            sent += line
        }
        override suspend fun close() = inbound.close().let { }
        suspend fun feed(line: String) = inbound.send(line)
    }

    private class FakeAgentwirePrefs : AgentwirePrefs(ApplicationProvider.getApplicationContext<Context>()) {
        override val enabled: Flow<Boolean> = flowOf(true)
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override suspend fun deviceId(): String = "device-under-test"
    }

    private class FakeBufferRepository(private val buffer: BufferEntity) : BufferRepository {
        val buffers = MutableStateFlow<BufferEntity?>(buffer)
        override fun observeChatList(): Flow<List<ChatListRow>> = flowOf(emptyList())
        override fun observeBuffer(id: Long): Flow<BufferEntity?> = buffers
        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())
        override suspend fun setPinned(id: Long, pinned: Boolean) = Unit
        override suspend fun setMuted(id: Long, muted: Boolean): MuteBacklogSuppression? = null
        override suspend fun setLayoutDensityOverride(id: Long, layout: LayoutDensity?): Boolean = true
        override suspend fun deleteBuffer(id: Long) = Unit
    }

    private class FakeConnections(private val client: IrcClient?) : ConnectionManager {
        val joins = mutableListOf<Pair<Long, String>>()
        override val connectionStates = MutableStateFlow(
            mapOf<Long, IrcClientState>(NETWORK_ID to IrcClientState.Ready("me", AGENTWIRE_REQUIRED_CAPS, emptyMap())),
        )
        override fun clientFor(networkId: Long): IrcClient? = client.takeIf { networkId == NETWORK_ID }
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun sendMessage(bufferId: Long, text: String, replyToEventId: Long?) =
            SendAcceptance.Accepted(emptyList())
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String) {
            joins += networkId to channel
        }
        override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String) = 0L
        override suspend fun ensureServerBuffer(networkId: Long) = 0L
        override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) = Unit
        override suspend fun evaluatePushMode() = Unit
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
    }
}
