package io.github.trevarj.motd.ui.settings.labs

import io.github.trevarj.motd.gesture.FakeBuffers
import io.github.trevarj.motd.gesture.FakeGesturePrefs
import io.github.trevarj.motd.gesture.FakeNetworks
import io.github.trevarj.motd.gesture.GestureAction
import io.github.trevarj.motd.gesture.GestureIcon
import io.github.trevarj.motd.gesture.GestureMenuConfig
import io.github.trevarj.motd.gesture.GestureMenuViolation
import io.github.trevarj.motd.gesture.GestureNode
import io.github.trevarj.motd.gesture.GestureProviderKind
import io.github.trevarj.motd.gesture.chatRow
import io.github.trevarj.motd.gesture.defaultGestureMenu
import io.github.trevarj.motd.gesture.findNode
import io.github.trevarj.motd.gesture.testNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The editor's working copy and its edits.
 *
 * Every case asserts through the ViewModel rather than the algebra it delegates to: the algebra has
 * its own tests, and what is worth pinning here is that the editor never bypasses it — a refused
 * edit stays refused, an invalid tree stays unsavable, and nothing reaches preferences until save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GestureMenuEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val prefs = FakeGesturePrefs()
    private val buffers = FakeBuffers()
    private val networks = FakeNetworks(listOf(testNetwork(1L, "libera"), testNetwork(2L, "oftc")))
    private val labels = GestureEditorLabels(submenu = "New submenu", leaf = "New action", provider = "New list")

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A model with a live collector, so `state.value` is always the settled working copy. */
    private fun TestScope.editor(menu: GestureMenuConfig = GestureMenuConfig()): GestureMenuEditorViewModel {
        prefs.menuState.value = menu
        val model = GestureMenuEditorViewModel(prefs, buffers, networks, labels)
        backgroundScope.launch { model.state.collect { } }
        advanceUntilIdle()
        return model
    }

    private fun TestScope.settled(model: GestureMenuEditorViewModel): GestureEditorUiState {
        advanceUntilIdle()
        return model.state.value
    }

    private fun TestScope.rowFor(model: GestureMenuEditorViewModel, nodeId: String): GestureEditorRow =
        settled(model).rows.first { it.node.id == nodeId }

    // -- loading and dirty tracking ---------------------------------------------------------------

    @Test fun loadsTheStoredTreeAsAnIndentedPreorderList() = runTest {
        val state = settled(editor())

        assertTrue(state.loaded)
        assertEquals("default-root", state.rows.first().node.id)
        assertEquals(0, state.rows.first().depth)
        assertEquals(8, state.rows.count { it.depth == 1 })
        assertEquals(
            listOf("default-search", "default-channel-info", "default-attach", "default-theme"),
            state.rows.filter { it.depth == 2 }.map { it.node.id },
        )
        assertFalse(state.dirty)
        assertFalse(state.canSave)
        assertTrue(state.isDefault)
    }

    @Test fun pickersComeFromTheRepositories() = runTest {
        buffers.chats.value = listOf(chatRow(7L, "#kotlin"), chatRow(8L, "#nix"))

        val state = settled(editor())

        assertEquals(listOf(7L, 8L), state.chats.map { it.bufferId })
        assertEquals(listOf("#kotlin", "#nix"), state.chats.map { it.label })
        assertEquals(listOf("libera", "oftc"), state.networks.map { it.name })
    }

    @Test fun anEditIsDirtyButNothingReachesPreferencesUntilSave() = runTest {
        val model = editor()

        model.rename("default-tools", "Utilities")
        val edited = settled(model)
        assertTrue(edited.dirty)
        assertTrue(edited.canSave)
        assertEquals(GestureMenuConfig(), prefs.menuState.value)

        model.save()
        val saved = settled(model)
        assertFalse(saved.dirty)
        assertFalse(saved.canSave)
        assertEquals("Utilities", prefs.menuState.value.findNode("default-tools")?.label)
    }

    // -- validation gating ------------------------------------------------------------------------

    @Test fun aBlankLabelBlocksSaveAndIsReportedOnItsOwnRow() = runTest {
        val model = editor()

        model.rename("default-away", "  ")
        val state = settled(model)

        assertTrue(state.dirty)
        assertFalse(state.canSave)
        assertEquals(
            listOf(GestureMenuViolation.BlankLabel("default-away")),
            state.rows.first { it.node.id == "default-away" }.violations,
        )

        model.save()
        advanceUntilIdle()
        assertEquals(GestureMenuConfig(), prefs.menuState.value)
    }

    @Test fun aNinthSliceOverflowsTheRingAndBlocksSave() = runTest {
        val model = editor()

        model.addChild("default-root", GestureNodeKind.LEAF)
        val state = settled(model)

        assertEquals(listOf(GestureMenuViolation.RingOverflow("default-root", 9)), state.violations)
        assertFalse(state.canSave)
    }

    @Test fun aFourthRingIsReportedTooDeep() = runTest {
        val model = editor(smallMenu())

        // root > outer > inner > new: the new submenu would open a fourth ring.
        model.addChild("inner", GestureNodeKind.SUBMENU)
        val state = settled(model)

        val deepest = state.rows.last().node.id
        assertEquals(3, state.rows.last().depth)
        assertEquals(listOf(GestureMenuViolation.TooDeep(deepest, 4)), state.violations)
        assertFalse(state.canSave)
    }

    // -- structural edits -------------------------------------------------------------------------

    @Test fun movesReorderWithinTheRingAndAreNotOfferedAtTheEnds() = runTest {
        val model = editor()

        val first = rowFor(model, "default-unread")
        assertFalse(first.canMoveUp)
        assertTrue(first.canMoveDown)
        assertFalse(rowFor(model, "default-networks").canMoveDown)

        model.moveDown("default-unread")
        val state = settled(model)

        assertEquals(
            listOf("default-pinned", "default-unread"),
            state.rows.filter { it.depth == 1 }.take(2).map { it.node.id },
        )
    }

    @Test fun indentPushesANodeIntoTheRingAboveItAndOutdentLiftsItBackOut() = runTest {
        val model = editor()
        assertTrue(rowFor(model, "default-networks").canIndent)

        model.indent("default-networks")
        val nested = settled(model)
        assertEquals(2, nested.rows.first { it.node.id == "default-networks" }.depth)
        assertEquals(
            listOf("default-search", "default-channel-info", "default-attach", "default-theme", "default-networks"),
            nested.rows.filter { it.depth == 2 }.map { it.node.id },
        )
        assertTrue(nested.rows.first { it.node.id == "default-networks" }.canOutdent)

        model.outdent("default-networks")
        val flat = settled(model)
        assertEquals(1, flat.rows.first { it.node.id == "default-networks" }.depth)
        assertEquals("default-networks", flat.rows.last { it.depth == 1 }.node.id)
        assertEquals(defaultGestureMenu(), flatConfigEquivalent(flat))
    }

    @Test fun indentIsRefusedWhenTheSliceAboveOpensNoRing() = runTest {
        val model = editor()
        // "Mark all read" follows "Next unread"; a leaf cannot take children.
        assertFalse(rowFor(model, "default-mark-all-read").canIndent)

        model.indent("default-mark-all-read")

        assertFalse(settled(model).dirty)
    }

    @Test fun outdentIsNotOfferedForTheRootsOwnChildren() = runTest {
        val model = editor()

        assertFalse(rowFor(model, "default-tools").canOutdent)

        model.outdent("default-tools")
        assertFalse(settled(model).dirty)
    }

    @Test fun deleteTakesTheWholeSubtree() = runTest {
        val model = editor()

        model.delete("default-tools")
        val state = settled(model)

        assertNull(state.rows.firstOrNull { it.node.id == "default-tools" })
        assertNull(state.rows.firstOrNull { it.node.id == "default-search" })
    }

    @Test fun theRootIsNeitherDeletableNorMovable() = runTest {
        val model = editor()
        val root = rowFor(model, "default-root")

        assertFalse(root.canDelete)
        assertFalse(root.canMoveUp)
        assertFalse(root.canMoveDown)
        assertFalse(root.canOutdent)
        assertTrue(root.canAddChild)

        model.delete("default-root")
        assertFalse(settled(model).dirty)
    }

    @Test fun addedNodesCarryUsableDefaults() = runTest {
        val model = editor(smallMenu())

        model.addChild("root", GestureNodeKind.LEAF)
        model.addChild("root", GestureNodeKind.PROVIDER)
        val state = settled(model)

        assertEquals(5, state.rows.size)
        val leaf = state.rows.map { it.node }.filterIsInstance<GestureNode.Leaf>().last()
        assertEquals("New action", leaf.label)
        assertEquals(GestureAction.OpenChatList, leaf.action)
        val provider = state.rows.map { it.node }.filterIsInstance<GestureNode.Provider>().last()
        assertEquals("New list", provider.label)
        assertEquals(GestureProviderKind.PINNED_CHATS, provider.kind)
        // A fresh node must never be born invalid.
        assertTrue(state.violations.isEmpty())
    }

    // -- bindings ---------------------------------------------------------------------------------

    @Test fun bindingRepointsALeafAndLeavesEverythingElseAlone() = runTest {
        val model = editor()

        model.bindAction("default-search", GestureAction.OpenChat(12L))
        val bound = settled(model)
        assertEquals(
            GestureAction.OpenChat(12L),
            (bound.rows.first { it.node.id == "default-search" }.node as GestureNode.Leaf).action,
        )

        model.bindAction("default-tools", GestureAction.MarkAllRead)
        assertTrue(settled(model).rows.first { it.node.id == "default-tools" }.node is GestureNode.Submenu)
    }

    @Test fun providerLimitIsClampedToWhatARingCanShow() = runTest {
        val model = editor()

        model.setProvider("default-unread", GestureProviderKind.FRIENDS, limit = 99)
        val state = settled(model)

        val provider = state.rows.first { it.node.id == "default-unread" }.node as GestureNode.Provider
        assertEquals(GestureProviderKind.FRIENDS, provider.kind)
        assertEquals(8, provider.limit)
        assertTrue(state.violations.isEmpty())
    }

    @Test fun iconsAreEditableOnEveryKnownNode() = runTest {
        val model = editor()

        model.setIcon("default-tools", GestureIcon.STAR)

        assertEquals(GestureIcon.STAR, rowFor(model, "default-tools").node.icon)
    }

    // -- forward compatibility --------------------------------------------------------------------

    @Test fun anUnknownNodeIsShownButNeverRewritten() = runTest {
        val model = editor(menuWithUnknown())
        val row = rowFor(model, "from-the-future")

        assertTrue(row.node is GestureNode.Unknown)
        assertFalse(row.canRename)
        assertTrue(row.canDelete)
        // An unknown node is exempt from the label rule, so it never blocks a save on its own.
        assertTrue(settled(model).violations.isEmpty())

        model.rename("from-the-future", "Renamed")
        model.setIcon("from-the-future", GestureIcon.STAR)
        assertFalse(settled(model).dirty)

        model.moveUp("from-the-future")
        assertTrue(settled(model).dirty)
        model.save()
        advanceUntilIdle()

        val stored = prefs.menuState.value.findNode("from-the-future")
        assertTrue(stored is GestureNode.Unknown)
        assertEquals(
            JsonPrimitive("Slice from a newer build"),
            (stored as GestureNode.Unknown).raw["label"],
        )
        assertEquals("from-the-future", prefs.menuState.value.root.children.first().id)
    }

    @Test fun anUnknownNodeRendersAsTheUnsupportedPlaceholder() = runTest {
        val model = editor(menuWithUnknown())

        // The screen keys the "Unsupported item (kept)" label off the node type, not its own text.
        assertTrue(rowFor(model, "from-the-future").node is GestureNode.Unknown)
        assertFalse(rowFor(model, "known").node is GestureNode.Unknown)
    }

    // -- reset ------------------------------------------------------------------------------------

    @Test fun resetGoesBackToTheStockTreeAndStillNeedsASave() = runTest {
        val model = editor(smallMenu())

        model.resetToDefault()
        val state = settled(model)

        assertTrue(state.isDefault)
        assertTrue(state.dirty)
        assertTrue(state.canSave)
        assertEquals(smallMenu(), prefs.menuState.value)

        model.save()
        advanceUntilIdle()
        assertFalse(model.state.value.dirty)
        assertEquals(defaultGestureMenu(), prefs.menuState.value)
    }

    // -- pure action-draft helpers ----------------------------------------------------------------

    @Test fun everyFamilyRoundTripsThroughItsDraft() {
        val actions = listOf(
            GestureAction.OpenChat(3L),
            GestureAction.OpenChatList,
            GestureAction.NextUnread,
            GestureAction.MarkAllRead,
            GestureAction.OpenSearch,
            GestureAction.ChannelInfoCurrent,
            GestureAction.AttachCurrent,
            GestureAction.InsertMention("trev"),
            GestureAction.InsertSnippet("brb"),
            GestureAction.StartQuery(2L, "trev"),
            GestureAction.JoinChannel(2L, "#motd", "hunter2"),
            GestureAction.ToggleAway("afk"),
            GestureAction.ToggleTheme,
            GestureAction.ReconnectNetwork(4L),
            GestureAction.DisconnectNetwork(4L),
        )

        actions.forEach { action ->
            assertEquals(action, buildGestureAction(gestureActionDraft(action)))
        }
        // Every family the sheet lists is reachable from some action, and vice versa.
        assertEquals(
            GestureActionFamily.entries.toSet(),
            actions.map { gestureActionDraft(it).family }.toSet(),
        )
    }

    @Test fun aDraftMissingARequiredParameterBuildsNothing() {
        assertNull(buildGestureAction(GestureActionDraft(GestureActionFamily.OPEN_CHAT)))
        assertNull(buildGestureAction(GestureActionDraft(GestureActionFamily.START_QUERY, networkId = 1L)))
        assertNull(buildGestureAction(GestureActionDraft(GestureActionFamily.START_QUERY, text = "trev")))
        assertNull(buildGestureAction(GestureActionDraft(GestureActionFamily.INSERT_MENTION, text = "   ")))
        assertNull(buildGestureAction(GestureActionDraft(GestureActionFamily.JOIN_CHANNEL, networkId = 1L)))
    }

    /** A blank away message is a choice, not a gap: it means "use the default". */
    @Test fun blankOptionalFieldsStillBuild() {
        assertEquals(
            GestureAction.ToggleAway(null),
            buildGestureAction(GestureActionDraft(GestureActionFamily.TOGGLE_AWAY, text = "  ")),
        )
        assertEquals(
            GestureAction.JoinChannel(1L, "#motd", null),
            buildGestureAction(
                GestureActionDraft(GestureActionFamily.JOIN_CHANNEL, networkId = 1L, text = "#motd"),
            ),
        )
    }

    @Test fun anUnknownActionFallsBackToADefaultDraft() {
        val draft = gestureActionDraft(GestureAction.Unknown(JsonObject(emptyMap())))

        assertEquals(GestureActionDraft(), draft)
        assertNotNull(buildGestureAction(draft))
    }

    // -- fixtures ---------------------------------------------------------------------------------

    /** Rebuild a config from the flattened rows, to prove an indent/outdent pair is a true no-op. */
    private fun flatConfigEquivalent(state: GestureEditorUiState): GestureMenuConfig =
        GestureMenuConfig(root = state.rows.first().node as GestureNode.Submenu)

    private fun smallMenu(): GestureMenuConfig = GestureMenuConfig(
        root = GestureNode.Submenu(
            id = "root",
            label = "Menu",
            children = listOf(
                GestureNode.Submenu(
                    id = "outer",
                    label = "Outer",
                    children = listOf(GestureNode.Submenu(id = "inner", label = "Inner")),
                ),
            ),
        ),
    )

    private fun menuWithUnknown(): GestureMenuConfig = GestureMenuConfig(
        root = GestureNode.Submenu(
            id = "root",
            label = "Menu",
            children = listOf(
                GestureNode.Leaf(id = "known", label = "Search", action = GestureAction.OpenSearch),
                GestureNode.Unknown(
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("hologram"),
                            "id" to JsonPrimitive("from-the-future"),
                            "label" to JsonPrimitive("Slice from a newer build"),
                        ),
                    ),
                ),
            ),
        ),
    )
}
