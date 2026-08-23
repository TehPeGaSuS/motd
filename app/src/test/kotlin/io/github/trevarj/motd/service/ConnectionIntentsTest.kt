package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure wanted-set computation behind [ConnectionManagerImpl.reconcile]
 *. Verifies that sticky user intent overrides `autoConnect` in both directions and
 * that orphan BOUNCER_CHILD rows are excluded.
 */
class ConnectionIntentsTest {
    private fun net(
        id: Long,
        autoConnect: Boolean = true,
        role: NetworkRole = NetworkRole.DIRECT,
        parentId: Long? = null,
    ) = NetworkEntity(
        id = id,
        name = "net$id",
        role = role,
        parentId = parentId,
        host = "irc.example.org",
        port = 6697,
        nick = "motd",
        username = "motd",
        realname = "motd",
        autoConnect = autoConnect,
    )

    @Test
    fun `autoConnect default with no intents`() {
        val all = listOf(net(1, autoConnect = true), net(2, autoConnect = false))
        assertEquals(setOf(1L), wantedNetworkIds(all, emptyMap()))
    }

    @Test
    fun `force-connect of autoConnect=false survives a reconcile input`() {
        val all = listOf(net(1, autoConnect = false))
        // User pressed Connect: intent true overrides autoConnect=false.
        assertEquals(setOf(1L), wantedNetworkIds(all, mapOf(1L to true)))
    }

    @Test
    fun `force-disconnect of autoConnect=true survives a reconcile input`() {
        val all = listOf(net(1, autoConnect = true))
        // User pressed Disconnect: intent false overrides autoConnect=true.
        assertEquals(emptySet<Long>(), wantedNetworkIds(all, mapOf(1L to false)))
    }

    @Test
    fun `wanted set collapses duplicate ids to one actor key`() {
        // reconcile keys actors by networkId and iterates the wanted SET, so even if two rows
        // carried the same id (defensive), only one actor would ever be ensured for it.
        val all = listOf(net(1, autoConnect = true), net(1, autoConnect = true))
        assertEquals(setOf(1L), wantedNetworkIds(all, emptyMap()))
    }

    @Test
    fun `orphan BOUNCER_CHILD is excluded even when wanted`() {
        val orphan = net(1, autoConnect = true, role = NetworkRole.BOUNCER_CHILD, parentId = null)
        val bound = net(2, autoConnect = true, role = NetworkRole.BOUNCER_CHILD, parentId = 9L)
        assertEquals(setOf(2L), wantedNetworkIds(listOf(orphan, bound), emptyMap()))
        // An explicit force-connect still cannot resurrect an orphan child.
        assertEquals(setOf(2L), wantedNetworkIds(listOf(orphan, bound), mapOf(1L to true)))
    }

    @Test
    fun `bouncer child dials in parallel with its root`() {
        // Startup step 3B: a child is its own slash-auth socket to the bouncer endpoint; nothing it
        // needs comes from the root's live session, so it must be wanted from the FIRST reconcile —
        // before the root has even dialed — instead of serializing behind root registration. (The
        // pre-3B rule pinned by this test's predecessor returned only the root here.)
        val root = net(1, autoConnect = true, role = NetworkRole.BOUNCER_ROOT)
        val child = net(2, autoConnect = true, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)

        assertEquals(setOf(1L, 2L), wantedNetworkIds(listOf(root, child), emptyMap()))
        // A sticky user disconnect on the child still wins over parallel dialing.
        assertEquals(setOf(1L), wantedNetworkIds(listOf(root, child), mapOf(2L to false)))
    }
}
