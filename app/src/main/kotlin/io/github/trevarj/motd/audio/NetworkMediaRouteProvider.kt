package io.github.trevarj.motd.audio

import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.service.LocalSocksProvider
import io.github.trevarj.motd.service.resolveTransportProxy
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkMediaRoute(
    val networkId: Long,
    val endpoint: NetworkEntity,
    val proxy: Proxy?,
    val proxyError: String?,
    val authorizationHeader: String?,
    private val release: () -> Unit = {},
) : AutoCloseable {
    fun open(url: String): HttpURLConnection {
        val connection = if (proxy != null) {
            URL(url).openConnection(proxy)
        } else {
            URL(url).openConnection()
        } as HttpURLConnection
        authorizationHeader?.let { connection.setRequestProperty("Authorization", it) }
        return connection
    }

    override fun close() = release()
}

@Singleton
class NetworkMediaRouteProvider @Inject constructor(
    private val db: MotdDatabase,
    private val localSocksProvider: LocalSocksProvider,
) {
    suspend fun routeForNetwork(networkId: Long): NetworkMediaRoute? {
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
            authorizationHeader = endpoint.basicAuthorizationHeader(),
            release = resolved.release,
        )
    }
}

private fun NetworkEntity.basicAuthorizationHeader(): String? {
    if (!saslMechanism.equals("PLAIN", ignoreCase = true)) return null
    val user = saslUser?.takeIf(String::isNotBlank)
        ?: username.takeIf(String::isNotBlank)
        ?: nick.takeIf(String::isNotBlank)
        ?: return null
    val password = saslPassword?.takeIf(String::isNotBlank) ?: return null
    val token = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
    return "Basic $token"
}
