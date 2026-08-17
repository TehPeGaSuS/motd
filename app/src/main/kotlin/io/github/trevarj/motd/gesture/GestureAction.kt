package io.github.trevarj.motd.gesture

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject

/**
 * What a leaf slice does when the finger lifts on it.
 *
 * Every variant is data only: resolving ids to chats, networks, or app state is the dispatcher's job
 * so the menu itself stays a pure, serialisable document.
 */
@Serializable(with = GestureActionSerializer::class)
sealed interface GestureAction {
    /** Open a specific buffer. */
    @Serializable
    data class OpenChat(val bufferId: Long) : GestureAction

    /** Open global search. */
    @Serializable
    data object OpenSearch : GestureAction

    /** Channel info for whichever chat is open behind the orb. */
    @Serializable
    data object ChannelInfoCurrent : GestureAction

    /** Jump to the first chat with unread messages. */
    @Serializable
    data object NextUnread : GestureAction

    /** Back out to the chat list. */
    @Serializable
    data object OpenChatList : GestureAction

    /** Prefill the current composer with a mention of [nick]. */
    @Serializable
    data class InsertMention(val nick: String) : GestureAction

    /** Prefill the current composer with canned [text]. */
    @Serializable
    data class InsertSnippet(val text: String) : GestureAction

    /** Open (creating if needed) a query with [nick] on [networkId]. */
    @Serializable
    data class StartQuery(val networkId: Long, val nick: String) : GestureAction

    /** Flip self-away across connected networks; [message] is used when going away. */
    @Serializable
    data class ToggleAway(val message: String? = null) : GestureAction

    @Serializable
    data class ReconnectNetwork(val networkId: Long) : GestureAction

    @Serializable
    data class DisconnectNetwork(val networkId: Long) : GestureAction

    @Serializable
    data class JoinChannel(val networkId: Long, val channel: String, val key: String? = null) : GestureAction

    /** Mark every visible chat read. */
    @Serializable
    data object MarkAllRead : GestureAction

    /** Swap the active palette for its light/dark partner. */
    @Serializable
    data object ToggleTheme : GestureAction

    /** Open the attachment sheet for the chat behind the orb. */
    @Serializable
    data object AttachCurrent : GestureAction

    /** An action this build cannot run, kept verbatim so a newer build still finds it intact. */
    data class Unknown(val raw: JsonObject) : GestureAction
}

/**
 * Same tree dispatch as [GestureNodeSerializer], for the same reason: an action written by a newer
 * build has to survive an older build re-saving the menu, which rules out a discriminator the
 * encoder insists on writing itself.
 */
object GestureActionSerializer : KSerializer<GestureAction> {
    private const val OPEN_CHAT = "openChat"
    private const val OPEN_SEARCH = "openSearch"
    private const val CHANNEL_INFO_CURRENT = "channelInfoCurrent"
    private const val NEXT_UNREAD = "nextUnread"
    private const val OPEN_CHAT_LIST = "openChatList"
    private const val INSERT_MENTION = "insertMention"
    private const val INSERT_SNIPPET = "insertSnippet"
    private const val START_QUERY = "startQuery"
    private const val TOGGLE_AWAY = "toggleAway"
    private const val RECONNECT_NETWORK = "reconnectNetwork"
    private const val DISCONNECT_NETWORK = "disconnectNetwork"
    private const val JOIN_CHANNEL = "joinChannel"
    private const val MARK_ALL_READ = "markAllRead"
    private const val TOGGLE_THEME = "toggleTheme"
    private const val ATTACH_CURRENT = "attachCurrent"

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("io.github.trevarj.motd.gesture.GestureAction")

    override fun serialize(encoder: Encoder, value: GestureAction) {
        val encoderJson = encoder.asJsonEncoder()
        val json = encoderJson.json
        val element = when (value) {
            is GestureAction.OpenChat -> json.typed(OPEN_CHAT, GestureAction.OpenChat.serializer(), value)
            is GestureAction.OpenSearch -> json.typed(OPEN_SEARCH, GestureAction.OpenSearch.serializer(), value)
            is GestureAction.ChannelInfoCurrent ->
                json.typed(CHANNEL_INFO_CURRENT, GestureAction.ChannelInfoCurrent.serializer(), value)
            is GestureAction.NextUnread -> json.typed(NEXT_UNREAD, GestureAction.NextUnread.serializer(), value)
            is GestureAction.OpenChatList -> json.typed(OPEN_CHAT_LIST, GestureAction.OpenChatList.serializer(), value)
            is GestureAction.InsertMention ->
                json.typed(INSERT_MENTION, GestureAction.InsertMention.serializer(), value)
            is GestureAction.InsertSnippet ->
                json.typed(INSERT_SNIPPET, GestureAction.InsertSnippet.serializer(), value)
            is GestureAction.StartQuery -> json.typed(START_QUERY, GestureAction.StartQuery.serializer(), value)
            is GestureAction.ToggleAway -> json.typed(TOGGLE_AWAY, GestureAction.ToggleAway.serializer(), value)
            is GestureAction.ReconnectNetwork ->
                json.typed(RECONNECT_NETWORK, GestureAction.ReconnectNetwork.serializer(), value)
            is GestureAction.DisconnectNetwork ->
                json.typed(DISCONNECT_NETWORK, GestureAction.DisconnectNetwork.serializer(), value)
            is GestureAction.JoinChannel -> json.typed(JOIN_CHANNEL, GestureAction.JoinChannel.serializer(), value)
            is GestureAction.MarkAllRead -> json.typed(MARK_ALL_READ, GestureAction.MarkAllRead.serializer(), value)
            is GestureAction.ToggleTheme -> json.typed(TOGGLE_THEME, GestureAction.ToggleTheme.serializer(), value)
            is GestureAction.AttachCurrent ->
                json.typed(ATTACH_CURRENT, GestureAction.AttachCurrent.serializer(), value)
            is GestureAction.Unknown -> value.raw
        }
        encoderJson.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): GestureAction {
        val json = decoder.asJsonDecoder()
        // An action that is not even an object carries nothing worth keeping here; failing lets the
        // node above keep its whole raw form instead of quietly replacing the action with a stub.
        val obj = json.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("A gesture action must be a JSON object.")
        val serializer: KSerializer<out GestureAction> = when (obj.stringOrNull(GESTURE_TYPE_KEY)) {
            OPEN_CHAT -> GestureAction.OpenChat.serializer()
            OPEN_SEARCH -> GestureAction.OpenSearch.serializer()
            CHANNEL_INFO_CURRENT -> GestureAction.ChannelInfoCurrent.serializer()
            NEXT_UNREAD -> GestureAction.NextUnread.serializer()
            OPEN_CHAT_LIST -> GestureAction.OpenChatList.serializer()
            INSERT_MENTION -> GestureAction.InsertMention.serializer()
            INSERT_SNIPPET -> GestureAction.InsertSnippet.serializer()
            START_QUERY -> GestureAction.StartQuery.serializer()
            TOGGLE_AWAY -> GestureAction.ToggleAway.serializer()
            RECONNECT_NETWORK -> GestureAction.ReconnectNetwork.serializer()
            DISCONNECT_NETWORK -> GestureAction.DisconnectNetwork.serializer()
            JOIN_CHANNEL -> GestureAction.JoinChannel.serializer()
            MARK_ALL_READ -> GestureAction.MarkAllRead.serializer()
            TOGGLE_THEME -> GestureAction.ToggleTheme.serializer()
            ATTACH_CURRENT -> GestureAction.AttachCurrent.serializer()
            else -> return GestureAction.Unknown(obj)
        }
        return runCatching { json.json.decodeFromJsonElement(serializer, obj.withoutType()) }
            .getOrElse { GestureAction.Unknown(obj) }
    }
}
