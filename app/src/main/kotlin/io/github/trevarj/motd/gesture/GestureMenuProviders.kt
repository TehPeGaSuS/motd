package io.github.trevarj.motd.gesture

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MonitorQueryRow
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.normalizeNick
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.unreadChatRows
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fans a [GestureNode.Provider] out into the leaves its ring shows.
 *
 * Resolution is a *snapshot* taken when the menu opens, never a live subscription: a ring whose
 * slices moved under a finger already committed to a slice would execute something the user never
 * aimed at. Everything here therefore reads `first()` and stops.
 *
 * The resolved leaves are throwaway — they are never persisted, so their ids only have to be unique
 * within the ring that is on screen.
 */
@Singleton
class GestureMenuProviders internal constructor(
    private val buffers: BufferRepository,
    private val networks: NetworkRepository,
    private val connections: ConnectionManager,
    private val settings: SettingsRepository,
    /**
     * The slice has to say which way it will flip, because the action depends on the current state.
     * Injected as a seam so this class stays `Context`-free and the suffix stays localizable.
     */
    private val networkLeafLabel: (name: String, connected: Boolean) -> String,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        buffers: BufferRepository,
        networks: NetworkRepository,
        connections: ConnectionManager,
        settings: SettingsRepository,
    ) : this(
        buffers = buffers,
        networks = networks,
        connections = connections,
        settings = settings,
        networkLeafLabel = { name, connected ->
            context.getString(
                if (connected) R.string.gesture_network_leaf_disconnect else R.string.gesture_network_leaf_connect,
                name,
            )
        },
    )

    suspend fun resolveProvider(node: GestureNode.Provider): List<GestureNode.Leaf> {
        val leaves =
            when (node.kind) {
                GestureProviderKind.PINNED_CHATS -> chatLeaves(node) { rows -> rows.filter { it.pinned } }

                GestureProviderKind.UNREAD_CHATS -> chatLeaves(node) { rows -> unreadChatRows(rows) }

                GestureProviderKind.RECENT_DMS -> recentDmLeaves(node)

                GestureProviderKind.FRIENDS -> friendLeaves(node)

                GestureProviderKind.NETWORKS -> networkLeaves(node)

                // A kind invented by a newer build: an empty ring, never a broken menu.
                GestureProviderKind.UNKNOWN -> emptyList()
            }
        return leaves.take(node.clampedLimit)
    }

    private suspend fun chatLeaves(
        node: GestureNode.Provider,
        select: (List<ChatListRow>) -> List<ChatListRow>,
    ): List<GestureNode.Leaf> =
        select(buffers.observeChatList().first()).map { row ->
            GestureNode.Leaf(
                id = providerLeafId(node, "chat", row.bufferId.toString()),
                label = row.displayName,
                icon = node.icon,
                action = GestureAction.OpenChat(row.bufferId),
            )
        }

    private suspend fun recentDmLeaves(node: GestureNode.Provider): List<GestureNode.Leaf> =
        recentDmOrder(buffers.observeQueryConversations().first()).map { row ->
            GestureNode.Leaf(
                id = providerLeafId(node, "dm", "${row.networkId}:${row.displayName}"),
                label = row.displayName,
                icon = GestureIcon.PERSON,
                // Identity, not a row id: a DM re-opened from here should land on the live query
                // buffer even if the old one was closed since the menu was authored.
                action = GestureAction.StartQuery(row.networkId, row.displayName),
            )
        }

    private suspend fun friendLeaves(node: GestureNode.Provider): List<GestureNode.Leaf> {
        val targets =
            friendTargets(
                friends = settings.settings.first().friends,
                presence = connections.presenceStates.value,
                readyNetworks = readyNetworkIds(),
            )
        return targets.map { target ->
            GestureNode.Leaf(
                id = providerLeafId(node, "friend", "${target.networkId}:${target.nick}"),
                label = target.nick,
                icon = if (target.online) GestureIcon.PERSON else GestureIcon.PEOPLE,
                action = GestureAction.StartQuery(target.networkId, target.nick),
            )
        }
    }

    private suspend fun networkLeaves(node: GestureNode.Provider): List<GestureNode.Leaf> {
        val ready = readyNetworkIds().toSet()
        return networks.observeNetworks().first().map { network ->
            val connected = network.id in ready
            GestureNode.Leaf(
                id = providerLeafId(node, "network", network.id.toString()),
                label = networkLeafLabel(network.name, connected),
                icon = if (connected) GestureIcon.POWER else GestureIcon.REFRESH,
                action = networkLeafAction(network.id, connected),
            )
        }
    }

    private fun readyNetworkIds(): List<Long> =
        connections.connectionStates.value
            .filterValues { it is IrcClientState.Ready }
            .keys
            .sorted()
}

/** A friend the menu can actually message, and where. */
internal data class FriendTarget(
    val nick: String,
    val networkId: Long,
    val online: Boolean,
)

/** Pinned DMs first, then most recent; a query with no messages yet sorts last. */
internal fun recentDmOrder(rows: List<MonitorQueryRow>): List<MonitorQueryRow> =
    rows.sortedWith(
        compareByDescending<MonitorQueryRow> { it.pinned }
            .thenByDescending { it.lastMessageTime ?: 0L }
            .thenBy { it.displayName },
    )

/**
 * Friends worth showing, online ones first.
 *
 * A friend is placed on the network MONITOR reports them online on; failing that, on the first
 * connected network, so the slice still opens a query rather than disappearing. A friend with no
 * connected network at all is dropped: there is nowhere to send to, and a slice that cannot act is
 * worse than a shorter ring.
 */
internal fun friendTargets(
    friends: Set<String>,
    presence: Map<PresenceKey, PresenceState>,
    readyNetworks: List<Long>,
): List<FriendTarget> {
    if (readyNetworks.isEmpty()) return emptyList()
    return friends
        .map(::normalizeNick)
        .filter { it.isNotBlank() }
        .distinct()
        .map { nick ->
            val onlineOn =
                readyNetworks.firstOrNull { networkId ->
                    presence[PresenceKey(networkId, nick)] == PresenceState.ONLINE
                }
            FriendTarget(nick, onlineOn ?: readyNetworks.first(), online = onlineOn != null)
        }.sortedWith(compareByDescending<FriendTarget> { it.online }.thenBy { it.nick })
}

/** Connected networks offer the disconnect; everything else offers the way back on. */
internal fun networkLeafAction(
    networkId: Long,
    connected: Boolean,
): GestureAction = if (connected) GestureAction.DisconnectNetwork(networkId) else GestureAction.ReconnectNetwork(networkId)

private fun providerLeafId(
    node: GestureNode.Provider,
    kind: String,
    key: String,
): String = "${node.id}/$kind/$key"
