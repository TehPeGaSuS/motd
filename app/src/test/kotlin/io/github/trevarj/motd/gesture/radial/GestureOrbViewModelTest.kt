package io.github.trevarj.motd.gesture.radial

import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.gesture.FakeAppearance
import io.github.trevarj.motd.gesture.FakeBuffers
import io.github.trevarj.motd.gesture.FakeConnections
import io.github.trevarj.motd.gesture.FakeForegroundBuffer
import io.github.trevarj.motd.gesture.FakeGesturePrefs
import io.github.trevarj.motd.gesture.FakeNetworks
import io.github.trevarj.motd.gesture.FakeReadMarkers
import io.github.trevarj.motd.gesture.FakeSettings
import io.github.trevarj.motd.gesture.GestureAction
import io.github.trevarj.motd.gesture.GestureActionDispatcher
import io.github.trevarj.motd.gesture.GestureIcon
import io.github.trevarj.motd.gesture.GestureMenuConfig
import io.github.trevarj.motd.gesture.GestureMenuProviders
import io.github.trevarj.motd.gesture.GestureNode
import io.github.trevarj.motd.gesture.chatRow
import io.github.trevarj.motd.gesture.readyState
import io.github.trevarj.motd.gesture.testNetwork
import io.github.trevarj.motd.ui.chat.AttachmentRequestStore
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric only because the dispatcher's [ComposerDraftStore] is Room-backed; nothing here
 * touches a table.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GestureOrbViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MotdDatabase

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = inMemoryDb()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    // --- pure label and state helpers ---

    @Test fun `a self-away toggle names the direction it will actually go`() {
        val leaf = GestureNode.Leaf("away", "Away", GestureIcon.AWAY, GestureAction.ToggleAway())

        assertEquals("Away", entryLabel(leaf, away = false, backLabel = "Back"))
        assertEquals("Back", entryLabel(leaf, away = true, backLabel = "Back"))
    }

    @Test fun `only a self-away toggle is ever relabelled`() {
        val other = GestureNode.Leaf("unread", "Next unread", GestureIcon.BOLT, GestureAction.NextUnread)
        val submenu = GestureNode.Submenu("tools", "Tools")

        assertEquals("Next unread", entryLabel(other, away = true, backLabel = "Back"))
        assertEquals("Tools", entryLabel(submenu, away = true, backLabel = "Back"))
    }

    /** Away state on a network we are not connected to says nothing about the toggle's direction. */
    @Test fun `away only counts on a connected network`() {
        assertTrue(anySelfAway(mapOf(1L to readyState()), mapOf(1L to "brb")))
        assertTrue(!anySelfAway(mapOf(1L to readyState()), mapOf(2L to "brb")))
        assertTrue(!anySelfAway(emptyMap(), mapOf(1L to "brb")))
    }

    // --- view model ---

    @Test fun `state follows the lab flag and the stored placement`() = runTest {
        val world = world()
        world.prefs.enabledState.value = true
        world.prefs.orbState.value = OrbPlacement(OrbEdge.LEFT, 0.25f)

        val state = world.model.state.first { it.enabled }

        assertEquals(OrbPlacement(OrbEdge.LEFT, 0.25f), state.placement)
    }

    @Test fun `moving the orb writes the new placement through`() = runTest {
        val world = world()

        world.model.setPlacement(OrbPlacement(OrbEdge.LEFT, 0.8f))
        runCurrent()

        assertEquals(OrbPlacement(OrbEdge.LEFT, 0.8f), world.prefs.orbState.value)
    }

    @Test fun `resolving the menu fans providers out into real slices`() = runTest {
        val world = world()
        world.connections.states.value = mapOf(1L to readyState())
        world.networks.rows.value = listOf(testNetwork(1L, "libera"))
        world.buffers.chats.value = listOf(chatRow(7L, displayName = "#kotlin", unreadCount = 3))

        val root = world.model.resolveMenu()

        assertEquals("default-root", root.id)
        assertEquals(8, root.children.size)
        val unread = root.children.first { it.id == "default-unread" }
        assertEquals(listOf("#kotlin"), unread.children.map { it.label })
        assertEquals(GestureAction.OpenChat(7L), unread.children.single().action)
        val networks = root.children.first { it.id == "default-networks" }
        assertEquals(listOf(GestureAction.DisconnectNetwork(1L)), networks.children.map { it.action })
    }

    @Test fun `a resolved submenu keeps its own leaves and their actions`() = runTest {
        val world = world()

        val tools = world.model.resolveMenu().children.first { it.id == "default-tools" }

        assertEquals(listOf("Search", "Channel info", "Attach", "Light/dark"), tools.children.map { it.label })
        assertEquals(GestureAction.OpenSearch, tools.children.first().action)
        assertNull(tools.action)
    }

    @Test fun `the away slice is resolved against the live connection state`() = runTest {
        val world = world()
        world.connections.states.value = mapOf(1L to readyState())
        world.connections.away.value = mapOf(1L to "brb")

        val away = world.model.resolveMenu().children.first { it.id == "default-away" }

        assertEquals("Back", away.label)
    }

    @Test fun `an empty provider leaves an inert slice rather than removing it`() = runTest {
        val world = world()

        val friends = world.model.resolveMenu().children.first { it.id == "default-friends" }

        assertTrue(friends.children.isEmpty())
        assertNull(friends.action)
    }

    @Test fun `executing a slice goes through the dispatcher`() = runTest {
        val world = world()
        world.connections.states.value = mapOf(1L to readyState())

        world.model.execute(GestureAction.ToggleAway("stepping out"))
        runCurrent()

        assertEquals(listOf(1L to "stepping out"), world.connections.awayWrites)
    }

    private class World(
        val model: GestureOrbViewModel,
        val prefs: FakeGesturePrefs,
        val connections: FakeConnections,
        val buffers: FakeBuffers,
        val networks: FakeNetworks,
    )

    private fun world(menu: GestureMenuConfig = GestureMenuConfig()): World {
        val prefs = FakeGesturePrefs(menu = menu)
        val connections = FakeConnections()
        val buffers = FakeBuffers()
        val networks = FakeNetworks()
        val settings = FakeSettings(Settings())
        val providers = GestureMenuProviders(
            buffers = buffers,
            networks = networks,
            connections = connections,
            settings = settings,
            networkLeafLabel = { name, connected -> if (connected) "$name · disconnect" else "$name · connect" },
        )
        val actions = GestureActionDispatcher(
            connections = connections,
            buffers = buffers,
            networks = networks,
            drafts = ComposerDraftStore(db),
            attachments = AttachmentRequestStore(),
            appearance = FakeAppearance(),
            foregroundBuffer = FakeForegroundBuffer(null),
            readMarkers = FakeReadMarkers(),
            defaultAwayMessage = { "Away" },
            systemDark = { false },
        )
        val model = GestureOrbViewModel(
            prefs = prefs,
            providers = providers,
            dispatcher = actions,
            connections = connections,
            appearance = FakeAppearance(),
            backLabel = "Back",
        )
        return World(model, prefs, connections, buffers, networks)
    }
}
