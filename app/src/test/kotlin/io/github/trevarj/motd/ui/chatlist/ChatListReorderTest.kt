package io.github.trevarj.motd.ui.chatlist

import androidx.lifecycle.SavedStateHandle
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.OnboardingPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.BufferReadMarker
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ChannelCloseCoordinator
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.ReadMarkerSnapshotter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Manual drawer ordering as the user experiences it: when a move is written, what the drawer shows
 * between the write and Room catching up, and what a drag leaves behind when it ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListReorderTest {

    /** Records every reorder write and only publishes it when the test says Room caught up. */
    private class FakeNetworkRepository(initial: List<NetworkEntity>) : NetworkRepository {
        val networks = MutableStateFlow(initial)
        val writes = mutableListOf<List<Long>>()

        override fun observeNetworks(): Flow<List<NetworkEntity>> = networks
        override suspend fun addNetwork(n: NetworkEntity): Long = 0
        override suspend fun updateNetwork(n: NetworkEntity) = Unit
        override suspend fun deleteNetwork(id: Long) = Unit
        override suspend fun reorderNetworks(orderedIds: List<Long>) { writes += orderedIds }
        override suspend fun networkById(id: Long): NetworkEntity? = null
        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()

        /** Publish the last written order the way Room's invalidation eventually would. */
        fun publishLastWrite() {
            val order = writes.last()
            networks.value = networks.value.sortedBy { order.indexOf(it.id) }
        }
    }

    private class FakeBufferRepository : BufferRepository {
        override fun observeChatList(): Flow<List<ChatListRow>> = flowOf(emptyList())
        override fun observeBuffer(id: Long): Flow<BufferEntity?> = flowOf(null)
        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())
        override suspend fun setPinned(id: Long, pinned: Boolean) = Unit
        override suspend fun setMuted(id: Long, muted: Boolean): MuteBacklogSuppression? = null
        override suspend fun setLayoutDensityOverride(id: Long, layout: LayoutDensity?): Boolean = true
        override suspend fun deleteBuffer(id: Long) = Unit
    }

    private class FakeConnectionManager : ConnectionManager {
        override val connectionStates = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
        override fun clientFor(networkId: Long): IrcClient? = null
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun sendMessage(bufferId: Long, text: String, replyToEventId: Long?) =
            io.github.trevarj.motd.service.SendAcceptance.Accepted(emptyList())
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String) = Unit
        override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 0
        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
        override suspend fun markRead(
            bufferId: Long,
            anchor: io.github.trevarj.motd.data.db.TimelineAnchor,
        ) = Unit
        override suspend fun evaluatePushMode() = Unit
        override val certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val settings = MutableStateFlow(
            Settings(ThemeMode.SYSTEM, true, DeliveryMode.PERSISTENT_SOCKET),
        )
        override suspend fun setThemeMode(m: ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setDeliveryMode(m: DeliveryMode) = Unit
        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit
        override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit
        override suspend fun setNickColorPalette(p: NickColorPalette) = Unit
        override suspend fun setNickColorOverride(nick: String, hue: Int?) = Unit
        override suspend fun setFriend(nick: String, isFriend: Boolean) = Unit
        override suspend fun setFool(nick: String, isFool: Boolean) = Unit
        override suspend fun setFoolsMode(m: FoolsMode) = Unit
        override suspend fun setShowJoinPartQuit(show: Boolean) = Unit
        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit
        override suspend fun setChatWallpaper(w: io.github.trevarj.motd.data.prefs.ChatWallpaper) = Unit
        override suspend fun setShowComposerEmoji(show: Boolean) = Unit
        override suspend fun setChatSoundsEnabled(enabled: Boolean) = Unit
    }

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun net(id: Long, name: String, role: NetworkRole = NetworkRole.DIRECT, parentId: Long? = null) =
        NetworkEntity(
            id = id, name = name, role = role, parentId = parentId,
            host = "$name.example", port = 6697, nick = "me", username = "me", realname = "Me",
        )

    /** libera, soju(oftc, ergo), hackint — the shape a soju user actually has. */
    private val networks = listOf(
        net(1, "libera"),
        net(2, "soju", NetworkRole.BOUNCER_ROOT),
        net(3, "oftc", NetworkRole.BOUNCER_CHILD, parentId = 2),
        net(4, "ergo", NetworkRole.BOUNCER_CHILD, parentId = 2),
        net(5, "hackint"),
    )

    private fun vm(repository: NetworkRepository) = ChatListViewModel(
        bufferRepository = FakeBufferRepository(),
        networkRepository = repository,
        connectionManager = FakeConnectionManager(),
        channelCloseCoordinator = object : ChannelCloseCoordinator {
            override fun start() = Unit
            override suspend fun requestClose(bufferId: Long) = Unit
        },
        readMarkerRepository = object : ReadMarkerSnapshotter {
            override suspend fun latestIncoming(bufferIds: Collection<Long>): List<BufferReadMarker> =
                emptyList()
        },
        settingsRepository = FakeSettingsRepository(),
        onboardingPrefs = object : OnboardingPrefs {
            override val completed = flowOf(true)
            override suspend fun markCompleted() = Unit
        },
        savedStateHandle = SavedStateHandle(),
    )

    private fun TestScope.collecting(viewModel: ChatListViewModel): Job =
        launch { viewModel.state.collect {} }.also { runCurrent() }

    private fun order(viewModel: ChatListViewModel) =
        viewModel.state.value.drawerRows.map(DrawerRow::networkId)

    @Test
    fun `a move action is persisted at once and shown before Room agrees`() = runTest {
        val repository = FakeNetworkRepository(networks)
        val viewModel = vm(repository)
        val collection = collecting(viewModel)

        viewModel.moveNetwork(networkId = 5, delta = -1)
        runCurrent()

        // One finished intent, one write — no debounce to lose if the process dies here.
        assertEquals(listOf(listOf(1L, 5L, 2L, 3L, 4L)), repository.writes)
        // The drawer moves immediately rather than waiting for the round trip through Room.
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), order(viewModel))

        repository.publishLastWrite()
        runCurrent()

        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), order(viewModel))
        collection.cancel()
    }

    @Test
    fun `a move that cannot happen writes nothing`() = runTest {
        val repository = FakeNetworkRepository(networks)
        val viewModel = vm(repository)
        val collection = collecting(viewModel)

        viewModel.moveNetwork(networkId = 1, delta = -1) // already first
        viewModel.moveNetwork(networkId = 5, delta = 1) // already last
        viewModel.moveNetwork(networkId = 3, delta = -1) // first child of its root
        viewModel.moveNetwork(networkId = 77, delta = 1) // not in the drawer
        runCurrent()

        assertEquals(emptyList<List<Long>>(), repository.writes)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), order(viewModel))
        collection.cancel()
    }

    @Test
    fun `a drag writes once when it ends, not once per row it crosses`() = runTest {
        val repository = FakeNetworkRepository(networks)
        val viewModel = vm(repository)
        val collection = collecting(viewModel)

        // Drag hackint to the top: two steps past libera and past the whole soju group.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), order(viewModel))
        val afterFirst = viewModel.previewNetworkMove(networkId = 5, delta = -1)
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L), afterFirst?.map(DrawerRow::networkId))
        viewModel.previewNetworkMove(networkId = 5, delta = -1)
        runCurrent()

        // Steps the finger only passed through are never persisted.
        assertEquals(emptyList<List<Long>>(), repository.writes)
        assertEquals(listOf(5L, 1L, 2L, 3L, 4L), order(viewModel))

        viewModel.commitNetworkOrder()
        runCurrent()

        assertEquals(listOf(listOf(5L, 1L, 2L, 3L, 4L)), repository.writes)
        collection.cancel()
    }

    @Test
    fun `consecutive drag steps compose without waiting for a recomposition`() = runTest {
        val repository = FakeNetworkRepository(networks)
        val viewModel = vm(repository)
        val collection = collecting(viewModel)

        // No runCurrent between the steps: one fast frame can cross several rows, and each step must
        // build on the previous one rather than on the last published state.
        viewModel.previewNetworkMove(networkId = 5, delta = -1)
        val afterSecond = viewModel.previewNetworkMove(networkId = 5, delta = -1)
        runCurrent()

        assertEquals(listOf(5L, 1L, 2L, 3L, 4L), afterSecond?.map(DrawerRow::networkId))
        assertEquals(listOf(5L, 1L, 2L, 3L, 4L), order(viewModel))
        collection.cancel()
    }

    @Test
    fun `a drag that ends without a move commits nothing`() = runTest {
        val repository = FakeNetworkRepository(networks)
        val viewModel = vm(repository)
        val collection = collecting(viewModel)

        // Picked the row up, never crossed a neighbour, let go.
        viewModel.commitNetworkOrder()
        runCurrent()

        assertEquals(emptyList<List<Long>>(), repository.writes)
        collection.cancel()
    }

    @Test
    fun `a bouncer child reorder keeps the group intact`() = runTest {
        val repository = FakeNetworkRepository(networks)
        val viewModel = vm(repository)
        val collection = collecting(viewModel)

        viewModel.moveNetwork(networkId = 4, delta = -1)
        runCurrent()

        assertEquals(listOf(listOf(1L, 2L, 4L, 3L, 5L)), repository.writes)
        assertEquals(listOf(1L, 2L, 4L, 3L, 5L), order(viewModel))
        collection.cancel()
    }

    @Test
    fun `a network added mid-drag does not disturb the pending order`() = runTest {
        val repository = FakeNetworkRepository(networks)
        val viewModel = vm(repository)
        val collection = collecting(viewModel)

        viewModel.previewNetworkMove(networkId = 5, delta = -1)
        runCurrent()
        repository.networks.value = repository.networks.value + net(6, "ergo2")
        runCurrent()

        // The newcomer lands at the end; the arrangement under the finger is untouched.
        assertEquals(listOf(1L, 5L, 2L, 3L, 4L, 6L), order(viewModel))

        viewModel.commitNetworkOrder()
        runCurrent()

        assertEquals(listOf(listOf(1L, 5L, 2L, 3L, 4L, 6L)), repository.writes)
        collection.cancel()
    }
}
