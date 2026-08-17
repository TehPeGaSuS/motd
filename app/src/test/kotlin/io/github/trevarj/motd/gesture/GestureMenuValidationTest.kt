package io.github.trevarj.motd.gesture

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the editor is allowed to save: a ring a thumb can hit, no more than three rings deep. */
class GestureMenuValidationTest {

    private fun leaf(id: String, label: String = id) =
        GestureNode.Leaf(id = id, label = label, action = GestureAction.MarkAllRead)

    private fun root(vararg children: GestureNode) = GestureMenuConfig(
        root = GestureNode.Submenu(id = "root", label = "Menu", children = children.toList()),
    )

    @Test fun theShippedDefaultIsValidAndFillsTheRing() {
        assertEquals(emptyList<GestureMenuViolation>(), validateGestureMenu(GestureMenuConfig()))
        assertTrue(isValidGestureMenu(GestureMenuConfig()))
        assertEquals(MAX_RING_SLICES, DEFAULT_GESTURE_ROOT.children.size)
    }

    @Test fun aFullRingPassesAndOneMoreSliceDoesNot() {
        val full = root(*(1..MAX_RING_SLICES).map { leaf("n$it") }.toTypedArray())
        assertEquals(emptyList<GestureMenuViolation>(), validateGestureMenu(full))

        val overfull = full.addChild("root", leaf("one-too-many"))
        assertEquals(
            listOf(GestureMenuViolation.RingOverflow("root", MAX_RING_SLICES + 1)),
            validateGestureMenu(overfull),
        )
    }

    /** A provider is one slice in its parent; its own ring is the clamped limit, so it cannot overflow. */
    @Test fun providerLimitsAreClampedRatherThanReported() {
        val greedy = root(
            GestureNode.Provider(id = "p", label = "Pinned", kind = GestureProviderKind.PINNED_CHATS, limit = 99),
            GestureNode.Provider(id = "q", label = "Unread", kind = GestureProviderKind.UNREAD_CHATS, limit = 0),
        )

        assertEquals(emptyList<GestureMenuViolation>(), validateGestureMenu(greedy))
        assertEquals(MAX_RING_SLICES, (greedy.findNode("p") as GestureNode.Provider).clampedLimit)
        assertEquals(1, (greedy.findNode("q") as GestureNode.Provider).clampedLimit)
    }

    @Test fun ringsMayNestTwiceButNotThreeTimes() {
        val deep = root(
            GestureNode.Submenu(
                id = "ring2",
                label = "Ring 2",
                children = listOf(
                    GestureNode.Submenu(id = "ring3", label = "Ring 3", children = listOf(leaf("deep"))),
                ),
            ),
        )
        assertEquals(emptyList<GestureMenuViolation>(), validateGestureMenu(deep))

        val tooDeep = deep.addChild(
            "ring3",
            GestureNode.Submenu(id = "ring4", label = "Ring 4", children = listOf(leaf("deeper"))),
        )
        assertEquals(listOf(GestureMenuViolation.TooDeep("ring4", MAX_GESTURE_RINGS + 1)), validateGestureMenu(tooDeep))
    }

    /** Descending into a provider opens a ring too, so providers count against the depth limit. */
    @Test fun aProviderCountsAsARing() {
        val tooDeep = root(
            GestureNode.Submenu(
                id = "ring2",
                label = "Ring 2",
                children = listOf(
                    GestureNode.Submenu(
                        id = "ring3",
                        label = "Ring 3",
                        children = listOf(
                            GestureNode.Provider(id = "p", label = "Pinned", kind = GestureProviderKind.PINNED_CHATS),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(GestureMenuViolation.TooDeep("p", MAX_GESTURE_RINGS + 1)), validateGestureMenu(tooDeep))
    }

    @Test fun blankLabelsAreReportedForEveryNodeWeAuthored() {
        val blank = root(leaf("a", label = " "), GestureNode.Submenu(id = "s", label = "", children = emptyList()))

        assertEquals(
            listOf(GestureMenuViolation.BlankLabel("a"), GestureMenuViolation.BlankLabel("s")),
            validateGestureMenu(blank),
        )
    }

    /** An unknown node's label belongs to whichever build wrote it, so it is exempt. */
    @Test fun unknownNodesAreExemptFromTheLabelRule() {
        val withUnknown = root(
            GestureNode.Unknown(JsonObject(mapOf("type" to JsonPrimitive("hologram"), "id" to JsonPrimitive("future")))),
        )

        assertEquals(emptyList<GestureMenuViolation>(), validateGestureMenu(withUnknown))
    }

    @Test fun duplicateIdsAreReportedOncePerId() {
        val duplicated = root(leaf("a"), GestureNode.Submenu(id = "s", label = "S", children = listOf(leaf("a"))))

        assertEquals(listOf(GestureMenuViolation.DuplicateId("a")), validateGestureMenu(duplicated))
    }

    @Test fun violationsAccumulateInPreorder() {
        val messy = root(
            leaf("a", label = ""),
            GestureNode.Submenu(id = "s", label = "", children = listOf(leaf("b", label = ""))),
        )

        assertEquals(
            listOf(
                GestureMenuViolation.BlankLabel("a"),
                GestureMenuViolation.BlankLabel("s"),
                GestureMenuViolation.BlankLabel("b"),
            ),
            validateGestureMenu(messy),
        )
    }
}
