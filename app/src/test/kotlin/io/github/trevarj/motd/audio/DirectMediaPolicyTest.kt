package io.github.trevarj.motd.audio

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.service.LocalSocksEngine
import io.github.trevarj.motd.service.LocalSocksProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The app-global Coil/ExoPlayer stacks cannot honor a per-network proxy, so the policy must deny
 * direct media for every obfuscated transport and for unknown networks (fail closed).
 */
@RunWith(RobolectricTestRunner::class)
class DirectMediaPolicyTest {
    private lateinit var db: MotdDatabase
    private lateinit var provider: NetworkMediaRouteProvider

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        provider = NetworkMediaRouteProvider(
            db,
            LocalSocksProvider.forTest { FailingEngine },
            NoPinsTrustStore,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun direct_networks_allow_global_media_fetches() = runTest {
        assertTrue(provider.directMediaAllowed(insert(obfsMode = null)))
        assertTrue(provider.directMediaAllowed(insert(obfsMode = ObfsMode.NONE)))
    }

    @Test
    fun every_obfuscated_transport_denies_global_media_fetches() = runTest {
        assertFalse(provider.directMediaAllowed(insert(obfsMode = ObfsMode.SOCKS5)))
        assertFalse(provider.directMediaAllowed(insert(obfsMode = ObfsMode.TOR)))
        assertFalse(provider.directMediaAllowed(insert(obfsMode = ObfsMode.EMBEDDED_REALITY)))
    }

    @Test
    fun bouncer_children_inherit_the_parent_endpoint_transport() = runTest {
        val torParent = insert(obfsMode = ObfsMode.TOR)
        val child = insert(
            obfsMode = null,
            role = NetworkRole.BOUNCER_CHILD,
            parentId = torParent,
        )
        assertFalse(provider.directMediaAllowed(child))

        val directParent = insert(obfsMode = null)
        val directChild = insert(
            obfsMode = null,
            role = NetworkRole.BOUNCER_CHILD,
            parentId = directParent,
        )
        assertTrue(provider.directMediaAllowed(directChild))
    }

    @Test
    fun unknown_networks_fail_closed() = runTest {
        assertFalse(provider.directMediaAllowed(9_999L))
    }

    private suspend fun insert(
        obfsMode: ObfsMode?,
        role: NetworkRole = NetworkRole.DIRECT,
        parentId: Long? = null,
    ): Long = db.networkDao().insert(
        NetworkEntity(
            name = "net",
            role = role,
            parentId = parentId,
            host = "irc.example.test",
            port = 6697,
            nick = "nick",
            username = "user",
            realname = "real",
            obfsMode = obfsMode,
            proxyHost = if (obfsMode == ObfsMode.SOCKS5) "127.0.0.1" else null,
            proxyPort = if (obfsMode == ObfsMode.SOCKS5) 1080 else null,
        ),
    )

    private object FailingEngine : LocalSocksEngine {
        override fun start(configJson: String): Result<Int> =
            Result.failure(IllegalStateException("no embedded core in unit tests"))

        override fun stop() = Unit
    }

    private object NoPinsTrustStore : CertTrustStore {
        override suspend fun pinnedFor(host: String, port: Int): String? = null
        override suspend fun isPinned(host: String, port: Int, sha256: String): Boolean = false
        override suspend fun pin(host: String, port: Int, sha256: String) = Unit
        override suspend fun unpin(host: String, port: Int) = Unit
    }
}
