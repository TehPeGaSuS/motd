package io.github.trevarj.motd.audio

import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.service.PinningTrustManager
import io.github.trevarj.motd.service.LocalSocksProvider
import io.github.trevarj.motd.service.resolveTransportProxy
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

data class NetworkMediaRoute(
    val networkId: Long,
    val endpoint: NetworkEntity,
    val proxy: Proxy?,
    val proxyError: String?,
    val authorizationHeader: String?,
    val endpointPinnedSha256: String? = null,
    private val release: () -> Unit = {},
) : AutoCloseable {
    fun open(url: String, authenticated: Boolean = false): HttpURLConnection {
        val parsedUrl = URL(url)
        val connection = if (proxy != null) {
            parsedUrl.openConnection(proxy)
        } else {
            parsedUrl.openConnection()
        } as HttpURLConnection
        if (
            connection is HttpsURLConnection &&
            endpointPinnedSha256 != null &&
            parsedUrl.host.equals(endpoint.host, ignoreCase = true)
        ) {
            val port = parsedUrl.port.takeIf { it >= 0 } ?: parsedUrl.defaultPort
            val trustManager = PinningTrustManager(parsedUrl.host, port, endpointPinnedSha256)
            connection.sslSocketFactory = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), null)
            }.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        if (authenticated) {
            authorizationHeader?.let { connection.setRequestProperty("Authorization", it) }
        }
        return connection
    }

    override fun close() = release()
}

/** Narrow seam over [NetworkMediaRouteProvider] so HTTP repositories are testable without Room. */
fun interface MediaRouteResolver {
    suspend fun routeForNetwork(networkId: Long): NetworkMediaRoute?
}

/**
 * Whether the app-global fetch stacks (the process Coil loader, ExoPlayer) may load network content
 * for one network. Those stacks cannot be routed per-network, so any obfuscated transport answers
 * false and the UI must withhold that content instead of fetching it directly.
 */
fun interface DirectMediaPolicy {
    suspend fun directMediaAllowed(networkId: Long): Boolean
}

@Singleton
class NetworkMediaRouteProvider @Inject constructor(
    private val db: MotdDatabase,
    private val localSocksProvider: LocalSocksProvider,
    private val certTrustStore: CertTrustStore,
) : MediaRouteResolver, DirectMediaPolicy {
    override suspend fun routeForNetwork(networkId: Long): NetworkMediaRoute? {
        val row = db.networkDao().byId(networkId) ?: return null
        val endpoint = if (row.role == NetworkRole.BOUNCER_CHILD) {
            row.parentId?.let { db.networkDao().byId(it) } ?: row
        } else {
            row
        }
        val resolved = resolveTransportProxy(endpoint, localSocksProvider, ownerKey = "media-$networkId")
        return NetworkMediaRoute(
            networkId = networkId,
            endpoint = endpoint,
            proxy = resolved.proxy,
            proxyError = resolved.error,
            authorizationHeader = endpoint.basicAuthorizationHeader(
                childNetworkSelector = row.bouncerNetId.takeIf { row.role == NetworkRole.BOUNCER_CHILD },
            ),
            endpointPinnedSha256 = certTrustStore.pinnedFor(endpoint.host, endpoint.port),
            release = resolved.release,
        )
    }

    override suspend fun directMediaAllowed(networkId: Long): Boolean {
        val row = db.networkDao().byId(networkId) ?: return false
        // A bouncer child shares its physical endpoint (and therefore its transport policy) with
        // the bouncer root, exactly as routeForNetwork does above.
        val endpoint = if (row.role == NetworkRole.BOUNCER_CHILD) {
            row.parentId?.let { db.networkDao().byId(it) } ?: row
        } else {
            row
        }
        return endpoint.obfsMode == null || endpoint.obfsMode == ObfsMode.NONE
    }
}

internal fun NetworkEntity.basicAuthorizationHeader(childNetworkSelector: String? = null): String? {
    if (!saslMechanism.equals("PLAIN", ignoreCase = true)) return null
    val baseUser = saslUser?.takeIf(String::isNotBlank)
        ?: username.takeIf(String::isNotBlank)
        ?: nick.takeIf(String::isNotBlank)
        ?: return null
    val user = childNetworkSelector?.takeIf(String::isNotBlank)?.let { "$baseUser/$it" } ?: baseUser
    val password = saslPassword?.takeIf(String::isNotBlank) ?: return null
    val token = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
    return "Basic $token"
}
