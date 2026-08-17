package io.github.trevarj.motd.gesture

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tree algebra: every edit is a value in, a value out, and a refused edit changes nothing. */
class GestureMenuEditTest {

    private fun leaf(id: String, label: String = id) =
        GestureNode.Leaf(id = id, label = label, action = GestureAction.MarkAllRead)

    private val config = GestureMenuConfig(
        root = GestureNode.Submenu(
            id = "root",
            label = "Menu",
            children = listOf(
                leaf("a"),
                GestureNode.Submenu(id = "tools", label = "Tools", children = listOf(leaf("b"), leaf("c"))),
                GestureNode.Provider(id = "pinned", label = "Pinned", kind = GestureProviderKind.PINNED_CHATS),
            ),
        ),
    )

    private fun childIds(config: GestureMenuConfig, parentId: String): List<String> =
        (config.findNode(parentId) as GestureNode.Submenu).children.map { it.id }

    @Test fun findAndParentWalkTheWholeTree() {
        assertEquals("b", config.findNode("b")?.id)
        assertEquals("tools", config.parentIdOf("b"))
        assertEquals("root", config.parentIdOf("tools"))
        assertNull(config.parentIdOf("root"))
        assertNull(config.findNode("nope"))
        assertEquals(listOf("root", "a", "tools", "b", "c", "pinned"), config.allNodes().map { it.id })
    }

    @Test fun addChildAppendsOrInserts() {
        assertEquals(listOf("a", "tools", "pinned", "new"), childIds(config.addChild("root", leaf("new")), "root"))
        assertEquals(
            listOf("a", "new", "tools", "pinned"),
            childIds(config.addChild("root", leaf("new"), index = 1), "root"),
        )
        assertEquals(listOf("b", "c", "new"), childIds(config.addChild("tools", leaf("new")), "tools"))
    }

    @Test fun addChildRefusesDuplicateIdsAndNonSubmenuParents() {
        assertSame(config, config.addChild("root", leaf("b")))
        assertSame(config, config.addChild("a", leaf("new")))
        assertSame(config, config.addChild("pinned", leaf("new")))
        assertSame(config, config.addChild("missing", leaf("new")))
    }

    @Test fun removeNodeDropsTheWholeSubtreeButNeverTheRoot() {
        val without = config.removeNode("tools")
        assertEquals(listOf("a", "pinned"), childIds(without, "root"))
        assertNull(without.findNode("b"))

        assertSame(config, config.removeNode("root"))
        assertSame(config, config.removeNode("missing"))
    }

    @Test fun moveAmongSiblingsStaysInsideItsOwnRing() {
        assertEquals(listOf("tools", "a", "pinned"), childIds(config.moveAmongSiblings("a", 1), "root"))
        assertEquals(listOf("a", "pinned", "tools"), childIds(config.moveAmongSiblings("pinned", -1), "root"))
        assertEquals(listOf("c", "b"), childIds(config.moveAmongSiblings("b", 1), "tools"))
    }

    @Test fun moveAmongSiblingsRefusesNoOpsAndBothEnds() {
        assertSame(config, config.moveAmongSiblings("a", 0))
        assertSame(config, config.moveAmongSiblings("a", -1))
        assertSame(config, config.moveAmongSiblings("pinned", 1))
        assertSame(config, config.moveAmongSiblings("root", 1))
        assertSame(config, config.moveAmongSiblings("missing", 1))
    }

    @Test fun reparentMovesTheSubtree() {
        val moved = config.reparent("a", "tools")
        assertEquals(listOf("tools", "pinned"), childIds(moved, "root"))
        assertEquals(listOf("b", "c", "a"), childIds(moved, "tools"))

        val promoted = config.reparent("b", "root", index = 0)
        assertEquals(listOf("b", "a", "tools", "pinned"), childIds(promoted, "root"))
        assertEquals(listOf("c"), childIds(promoted, "tools"))
    }

    @Test fun reparentRefusesCyclesAndImpossibleTargets() {
        assertSame(config, config.reparent("tools", "b"))
        assertSame(config, config.reparent("tools", "tools"))
        assertSame(config, config.reparent("root", "tools"))
        assertSame(config, config.reparent("a", "pinned"))
        assertSame(config, config.reparent("a", "missing"))
    }

    @Test fun reparentIntoTheSameParentRepositions() {
        assertEquals(listOf("tools", "pinned", "a"), childIds(config.reparent("a", "root"), "root"))
    }

    @Test fun updateNodeRewritesInPlaceAndGuardsIdCollisions() {
        val renamed = config.updateNode("a") { (it as GestureNode.Leaf).copy(label = "Alpha") }
        assertEquals("Alpha", renamed.findNode("a")?.label)

        val reIded = config.updateNode("a") { (it as GestureNode.Leaf).copy(id = "z") }
        assertEquals(listOf("z", "tools", "pinned"), childIds(reIded, "root"))

        assertSame(config, config.updateNode("a") { (it as GestureNode.Leaf).copy(id = "b") })
        assertSame(config, config.updateNode("missing") { it })
    }

    @Test fun updateNodeCanEditTheRootButNotDemoteIt() {
        val renamed = config.updateNode("root") { (it as GestureNode.Submenu).copy(label = "Ring") }
        assertEquals("Ring", renamed.root.label)
        assertEquals(listOf("a", "tools", "pinned"), childIds(renamed, "root"))

        assertEquals(config.root, config.updateNode("root") { leaf("root") }.root)
    }

    @Test fun bindActionOnlyTouchesLeaves() {
        val bound = config.bindAction("a", GestureAction.OpenChat(9L))
        assertEquals(GestureAction.OpenChat(9L), (bound.findNode("a") as GestureNode.Leaf).action)

        assertEquals(config.root, config.bindAction("tools", GestureAction.OpenChat(9L)).root)
        assertSame(config, config.bindAction("missing", GestureAction.OpenChat(9L)))
    }

    /** Unknown nodes are ordinary tree citizens for structural edits, and stay opaque. */
    @Test fun unknownNodesMoveAndDeleteLikeAnythingElse() {
        val unknown = GestureNode.Unknown(
            JsonObject(mapOf("type" to JsonPrimitive("hologram"), "id" to JsonPrimitive("future"))),
        )
        val withUnknown = config.addChild("root", unknown, index = 0)

        assertEquals(listOf("future", "a", "tools", "pinned"), childIds(withUnknown, "root"))
        assertEquals(
            listOf("a", "future", "tools", "pinned"),
            childIds(withUnknown.moveAmongSiblings("future", 1), "root"),
        )
        assertTrue(withUnknown.removeNode("future").findNode("future") == null)
        assertSame(withUnknown, withUnknown.addChild("future", leaf("nested")))
    }
}
