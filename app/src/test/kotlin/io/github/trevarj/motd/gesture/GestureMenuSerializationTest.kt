package io.github.trevarj.motd.gesture

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gesture menu is persisted user data that both older and newer builds write, so these tests
 * pin the format itself: canonical text, and — the part that actually matters — that a node or
 * action this build does not understand comes back out byte for byte after being read, edited, and
 * written again.
 */
class GestureMenuSerializationTest {

    /**
     * A document from a hypothetical newer build: one node kind and one action kind this build has
     * never heard of, alongside nodes it knows. Written in the exact canonical form our encoder
     * emits, so it can be compared as text.
     */
    private val futureDocument = """
        {"version":1,"root":{"id":"root","label":"Menu","icon":"MENU","children":[
        {"type":"leaf","id":"search","label":"Search","icon":"SEARCH","action":{"type":"openSearch"}},
        {"type":"hologram","id":"future","label":"Hologram","icon":"SPARKLE","spin":3,"tags":["a","b"],
        "children":[{"type":"leaf","id":"buried","label":"Buried","icon":"BOLT","action":{"type":"openSearch"}}]},
        {"type":"leaf","id":"teleport","label":"Teleport","icon":"BOLT","action":{"type":"teleport","target":"tomorrow","warp":true}},
        {"type":"provider","id":"pinned","label":"Pinned","icon":"PIN","kind":"PINNED_CHATS","limit":6}]}}
    """.trimIndent().replace("\n", "")

    @Test fun defaultMenuRoundTripsThroughText() {
        val decoded = decodeGestureMenu(encodeGestureMenu(GestureMenuConfig()))
        assertEquals(GestureMenuConfig(), decoded)
    }

    @Test fun everyActionKindRoundTrips() {
        val actions = listOf(
            GestureAction.OpenChat(42L),
            GestureAction.OpenSearch,
            GestureAction.ChannelInfoCurrent,
            GestureAction.NextUnread,
            GestureAction.OpenChatList,
            GestureAction.InsertMention("trev"),
            GestureAction.InsertSnippet("brb"),
            GestureAction.StartQuery(7L, "trev"),
            GestureAction.ToggleAway("lunch"),
            GestureAction.ToggleAway(),
            GestureAction.ReconnectNetwork(3L),
            GestureAction.DisconnectNetwork(3L),
            GestureAction.JoinChannel(3L, "#motd", "hunter2"),
            GestureAction.JoinChannel(3L, "#motd"),
            GestureAction.MarkAllRead,
            GestureAction.ToggleTheme,
            GestureAction.AttachCurrent,
        )
        val config = GestureMenuConfig(
            root = GestureNode.Submenu(
                id = "root",
                label = "Menu",
                children = actions.mapIndexed { index, action ->
                    GestureNode.Leaf(id = "leaf-$index", label = "Leaf $index", action = action)
                },
            ),
        )

        assertEquals(config, decodeGestureMenu(encodeGestureMenu(config)))
    }

    /** The whole point: an unknown node and an unknown action re-encode exactly as they arrived. */
    @Test fun unknownNodesAndActionsReEncodeVerbatim() {
        val decoded = decodeGestureMenu(futureDocument)

        assertEquals(futureDocument, encodeGestureMenu(decoded))
    }

    @Test fun unknownNodeKeepsItsRawObjectAndStaysIdentifiable() {
        val decoded = decodeGestureMenu(futureDocument)
        val unknown = decoded.root.children.filterIsInstance<GestureNode.Unknown>().single()

        assertEquals("future", unknown.id)
        assertEquals("Hologram", unknown.label)
        assertEquals(GestureIcon.UNKNOWN, unknown.icon)
        assertEquals("hologram", unknown.raw.stringOrNull("type"))
        assertTrue(unknown.raw.containsKey("spin"))
        assertTrue(unknown.raw.containsKey("tags"))
        // Children of an unknown node are not interpreted either: they ride along inside the raw.
        assertTrue(unknown.raw["children"].toString().contains("buried"))
    }

    @Test fun unknownActionKeepsItsRawObject() {
        val decoded = decodeGestureMenu(futureDocument)
        val leaf = decoded.root.children.filterIsInstance<GestureNode.Leaf>().single { it.id == "teleport" }
        val action = leaf.action as GestureAction.Unknown

        assertEquals("tomorrow", (action.raw["target"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    /** An older build editing a newer build's menu must not amputate what it cannot render. */
    @Test fun editingAroundAnUnknownNodeLeavesItUntouched() {
        val decoded = decodeGestureMenu(futureDocument)
        val unknown = decoded.root.children.filterIsInstance<GestureNode.Unknown>().single()
        val edited = decoded
            .updateNode("search") { (it as GestureNode.Leaf).copy(label = "Find") }
            .addChild("root", GestureNode.Leaf(id = "added", label = "Added", action = GestureAction.MarkAllRead))

        val reEncoded = encodeGestureMenu(edited)

        assertTrue(reEncoded.contains(""""label":"Find""""))
        assertTrue(reEncoded.contains(unknown.raw.toString()))
        assertEquals(
            listOf(unknown),
            decodeGestureMenu(reEncoded).root.children.filterIsInstance<GestureNode.Unknown>(),
        )
    }

    /** A named icon or provider kind that no longer exists degrades; it never fails the decode. */
    @Test fun unknownEnumNamesDegradeToUnknown() {
        val raw = """
            {"version":1,"root":{"id":"root","label":"Menu","icon":"HOLOGRAM","children":[
            {"type":"provider","id":"p","label":"Ghosts","icon":"NOPE","kind":"SEANCE","limit":4}]}}
        """.trimIndent().replace("\n", "")

        val decoded = decodeGestureMenu(raw)
        val provider = decoded.root.children.single() as GestureNode.Provider

        assertEquals(GestureIcon.UNKNOWN, decoded.root.icon)
        assertEquals(GestureIcon.UNKNOWN, provider.icon)
        assertEquals(GestureProviderKind.UNKNOWN, provider.kind)
        assertEquals(4, provider.limit)
    }

    /** A node whose known type this build cannot parse is kept whole rather than dropped. */
    @Test fun unparseableKnownNodeIsKeptAsUnknown() {
        val raw = """
            {"version":1,"root":{"id":"root","label":"Menu","icon":"MENU","children":[
            {"type":"leaf","id":"broken","label":"Broken","icon":"BOLT","action":42}]}}
        """.trimIndent().replace("\n", "")

        val decoded = decodeGestureMenu(raw)
        val kept = decoded.root.children.single() as GestureNode.Unknown

        assertEquals("broken", kept.id)
        assertEquals(raw, encodeGestureMenu(decoded))
    }

    @Test fun garbageAndEmptyStoragesFallBackToTheDefault() {
        assertEquals(GestureMenuConfig(), decodeGestureMenu(null))
        assertEquals(GestureMenuConfig(), decodeGestureMenu(""))
        assertEquals(GestureMenuConfig(), decodeGestureMenu("not json at all"))
        assertEquals(GestureMenuConfig(), decodeGestureMenu("""{"version":1,"root":[]}"""))
    }

    @Test fun aNodeThatIsNotEvenAnObjectDoesNotBreakTheRing() {
        val raw = """{"version":1,"root":{"id":"root","label":"Menu","icon":"MENU","children":["nope"]}}"""

        val decoded = decodeGestureMenu(raw)

        assertEquals(listOf(GestureNode.Unknown(JsonObject(emptyMap()))), decoded.root.children)
    }
}
