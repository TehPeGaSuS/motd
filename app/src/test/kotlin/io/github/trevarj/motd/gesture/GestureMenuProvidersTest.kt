package io.github.trevarj.motd.gesture

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MonitorQueryRow
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureMenuProvidersTest {
    @Test fun `pinned chats become open-chat leaves in chat-list order`() =
        runTest {
            val world = world()
            world.buffers.chats.value =
                listOf(
                    chatRow(1L, displayName = "#one", pinned = true),
                    chatRow(2L, displayName = "#two"),
                    chatRow(3L, displayName = "#three", pinned = true),
                )

            val leaves = world.providers.resolveProvider(provider(GestureProviderKind.PINNED_CHATS))

            assertEquals(listOf("#one", "#three"), leaves.map { it.label })
            assertEquals(listOf(GestureAction.OpenChat(1L), GestureAction.OpenChat(3L)), leaves.map { it.action })
        }

    @Test fun `unread chats skip muted, server and read rows`() =
        runTest {
            val world = world()
            world.buffers.chats.value =
                listOf(
                    chatRow(1L, displayName = "#read", unreadCount = 0),
                    chatRow(2L, displayName = "#muted", muted = true, unreadCount = 5),
                    chatRow(3L, displayName = "*", type = BufferType.SERVER, unreadCount = 5),
                    chatRow(4L, displayName = "#live", unreadCount = 5),
                )

            val leaves = world.providers.resolveProvider(provider(GestureProviderKind.UNREAD_CHATS))

            assertEquals(listOf("#live"), leaves.map { it.label })
        }

    @Test fun `a ring never grows past the provider's clamped limit`() =
        runTest {
            val world = world()
            world.buffers.chats.value = (1L..20L).map { chatRow(it, pinned = true) }

            // A stored limit outside 1..8 is clamped, so a hostile config cannot make an unhittable ring.
            val leaves = world.providers.resolveProvider(provider(GestureProviderKind.PINNED_CHATS, limit = 99))

            assertEquals(MAX_RING_SLICES, leaves.size)
        }

    @Test fun `recent DMs open by identity rather than by a stored room id`() =
        runTest {
            val world = world()
            world.buffers.queryConversations.value =
                listOf(
                    dmRow(1L, "carol", lastMessageTime = 30),
                    dmRow(1L, "alice", pinned = true, lastMessageTime = 10),
                    dmRow(2L, "bob", lastMessageTime = 20),
                )

            val leaves = world.providers.resolveProvider(provider(GestureProviderKind.RECENT_DMS))

            assertEquals(listOf("alice", "carol", "bob"), leaves.map { it.label })
            assertEquals(GestureAction.StartQuery(1L, "alice"), leaves.first().action)
        }

    @Test fun `friends come online first and offline ones land on the first connected network`() =
        runTest {
            val world = world()
            world.settings.state.value = Settings(friends = setOf("Zoe", "alice", "bob"))
            world.connections.states.value = mapOf(1L to readyState(), 2L to readyState())
            world.connections.presence.value =
                mapOf(
                    PresenceKey(2L, "zoe") to PresenceState.ONLINE,
                    PresenceKey(1L, "alice") to PresenceState.OFFLINE,
                )

            val leaves = world.providers.resolveProvider(provider(GestureProviderKind.FRIENDS))

            assertEquals(listOf("zoe", "alice", "bob"), leaves.map { it.label })
            assertEquals(
                listOf(
                    GestureAction.StartQuery(2L, "zoe"),
                    GestureAction.StartQuery(1L, "alice"),
                    GestureAction.StartQuery(1L, "bob"),
                ),
                leaves.map { it.action },
            )
        }

    @Test fun `friends vanish when there is nowhere to message them`() =
        runTest {
            val world = world()
            world.settings.state.value = Settings(friends = setOf("alice"))

            assertTrue(world.providers.resolveProvider(provider(GestureProviderKind.FRIENDS)).isEmpty())
        }

    @Test fun `networks offer the flip their current state allows`() =
        runTest {
            val world = world(networks = listOf(testNetwork(1L, "libera"), testNetwork(2L, "oftc")))
            world.connections.states.value = mapOf(1L to readyState())

            val leaves = world.providers.resolveProvider(provider(GestureProviderKind.NETWORKS))

            assertEquals(
                listOf(GestureAction.DisconnectNetwork(1L), GestureAction.ReconnectNetwork(2L)),
                leaves.map { it.action },
            )
            assertEquals(listOf("libera · disconnect", "oftc · connect"), leaves.map { it.label })
        }

    @Test fun `a provider kind from a newer build resolves to an empty ring`() =
        runTest {
            val world = world()
            world.buffers.chats.value = listOf(chatRow(1L, pinned = true))

            assertTrue(world.providers.resolveProvider(provider(GestureProviderKind.UNKNOWN)).isEmpty())
        }

    @Test fun `resolved leaf ids stay unique inside one ring`() =
        runTest {
            val world = world()
            world.buffers.chats.value = (1L..5L).map { chatRow(it, pinned = true) }

            val leaves = world.providers.resolveProvider(provider(GestureProviderKind.PINNED_CHATS))

            assertEquals(leaves.size, leaves.map { it.id }.distinct().size)
        }

    // --- pure helpers ---

    @Test fun `recent DM order is pinned, then recency, then name`() {
        val rows =
            listOf(
                dmRow(1L, "carol", lastMessageTime = null),
                dmRow(1L, "dave", lastMessageTime = 50),
                dmRow(1L, "alice", pinned = true, lastMessageTime = 1),
                dmRow(1L, "bob", lastMessageTime = null),
            )

        assertEquals(listOf("alice", "dave", "bob", "carol"), recentDmOrder(rows).map { it.displayName })
    }

    @Test fun `a friend online on a later network is placed there, not on the first one`() {
        val targets =
            friendTargets(
                friends = setOf("alice"),
                presence = mapOf(PresenceKey(3L, "alice") to PresenceState.ONLINE),
                readyNetworks = listOf(1L, 3L),
            )

        assertEquals(listOf(FriendTarget("alice", 3L, online = true)), targets)
    }

    @Test fun `blank and duplicate friend entries collapse`() {
        val targets =
            friendTargets(
                friends = setOf("Alice", "alice ", "  "),
                presence = emptyMap(),
                readyNetworks = listOf(1L),
            )

        assertEquals(listOf(FriendTarget("alice", 1L, online = false)), targets)
    }

    // --- fixtures ---

    private fun provider(
        kind: GestureProviderKind,
        limit: Int = DEFAULT_PROVIDER_LIMIT,
    ): GestureNode.Provider =
        GestureNode.Provider(
            id = "ring",
            label = kind.name,
            icon = GestureIcon.CHAT,
            kind = kind,
            limit = limit,
        )

    private fun dmRow(
        networkId: Long,
        nick: String,
        pinned: Boolean = false,
        lastMessageTime: Long? = null,
    ) = MonitorQueryRow(networkId, nick, pinned, lastMessageTime)

    private class World(
        val providers: GestureMenuProviders,
        val buffers: FakeBuffers,
        val connections: FakeConnections,
        val settings: FakeSettings,
    )

    private fun world(networks: List<io.github.trevarj.motd.data.db.NetworkEntity> = emptyList()): World {
        val buffers = FakeBuffers()
        val connections = FakeConnections()
        val settings = FakeSettings()
        return World(
            providers =
                GestureMenuProviders(
                    buffers,
                    FakeNetworks(networks),
                    connections,
                    settings,
                    // Mirrors gesture_network_leaf_connect / _disconnect; the strings themselves are
                    // resources, so the case only asserts which way the slice says it will flip.
                    networkLeafLabel = { name, connected ->
                        if (connected) "$name · disconnect" else "$name · connect"
                    },
                ),
            buffers = buffers,
            connections = connections,
            settings = settings,
        )
    }
}
