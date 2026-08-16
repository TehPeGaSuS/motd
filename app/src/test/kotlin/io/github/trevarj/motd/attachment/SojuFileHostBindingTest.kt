package io.github.trevarj.motd.attachment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.audio.MediaRouteResolver
import io.github.trevarj.motd.audio.NetworkMediaRoute
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A soju file-host upload authenticates with the network's SASL credential, so the advertised
 * `soju.im/FILEHOST` endpoint is bound to the host that credential belongs to before any request
 * is opened. Without that binding a server can name any third-party host and have the client
 * forward its Authorization header there — credential forwarding, with no pin and no consent.
 *
 * The probe socket below stands in for the advertised host so a leaked request is observable:
 * every connection attempt is counted, then dropped.
 */
@RunWith(RobolectricTestRunner::class)
class SojuFileHostBindingTest {
    private val probe = ConnectionProbe()
    private var released = false

    @After fun tearDown() = probe.close()

    @Test fun offHostFileHostIsRefusedBeforeAnythingIsOpened() {
        val error = assertThrows(UploadException::class.java) {
            uploadText(advertised = "https://127.0.0.1:${probe.port}/uploads", networkHost = "irc.example")
        }
        // The refusal names the advertised host, so a misconfigured bouncer reads differently from
        // a hostile one.
        assertTrue(error.message, error.message.orEmpty().contains("127.0.0.1"))
        assertTrue(error.message, error.message.orEmpty().contains("irc.example"))
        // The OPTIONS probe carries the credential too and runs before the POST, so the binding has
        // to precede both: not one byte may reach the advertised host.
        assertEquals(0, probe.connections())
        assertTrue("the media route was not released", released)
    }

    @Test fun advertisedHostIsComparedCaseInsensitivelyAndIgnoringPort() {
        // soju serves IRC and the file host from one process on two ports, so the file host is
        // regularly on a different port than the IRC endpoint. That must still upload.
        val error = assertThrows(IOException::class.java) {
            uploadText(advertised = "https://127.0.0.1:${probe.port}/uploads", networkHost = "127.0.0.1")
        }
        // The probe speaks no TLS, so the request dies in the handshake — after it was made.
        assertFalse(error.message, error.message.orEmpty().contains("Refusing"))
        assertTrue("the upload never reached the advertised host", probe.connections() > 0)
    }

    @Test fun networkWithoutAFileHostIsRefusedWithoutNamingAHost() {
        val error = assertThrows(UploadException::class.java) {
            uploadText(advertised = null, networkHost = "irc.example")
        }
        assertEquals("This IRC network is not advertising a Soju file host.", error.message)
        assertEquals(0, probe.connections())
    }

    private fun uploadText(advertised: String?, networkHost: String) = runBlocking {
        val isupport = advertised?.let { mapOf(SOJU_FILEHOST_TOKEN to it) }.orEmpty()
        val uploader = AttachmentUploaderImpl(
            ApplicationProvider.getApplicationContext<Context>(),
            FakeConnectionManager(IrcClientState.Ready("me", emptySet(), isupport)),
            MediaRouteResolver { id -> route(id, networkHost) },
        )
        uploader.upload(
            AttachmentSource.Text("hello"),
            PasteBackendConfig(backend = AttachmentBackend.SOJU_FILEHOST),
            AttachmentUploadContext(NETWORK_ID),
        ).collect { }
    }

    private fun route(networkId: Long, host: String) = NetworkMediaRoute(
        networkId = networkId,
        endpoint = NetworkEntity(
            id = networkId,
            name = "net",
            role = NetworkRole.DIRECT,
            host = host,
            port = 6697,
            nick = "me",
            username = "me",
            realname = "me",
            saslMechanism = "PLAIN",
            saslUser = "me",
            saslPassword = "hunter2",
        ),
        proxy = null,
        proxyError = null,
        authorizationHeader = "Basic bWU6aHVudGVyMg==",
        release = { released = true },
    )

    /** Counts every inbound connection, then drops it so a stalled handshake cannot hang the test. */
    private class ConnectionProbe : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val count = AtomicInteger()
        private val accepting = Thread {
            while (true) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                count.incrementAndGet()
                runCatching { client.close() }
            }
        }.apply { isDaemon = true; start() }

        val port: Int get() = socket.localPort
        fun connections(): Int = count.get()
        override fun close() {
            runCatching { socket.close() }
            accepting.join(1_000)
        }
    }

    private class FakeConnectionManager(state: IrcClientState) : ConnectionManager {
        override val connectionStates = MutableStateFlow(mapOf(NETWORK_ID to state))
        override fun clientFor(networkId: Long): IrcClient? = null
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun sendMessage(bufferId: Long, text: String, replyToEventId: Long?) =
            SendAcceptance.Accepted(emptyList())
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String, key: String?) = Unit
        override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 0
        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
        override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) = Unit
        override suspend fun evaluatePushMode() = Unit
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
    }

    private companion object {
        const val NETWORK_ID = 7L
    }
}
