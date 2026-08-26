package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.prefs.InviteEnrollmentCleanup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Duplicate-connection prevention: adding the same server twice must not insert a second
 * [NetworkEntity] (which would spawn a second actor/socket). DIRECT and BOUNCER_ROOT dedup on
 * [name][NetworkEntity.name] alone — host/port/nick/credential differences never block a second
 * add (see [networkIdentityKey]: users filling those in incorrectly, e.g. a WeeChat relay account
 * missing the ZNC/CLoak `user/network` selector, must not have it silently merged into an
 * unrelated existing network). BOUNCER_CHILD keeps its own `(parentId, bouncerNetId)` key, since
 * that path is the soju-child import, not a user-facing add.
 */
class NetworkDedupTest {
    /** In-memory NetworkDao: only the methods addNetwork touches are backed; rest throw. */
    private class InMemoryNetworkDao : NetworkDao {
        val rows = LinkedHashMap<Long, NetworkEntity>()
        private var nextId = 1L

        override suspend fun insert(n: NetworkEntity): Long {
            val id = nextId++
            rows[id] = n.copy(id = id)
            return id
        }

        override suspend fun allNow(): List<NetworkEntity> {
            yield()
            return rows.values.toList()
        }

        override suspend fun byId(id: Long): NetworkEntity? = rows[id]

        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = rows.values.filter { it.parentId == rootId }

        override suspend fun localTreeIds(id: Long): List<Long> =
            rows.values
                .filter { it.id == id || it.parentId == id }
                .map { it.id }

        override suspend fun deleteMembersForNetworks(networkIds: List<Long>) = Unit

        override suspend fun deleteReactionsForNetworks(networkIds: List<Long>) = Unit

        override suspend fun deleteUsersForNetworks(networkIds: List<Long>) = Unit

        override suspend fun deleteNetworkRows(networkIds: List<Long>) {
            networkIds.forEach(rows::remove)
        }

        override suspend fun maxOrdering(): Int = rows.values.maxOfOrNull { it.ordering } ?: -1

        override suspend fun idsInOrder(): List<Long> =
            rows.values
                .sortedWith(compareBy(NetworkEntity::ordering, NetworkEntity::id))
                .map { it.id }

        override suspend fun setServerIconUrl(
            id: Long,
            url: String?,
        ) {
            rows[id]?.let { rows[id] = it.copy(serverIconUrl = url) }
        }

        override suspend fun setOrdering(
            id: Long,
            ordering: Int,
        ) {
            rows[id]?.let { rows[id] = it.copy(ordering = ordering) }
        }

        override fun observeAll(): Flow<List<NetworkEntity>> = flowOf(rows.values.toList())

        override suspend fun connectable(): List<NetworkEntity> = rows.values.filter { it.autoConnect }

        override suspend fun update(n: NetworkEntity) {
            rows[n.id] = n
        }

        override suspend fun updateBouncerConnection(
            id: Long,
            host: String,
            port: Int,
            nick: String,
        ) {
            rows[id]?.let { rows[id] = it.copy(host = host, port = port, nick = nick) }
        }
    }

    private class RecordingEnrollmentCleanup : InviteEnrollmentCleanup {
        val cleared = mutableListOf<Long>()

        override suspend fun clearNetwork(networkId: Long) {
            cleared += networkId
        }
    }

    private class RecordingBouncerKinds : BouncerKindPrefs {
        override val zncNetworkIds: Flow<Set<Long>> = flowOf(emptySet())
        val cleared = mutableListOf<Long>()

        override suspend fun markZnc(networkId: Long) = Unit

        override suspend fun clear(networkId: Long) {
            cleared += networkId
        }
    }

    private fun direct(
        name: String,
        host: String = name,
        port: Int = 6697,
        nick: String = "motd",
    ) = NetworkEntity(
        name = name,
        role = NetworkRole.DIRECT,
        host = host,
        port = port,
        nick = nick,
        username = nick,
        realname = nick,
    )

    private fun root(
        name: String,
        saslUser: String?,
        host: String = name,
    ) = NetworkEntity(
        name = name,
        role = NetworkRole.BOUNCER_ROOT,
        host = host,
        port = 6697,
        nick = "motd",
        username = "motd",
        realname = "motd",
        saslMechanism = "PLAIN",
        saslUser = saslUser,
    )

    private fun child(
        parentId: Long,
        netId: String,
        host: String = "irc.child.org",
    ) = NetworkEntity(
        name = netId,
        role = NetworkRole.BOUNCER_CHILD,
        parentId = parentId,
        bouncerNetId = netId,
        host = host,
        port = 6697,
        nick = "motd",
        username = "motd",
        realname = "motd",
    )

    @Test
    fun `adding the same name twice returns the existing id and no second row`() =
        runBlocking {
            val dao = InMemoryNetworkDao()
            val repo = NetworkRepositoryImpl(dao)
            val first = repo.addNetwork(direct("Libera"))
            val second = repo.addNetwork(direct("Libera"))
            assertEquals(first, second)
            assertEquals(1, dao.rows.size)
        }

    @Test
    fun `concurrent equivalent adds serialize to one network row`() =
        runTest {
            val dao = InMemoryNetworkDao()
            val repo = NetworkRepositoryImpl(dao)

            val ids = List(20) { async { repo.addNetwork(direct("Libera")) } }.awaitAll()

            assertEquals(1, ids.toSet().size)
            assertEquals(1, dao.rows.size)
        }

    @Test
    fun `name normalization dedups whitespace and case variants`() =
        runBlocking {
            val dao = InMemoryNetworkDao()
            val repo = NetworkRepositoryImpl(dao)
            val a = repo.addNetwork(direct("Libera"))
            val b = repo.addNetwork(direct("  libera  "))
            assertEquals(a, b)
            assertEquals(1, dao.rows.size)
        }

    @Test
    fun `networkNameTaken matches case-insensitively and ignores bouncer children`() {
        val existing =
            listOf(
                direct("Libera").copy(id = 1),
                child(parentId = 2, netId = "1").copy(id = 3, name = "Libera"),
            )
        assertEquals(true, networkNameTaken("libera", existing))
        assertEquals(true, networkNameTaken("  LIBERA  ", existing))
        assertEquals(false, networkNameTaken("OFTC", existing))
        // The BOUNCER_CHILD row happens to share the name "Libera" but must not count: it is an
        // internal soju mirror, not a name the "Add network" form could ever collide with.
        assertEquals(false, networkNameTaken("Libera", listOf(child(parentId = 2, netId = "1").copy(name = "Libera"))))
    }

    @Test
    fun `same name but different host, port, or nick still dedups`() =
        runBlocking {
            val dao = InMemoryNetworkDao()
            val repo = NetworkRepositoryImpl(dao)
            val first =
                repo.addNetwork(
                    direct("relay", host = "myweechat.0bin.xyz", port = 1343, nick = "alice"),
                )
            // Same display name, everything else differs (the WeeChat-relay dedup complaint):
            // whatever host/port/nick/credentials the user typed the second time, a name collision
            // alone must still resolve to the existing row rather than silently connecting twice.
            val second =
                repo.addNetwork(
                    direct("relay", host = "myweechat.0bin.xyz", port = 1343, nick = "PeGaSuS"),
                )
            assertEquals(first, second)
            assertEquals(1, dao.rows.size)
        }

    @Test
    fun `different name on the same host, port, and nick is a distinct network`() =
        runBlocking {
            val dao = InMemoryNetworkDao()
            val repo = NetworkRepositoryImpl(dao)
            // The motivating case: a second account on the same relay. Host/port/nick alone must
            // never merge two rows the user explicitly named differently.
            repo.addNetwork(direct("01_ptirc", host = "myweechat.0bin.xyz", port = 1343))
            repo.addNetwork(direct("02_libera", host = "myweechat.0bin.xyz", port = 1343))
            assertEquals(2, dao.rows.size)
        }

    @Test
    fun `same bouncer root name added twice reuses the row`() =
        runBlocking {
            val dao = InMemoryNetworkDao()
            val repo = NetworkRepositoryImpl(dao)
            val a = repo.addNetwork(root("My Bouncer", saslUser = "acct"))
            val b = repo.addNetwork(root("My Bouncer", saslUser = "acct"))
            assertEquals(a, b)
            assertEquals(1, dao.rows.size)
            // A different name is a distinct root even with the same saslUser/host.
            repo.addNetwork(root("Other Bouncer", saslUser = "acct", host = "bnc.example.org"))
            assertEquals(2, dao.rows.size)
        }

    @Test
    fun `bouncer child import is idempotent per (parent, netId)`() =
        runBlocking {
            val dao = InMemoryNetworkDao()
            val repo = NetworkRepositoryImpl(dao)
            val rootId = repo.addNetwork(root("bnc.example.org", saslUser = "acct"))
            val first = repo.addNetwork(child(rootId, netId = "42"))
            val second = repo.addNetwork(child(rootId, netId = "42", host = "different.host"))
            assertEquals(first, second)
            assertEquals(2, dao.rows.size) // root + one child
            // A different netId under the same root is a distinct child.
            repo.addNetwork(child(rootId, netId = "43"))
            assertEquals(3, dao.rows.size)
        }

    @Test
    fun `child netId collision across different roots stays distinct`() =
        runBlocking {
            val dao = InMemoryNetworkDao()
            val repo = NetworkRepositoryImpl(dao)
            val root1 = repo.addNetwork(root("bnc1.example.org", saslUser = "a"))
            val root2 = repo.addNetwork(root("bnc2.example.org", saslUser = "b"))
            repo.addNetwork(child(root1, netId = "1"))
            repo.addNetwork(child(root2, netId = "1"))
            assertEquals(4, dao.rows.size) // two roots + two children
        }

    @Test
    fun `deleting a bouncer root deletes every local child and clears their classifications`() =
        runBlocking {
            val dao = InMemoryNetworkDao()
            val kinds = RecordingBouncerKinds()
            val enrollment = RecordingEnrollmentCleanup()
            val repo = NetworkRepositoryImpl(dao, kinds, enrollment)
            val rootId = repo.addNetwork(root("bnc.example.org", saslUser = "acct"))
            val childOne = repo.addNetwork(child(rootId, netId = "1"))
            val childTwo = repo.addNetwork(child(rootId, netId = "2"))
            val unrelated = repo.addNetwork(direct("irc.example.org"))

            repo.deleteNetwork(rootId)

            assertEquals(setOf(unrelated), dao.rows.keys)
            assertEquals(setOf(rootId, childOne, childTwo), kinds.cleared.toSet())
            assertEquals(setOf(rootId, childOne, childTwo), enrollment.cleared.toSet())
        }
}
