package io.github.trevarj.motd.gesture

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Format version stamped into every persisted menu. */
const val GESTURE_MENU_VERSION = 1

/** Slices one ring can carry before it stops being hittable with a thumb. */
const val MAX_RING_SLICES = 8

/** Root ring plus at most two nested rings. */
const val MAX_GESTURE_RINGS = 3

/** How many entries a dynamic provider fans out by default. */
const val DEFAULT_PROVIDER_LIMIT = 6

/** Discriminator key written for every node and action. */
internal const val GESTURE_TYPE_KEY = "type"

/** Fresh id for a node the editor creates. Ids only have to be unique within one menu. */
fun newGestureNodeId(): String = UUID.randomUUID().toString()

/**
 * The whole user-editable gesture menu: a versioned tree whose root is the ring the orb opens.
 *
 * Persisted in `GesturePrefs` and carried in configuration backups, so both directions of version
 * skew matter — see [GestureNodeSerializer] for how nodes written by a newer build survive being
 * loaded, edited, and saved again by an older one.
 */
@Serializable
data class GestureMenuConfig(
    val version: Int = GESTURE_MENU_VERSION,
    val root: GestureNode.Submenu = DEFAULT_GESTURE_ROOT,
)

/** Where a dynamic node gets its entries from at menu-open time. */
@Serializable(with = GestureProviderKindSerializer::class)
enum class GestureProviderKind {
    PINNED_CHATS,
    UNREAD_CHATS,
    RECENT_DMS,
    FRIENDS,
    NETWORKS,

    /** A kind this build does not know; resolves to nothing rather than failing the menu. */
    UNKNOWN,
}

/**
 * One entry in the menu tree.
 *
 * [Submenu] and [Provider] both open a ring of their own when the finger descends into them, which
 * is why both count against the ring-depth limit; [Leaf] executes and [Unknown] is inert.
 */
@Serializable(with = GestureNodeSerializer::class)
sealed interface GestureNode {
    val id: String
    val label: String
    val icon: GestureIcon

    /** A static ring of hand-authored children. */
    @Serializable
    data class Submenu(
        override val id: String,
        override val label: String,
        override val icon: GestureIcon = GestureIcon.FOLDER,
        val children: List<GestureNode> = emptyList(),
    ) : GestureNode

    /** A slice that runs [action] when the finger lifts on it. */
    @Serializable
    data class Leaf(
        override val id: String,
        override val label: String,
        override val icon: GestureIcon = GestureIcon.BOLT,
        val action: GestureAction,
    ) : GestureNode

    /** A ring filled in from live app state (pinned chats, friends, networks, …). */
    @Serializable
    data class Provider(
        override val id: String,
        override val label: String,
        override val icon: GestureIcon = GestureIcon.CHAT,
        val kind: GestureProviderKind = GestureProviderKind.UNKNOWN,
        val limit: Int = DEFAULT_PROVIDER_LIMIT,
    ) : GestureNode {
        /** Entries actually fanned out; a stored limit outside 1..8 is clamped, never honoured. */
        val clampedLimit: Int get() = limit.coerceIn(1, MAX_RING_SLICES)
    }

    /**
     * A node this build cannot interpret, kept exactly as it was read.
     *
     * Written back verbatim so a menu authored on a newer build is not quietly amputated by an
     * older one that merely opened the editor.
     */
    data class Unknown(
        val raw: JsonObject,
    ) : GestureNode {
        override val id: String
            get() = raw.stringOrNull("id") ?: "unknown-${raw.hashCode().toUInt().toString(16)}"
        override val label: String get() = raw.stringOrNull("label").orEmpty()
        override val icon: GestureIcon get() = GestureIcon.UNKNOWN
    }
}

/** Children of a ring-opening node; empty for anything that does not open a ring. */
val GestureNode.childNodes: List<GestureNode>
    get() = (this as? GestureNode.Submenu)?.children ?: emptyList()

/** True when descending into this node opens another ring. */
val GestureNode.opensRing: Boolean
    get() = this is GestureNode.Submenu || this is GestureNode.Provider

/**
 * Slices the ring behind [this] node would show: a submenu's authored children, a provider's clamped
 * limit. Zero for nodes that open no ring.
 */
val GestureNode.ringSlices: Int
    get() =
        when (this) {
            is GestureNode.Submenu -> children.size
            is GestureNode.Provider -> clampedLimit
            else -> 0
        }

// --- serialization -----------------------------------------------------------------------------

/**
 * The one JSON dialect for gesture menus, deliberately forgiving in every direction: defaults are
 * written so an older build sees complete objects, unknown keys are dropped rather than fatal, and
 * out-of-range enums coerce to their default instead of failing the decode.
 */
internal val gestureMenuJson: Json =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

internal fun encodeGestureMenu(config: GestureMenuConfig): String = gestureMenuJson.encodeToString(GestureMenuConfig.serializer(), config)

/** Decodes a stored menu; anything unreadable falls back to the built-in default. */
internal fun decodeGestureMenu(raw: String?): GestureMenuConfig {
    if (raw.isNullOrBlank()) return GestureMenuConfig()
    return runCatching { gestureMenuJson.decodeFromString(GestureMenuConfig.serializer(), raw) }
        .getOrElse { GestureMenuConfig() }
}

/**
 * Tree-dispatched polymorphism for [GestureNode].
 *
 * Hand-rolled rather than `Json`'s built-in polymorphism because the built-in encoder always writes
 * *its own* class discriminator: an [GestureNode.Unknown] carrying a foreign `type` could never be
 * re-emitted as itself. Here the discriminator is just another key in the tree, so an unrecognised
 * node round-trips byte for byte.
 */
object GestureNodeSerializer : KSerializer<GestureNode> {
    private const val SUBMENU = "submenu"
    private const val LEAF = "leaf"
    private const val PROVIDER = "provider"

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("io.github.trevarj.motd.gesture.GestureNode")

    override fun serialize(
        encoder: Encoder,
        value: GestureNode,
    ) {
        val json = encoder.asJsonEncoder()
        val element =
            when (value) {
                is GestureNode.Submenu -> json.json.typed(SUBMENU, GestureNode.Submenu.serializer(), value)
                is GestureNode.Leaf -> json.json.typed(LEAF, GestureNode.Leaf.serializer(), value)
                is GestureNode.Provider -> json.json.typed(PROVIDER, GestureNode.Provider.serializer(), value)
                is GestureNode.Unknown -> value.raw
            }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): GestureNode {
        val json = decoder.asJsonDecoder()
        val element = json.decodeJsonElement()
        // A ring entry that is not an object holds nothing this build could ever run; it becomes an
        // inert slice rather than failing its parent, which would cost the whole surrounding ring.
        val obj = element as? JsonObject ?: return GestureNode.Unknown(JsonObject(emptyMap()))
        val serializer =
            when (obj.stringOrNull(GESTURE_TYPE_KEY)) {
                SUBMENU -> GestureNode.Submenu.serializer()
                LEAF -> GestureNode.Leaf.serializer()
                PROVIDER -> GestureNode.Provider.serializer()
                else -> return GestureNode.Unknown(obj)
            }
        // A known type whose body this build cannot read is kept verbatim too, so a newer build's
        // extra required field costs one inert slice instead of the whole menu.
        return runCatching { json.json.decodeFromJsonElement(serializer, obj.withoutType()) }
            .getOrElse { GestureNode.Unknown(obj) }
    }
}

internal fun <T> Json.typed(
    type: String,
    serializer: KSerializer<T>,
    value: T,
): JsonObject {
    val body = encodeToJsonElement(serializer, value) as JsonObject
    val head = linkedMapOf<String, JsonElement>(GESTURE_TYPE_KEY to JsonPrimitive(type))
    return JsonObject(head + body)
}

internal fun JsonObject.withoutType(): JsonObject = if (containsKey(GESTURE_TYPE_KEY)) JsonObject(this - GESTURE_TYPE_KEY) else this

internal fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun Encoder.asJsonEncoder(): JsonEncoder = this as? JsonEncoder ?: throw SerializationException("Gesture menus are JSON-only.")

internal fun Decoder.asJsonDecoder(): JsonDecoder = this as? JsonDecoder ?: throw SerializationException("Gesture menus are JSON-only.")

/**
 * Enum codec that degrades an unrecognised name to [fallback].
 *
 * Carried by the enum itself rather than left to `coerceInputValues`, so the fallback also holds
 * inside the configuration backup's own `Json`.
 */
abstract class FallbackEnumSerializer<T : Enum<T>>(
    serialName: String,
    private val entries: List<T>,
    private val fallback: T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeString()
        return entries.firstOrNull { it.name == raw } ?: fallback
    }
}

/** Provider kinds a newer build invented decode to [GestureProviderKind.UNKNOWN]. */
object GestureProviderKindSerializer : FallbackEnumSerializer<GestureProviderKind>(
    serialName = "io.github.trevarj.motd.gesture.GestureProviderKind",
    entries = GestureProviderKind.entries,
    fallback = GestureProviderKind.UNKNOWN,
)
