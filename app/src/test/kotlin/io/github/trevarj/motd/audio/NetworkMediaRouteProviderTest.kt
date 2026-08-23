package io.github.trevarj.motd.audio

import com.sun.net.httpserver.HttpServer
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

class NetworkMediaRouteProviderTest {
    @Test
    fun `bouncer child filehost auth uses the same selected-network identity as IRC`() {
        val header = network().basicAuthorizationHeader(childNetworkSelector = "libera")

        assertEquals("trev/libera:password", decodeBasic(header))
    }

    @Test
    fun `non plain SASL does not synthesize HTTP basic credentials`() {
        assertNull(network().copy(saslMechanism = "EXTERNAL").basicAuthorizationHeader("libera"))
    }

    @Test
    fun `route only exposes bouncer credentials to explicitly authenticated requests`() {
        val received = mutableListOf<String?>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    received += exchange.requestHeaders.getFirst("Authorization")
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                start()
            }
        val route =
            NetworkMediaRoute(
                networkId = 1L,
                endpoint = network(),
                proxy = null,
                proxyError = null,
                authorizationHeader = "Basic private",
            )

        try {
            val url = "http://127.0.0.1:${server.address.port}/"
            route.open(url).apply { responseCode }.disconnect()
            route.open(url, authenticated = true).apply { responseCode }.disconnect()

            assertEquals(listOf(null, "Basic private"), received)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `route reuses an approved bouncer leaf pin only for the same filehost`() {
        val route =
            NetworkMediaRoute(
                networkId = 1L,
                endpoint = network(),
                proxy = null,
                proxyError = null,
                authorizationHeader = null,
                endpointPinnedSha256 = "00".repeat(32),
            )
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

        val bouncerFileHost = route.open("https://irc.example:7443/uploads") as HttpsURLConnection
        val unrelatedFileHost = route.open("https://uploads.example:7443/uploads") as HttpsURLConnection

        assertNotSame(defaultVerifier, bouncerFileHost.hostnameVerifier)
        assertSame(defaultVerifier, unrelatedFileHost.hostnameVerifier)
        bouncerFileHost.disconnect()
        unrelatedFileHost.disconnect()
    }

    private fun network() =
        NetworkEntity(
            name = "Soju",
            role = NetworkRole.BOUNCER_ROOT,
            host = "irc.example",
            port = 6697,
            nick = "trev",
            username = "trev",
            realname = "trev",
            saslMechanism = "PLAIN",
            saslUser = "trev",
            saslPassword = "password",
        )

    private fun decodeBasic(header: String?): String {
        val encoded = requireNotNull(header).removePrefix("Basic ")
        return Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
    }
}
