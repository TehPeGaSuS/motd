package io.github.trevarj.motd.spike

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Send-animation API spike, Phase 0/1: a bench for the two questions the approved spike asks about
 * replacing the app's hand-rolled send flight (`ui/chat/SendFlight.kt`). Debug source set only;
 * production code is untouched and this harness imports none of the chat composables, so nothing
 * it does can drift the shipped animation.
 *
 * The shipped flight has one hard constraint that ordinary shared-element demos never face: the
 * bubble takes off from the composer *before its landing row exists*. The row only appears once
 * Room has persisted the message, which the shipped code waits up to
 * `MotdMotion.SendFlightTargetTimeoutMs` (1200 ms) for, with a `snapshotFlow` wait plus a `snapTo`
 * fallback. Everything below exists to reproduce that gap on demand.
 *
 * ## Spike questions
 *
 * 1. Can `Modifier.sharedElementWithCallerManagedVisibility` animate a ghost that is already on
 *    screen into a lazy-list row that composes hundreds of milliseconds later -- and does the match
 *    keep retargeting while that row's bounds are still settling (keyboard/inset animation, list
 *    reflow)?
 * 2. Can `Modifier.animateItem` placement carry the list shift that the shipped code hand-rolls as
 *    the "runway" (`sendFlightListShift`, a `graphicsLayer` translation of the whole timeline)?
 *
 * ## Gate A criteria (evaluated by the maintainer on a physical device, not here)
 *
 * - **Match animates to a late lazy item.** With the insert delay at 800 ms and 1200 ms the ghost
 *   must fly into the row that appears afterwards, not cut to it or jump from the wrong origin.
 * - **Retargets live bounds.** Raising or lowering the keyboard, or letting the list settle, while
 *   the match is in flight must bend the trajectory toward the row's new bounds rather than land on
 *   a stale target. Use 5x/10x slow motion and drag the IME up mid-flight. Nothing in the harness
 *   cuts a flight short on a timer, so an observed snap is the API's, not the bench's -- see
 *   [SpikeFlightDriver].
 * - **Clip acceptable.** Two clips are in play and they are not the same clip. `LazyColumn`
 *   installs its own scroll clip internally (foundation 1.12.0 `clipScrollableContainer`, which
 *   clips to `Rect(-30dp, 0, width + 30dp, height)`), so a lazy item can never paint past the top
 *   or bottom of the list no matter what the caller does; `renderInOverlayDuringTransition = true`
 *   is the only way a shared element escapes it, which is what the `overlay` toggle switches off to
 *   demonstrate. The `clipToBounds` toggle therefore sits where production's sits -- on the wrapper
 *   Box AROUND the list (`ChatScreen.kt:2113-2120`), load-bearing only because the runway
 *   translates the entire list (`MessageList.kt:343`). Gate A passes only if the bubble can cross
 *   the composer/timeline boundary unsheared while the wrapper still contains the translated list.
 *
 * ## Viewport: the landing slot must stay on screen
 *
 * Neither Gate A criterion is judgeable unless the row the ghost is flying TO is inside the pane
 * for the whole flight. A match whose target is parked below the fold produces no observable
 * motion, and that reads as "the API did not animate" when in fact the bench gave it nowhere to
 * go. So the bench reproduces production's live edge rather than inventing a topology:
 *
 * - **Same shape as `MessageList.kt`.** `reverseLayout = true`, newest row at index 0, drawn at the
 *   foot of the pane directly above the composer divider. Resting at index 0 / offset 0 *is* the
 *   live edge, and an insert at the head therefore lands in a slot the maintainer is already
 *   looking at.
 * - **The seed overflows the pane** in every configuration the bench can be put in (controls
 *   expanded, IME up). A list shorter than its viewport is bottom-packed but has no live edge to
 *   pin, and it never reaches the top of the pane, so the runway would overflow nothing and the
 *   `clipToBounds` toggle would look inert.
 * - **The head insert is re-pinned.** `LazyListState` re-anchors the viewport by KEY: inserting at
 *   index 0 moves the key that owned the first visible slot to index 1 and the viewport follows it,
 *   parking the brand-new landing row just past the foot of the pane with only its top few pixels
 *   showing. (That was this bench's original defect, and `ChatListScrollPlacementTest` pins the
 *   same foundation behavior for the chat list.) Production corrects it with
 *   `listState.requestScrollToItem(0)` applied to the same remeasure that presents the insert
 *   (`ChatScreen.kt:1711`, `ChatListScreen.kt:886`); so does [SpikeTimeline], from the composition
 *   that presents the new head, plus once more on the tap so a flight launched after the maintainer
 *   scrolled up still has a visible target.
 *
 * The pin is deliberately not an `animateScrollToItem`. A scroll animation running alongside the
 * flight would drag the target bounds under the match and confound criterion 1 ("did it animate to
 * the late row") with criterion 2 ("did it retarget"). `requestScrollToItem` consumes no frames: it
 * is an anchor correction the next measure pass applies, indistinguishable in a recording from the
 * row simply appearing where it belongs. Unlike production the bench pins unconditionally instead
 * of gating on auto-follow, because following the live edge is the only state Gate A is asked
 * about.
 *
 * ## Controls
 *
 * - **Variant** -- `A shared element` (question 1) or `B animateItem` (question 2). Variant A wraps
 *   the surface in a [SharedTransitionLayout] and gives the ghost and the landing row the two ends
 *   of one caller-managed match; the list itself is deliberately un-animated so the shared element
 *   is the only thing moving. Variant B has no shared transition at all: the ghost simply fades at
 *   the handoff and every row carries `Modifier.animateItem`, so what is on trial is whether the
 *   older rows glide up acceptably on their own. Locked while a flight is airborne -- switching
 *   mid-flight swaps the whole subtree and would produce a bogus observation.
 * - **Insert delay** -- how long after the tap the "persisted" row is inserted (0/300/800/1200 ms),
 *   standing in for the Room round trip. Deliberately NOT scaled by slow motion: it models real
 *   persistence latency, which does not slow down when the animator does.
 * - **Slow-mo** -- 1x/5x/10x multiplier on every spec this harness owns (ghost lift, bounds
 *   transform, item placement, runway, handoff fade and margin). No adb animator-scale fiddling
 *   needed, and it can be changed mid-flight: the teardown reads it live.
 * - **clipToBounds / overlay** -- see Gate A above.
 * - **clear** -- empties the timeline (locked mid-flight, or the landing row would be re-inserted
 *   into a list the maintainer just emptied).
 * - **release** -- force teardown of a stuck flight. Only enabled while one is airborne; needed
 *   because the flight is otherwise held open for exactly as long as the shared transition runs,
 *   and a match that never resolves would otherwise strand the ghost.
 *
 * ## Non-goals (Phase 0/1)
 *
 * - **Concurrent flights.** Send is disabled while a flight is airborne, and only one shared key is
 *   ever live. Overlapping caller-managed matches -- one key and one visibility lifecycle per
 *   in-flight message, with N ghosts over the composer -- are explicitly out of scope for this
 *   phase; neither Gate A criterion depends on them.
 * - **Runway fidelity.** The harness runway is a single open-then-drain shift that exists so the
 *   `clipToBounds` toggle has something to clip. It does not model `sendFlightListShift`'s transfer
 *   math (predicted height, reveal absorption, composer-collapse foot drop).
 */
@Composable
fun SendSpikeHarness(modifier: Modifier = Modifier) {
    val state = remember { SpikeState() }
    // Variant B is composed outside the SharedTransitionLayout on purpose. That layout installs a
    // LookaheadScope over everything beneath it, which changes how the list measures; hosting the
    // runway probe under it would make B a measurement of A's scaffolding rather than a control.
    if (state.variant == SpikeVariant.SHARED_ELEMENT) {
        SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
            SpikeSurface(state = state, sharedScope = this)
        }
    } else {
        SpikeSurface(state = state, sharedScope = null, modifier = modifier)
    }
}

/** The two things on trial. */
enum class SpikeVariant(val label: String) {
    SHARED_ELEMENT("A shared element"),
    RUNWAY_PROBE("B animateItem"),
}

/** Insert-delay presets in milliseconds; 1200 is the shipped flight's give-up timeout. */
private val InsertDelayPresets = listOf(0, 300, 800, 1200)

/** Slow-motion multipliers applied to every spec the harness owns. */
private val SlowMotionPresets = listOf(1, 5, 10)

/** The ghost's rise above the composer while it waits for its row, mirroring the shipped lift. */
private const val GhostLiftMs = 300

/** The shared-element bounds transform: roughly the settle time of `MotdMotion.sendFlightSpring`. */
private const val SharedBoundsMs = 300

/** `Modifier.animateItem` placement tempo for variant B. */
private const val RowPlacementMs = 300

/** The list-level runway shift's open and drain tempo. */
private const val RunwayMs = 300

/** Variant B's ghost dissolve once the real row has landed. */
private const val GhostFadeMs = 160

/** Slack after the handoff settles before the ghost is disposed; scaled with everything else. */
private const val HandoffMarginMs = 150

/**
 * How long to wait for a caller-managed match to *start* before concluding it never will. Not
 * scaled: this is frame-detection, not animation, and a match that has not begun within a quarter
 * second of the visibility flip is a finding in itself.
 */
private const val MatchStartTimeoutMs = 250L

/** Where a bubble came from; `fromSelf` bubbles are the ones a flight can land in. */
private data class SpikeRow(val id: Long, val text: String, val fromSelf: Boolean)

/** One tap in flight. [rowId] is the lazy key, the shared-element key, and the flight's identity. */
private data class SpikeFlight(val text: String, val rowId: Long)

/** Shared-element key. A dedicated type keeps it from colliding with any other key in the tree. */
private data class SpikeGhostKey(val rowId: Long)

/**
 * Plain mutable holder for the newest row's id, so the live-edge re-pin in [SpikeTimeline] can
 * detect a head change during composition without a state backwards write. Mirrors
 * `ChatListTopItemTracker`.
 */
private class SpikeHeadTracker(var id: Long?)

/**
 * Everything the harness mutates, in one holder so the surface composables take three parameters
 * instead of fifteen. Mirrors the shape of `SendFlightAnchors`: geometry is reported in window
 * coordinates from `onGloballyPositioned`, because the composer and a timeline row share no local
 * space, and [hostOrigin] converts back.
 *
 * The list state lives here rather than in a `rememberLazyListState()` inside the timeline so a
 * variant switch -- which swaps the entire subtree between the two hosts -- keeps the maintainer's
 * scroll position. Rotation is handled by `configChanges` on the activity, so no saver is needed.
 */
@Stable
private class SpikeState {
    var variant by mutableStateOf(SpikeVariant.SHARED_ELEMENT)
    var insertDelayMs by mutableIntStateOf(800)
    var slowMotion by mutableIntStateOf(1)
    var clipTimeline by mutableStateOf(true)
    var renderInOverlay by mutableStateOf(true)

    var draft by mutableStateOf("")
    var rows by mutableStateOf(seedRows())
    var flight by mutableStateOf<SpikeFlight?>(null)

    /** Caller-managed visibility: true while the ghost owns the match, false once the row does. */
    var ghostVisible by mutableStateOf(false)

    /** True from the tap until the landing row is inserted; drives the list-level runway shift. */
    var runwayOpen by mutableStateOf(false)

    val listState = LazyListState()

    /** 0..1 fraction of [ghostHeight] the whole timeline is translated up by. */
    val runway = Animatable(0f)

    var hostOrigin by mutableStateOf(Offset.Zero)
    var composerField by mutableStateOf<Rect?>(null)
    var ghostHeight by mutableStateOf(0f)

    private var nextId = 1_000L

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || flight != null) return
        nextId += 1
        draft = ""
        ghostVisible = true
        runwayOpen = true
        // Take off from the live edge even if the maintainer scrolled up to inspect the clip: the
        // landing slot has to be on screen before the ghost has anywhere to fly to. A same-frame
        // anchor correction, never an animated scroll -- see the file KDoc.
        listState.requestScrollToItem(0)
        flight = SpikeFlight(text = text, rowId = nextId)
    }

    /** Manual escape hatch: tear the current flight down now, whatever the match is doing. */
    fun release() {
        ghostVisible = false
        runwayOpen = false
        flight = null
    }

    fun clear() {
        rows = emptyList()
    }

    /** Milliseconds a spec of [baseMs] runs for at the current slow-motion multiplier. */
    fun scaled(baseMs: Int): Int = baseMs * slowMotion
}

/**
 * The seeded timeline, newest first: index 0 is the newest row, and [SpikeTimeline]'s
 * `reverseLayout` draws it at the foot of the pane directly above the composer divider.
 *
 * Deliberately long enough to overflow the pane with the controls expanded and the IME up. Two
 * things depend on that overflow: the resting viewport is a genuine live edge (index 0 at offset 0
 * with content above it) rather than a short bottom-packed list, and the timeline reaches the top
 * of the pane so the runway's translation has something for `clipToBounds` to clip.
 */
private fun seedRows(): List<SpikeRow> = listOf(
    SpikeRow(18, "otherwise the runway would have nothing to overflow", false),
    SpikeRow(17, "the seed is long enough to overflow the pane, too", true),
    SpikeRow(16, "same correction production applies when a message lands", true),
    SpikeRow(15, "so the bench re-pins index 0 in the same remeasure", false),
    SpikeRow(14, "which is exactly what a head insert does to it", true),
    SpikeRow(13, "unless the viewport keeps following the old first key", false),
    SpikeRow(12, "right above the composer divider, in view", true),
    SpikeRow(11, "index 0, which reverseLayout draws at the foot", false),
    SpikeRow(10, "so where does a landing row actually end up", true),
    SpikeRow(9, "and that is why the runway exists", false),
    SpikeRow(8, "the row does not exist yet when the bubble takes off", true),
    SpikeRow(7, "wait, it flies into a row that has not been persisted?", false),
    SpikeRow(6, "up to 1200ms, per the snapshotFlow wait", true),
    SpikeRow(5, "how long can the gap get", false),
    SpikeRow(4, "long enough that the ghost has to hold its own", true),
    SpikeRow(3, "scroll me to check the clip", false),
    SpikeRow(2, "older rows glide when the list shifts", true),
    SpikeRow(1, "welcome to the send spike", false),
)

/**
 * The tap's lifecycle: hold the ghost for the configured delay, hand the match to the row, then
 * dispose the ghost once the handoff is genuinely over.
 *
 * The insert and the visibility flip are written back to back with no suspension between them, so
 * they land in one snapshot and therefore one composition. That matters: caller-managed visibility
 * expects exactly one visible end of a match at a time, and letting the row compose visible while
 * the ghost is still visible would be a frame of two-visible ambiguity.
 *
 * Teardown is NOT timed. Under variant A the flight is held open until
 * [SharedTransitionScope.isTransitionActive] goes false, because the transition's real duration is
 * not knowable up front: dragging the IME mid-flight retargets the match and extends it, and the
 * slow-motion multiplier can be changed after the insert. A precomputed timer would detach the
 * shared modifier while the bubble was still moving and snap it into place -- which would read as
 * "the API did not retarget" when in fact the bench cut it off. The only bounded wait is
 * [MatchStartTimeoutMs], for a match that never starts at all; `release` covers anything else.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SpikeFlightDriver(state: SpikeState, sharedScope: SharedTransitionScope?) {
    val flight = state.flight
    LaunchedEffect(flight?.rowId) {
        val current = flight ?: return@LaunchedEffect
        // Guarantee the ghost is composed and placed for at least one frame even at a 0 ms insert
        // delay, so the match has an origin to animate from rather than appearing already landed.
        withFrameNanos { }
        delay(state.insertDelayMs.toLong())
        state.rows = listOf(SpikeRow(current.rowId, current.text, fromSelf = true)) + state.rows
        state.ghostVisible = false
        state.runwayOpen = false
        if (sharedScope != null) {
            withTimeoutOrNull(MatchStartTimeoutMs) {
                snapshotFlow { sharedScope.isTransitionActive }.first { it }
            }
            snapshotFlow { sharedScope.isTransitionActive }.first { !it }
        } else {
            delay(state.scaled(GhostFadeMs).toLong())
        }
        delay(state.scaled(HandoffMarginMs).toLong())
        state.flight = null
    }
    // Opens on the tap and drains as the landing row is inserted, so the row's own arrival takes
    // over the space the shift vacated. Keyed on the flag alone; the spec is read live, so a
    // slow-motion change applies to the next leg rather than restarting this one.
    LaunchedEffect(state.runwayOpen) {
        state.runway.animateTo(
            targetValue = if (state.runwayOpen) 1f else 0f,
            animationSpec = tween(durationMillis = state.scaled(RunwayMs), easing = FastOutSlowInEasing),
        )
    }
}

/**
 * The harness surface: controls, timeline pane, composer, and the ghost overlay drawn above them.
 *
 * [sharedScope] is non-null only under variant A; every shared-element call below is guarded on it,
 * which is what keeps variant B an honest control. The driver lives here rather than above the
 * variant branch because it needs that scope to observe the transition; variant switching is locked
 * while a flight is airborne, so the driver is never swapped out from under one.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SpikeSurface(
    state: SpikeState,
    sharedScope: SharedTransitionScope?,
    modifier: Modifier = Modifier,
) {
    SpikeFlightDriver(state = state, sharedScope = sharedScope)
    Box(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .onGloballyPositioned { state.hostOrigin = it.positionInWindow() }
            .testTag("send_spike_surface"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SpikeControls(state = state)
            HorizontalDivider()
            // The wrapper Box carries the clip, matching production's topology exactly
            // (ChatScreen.kt:2113-2120). Putting it on the LazyColumn instead would be inert
            // vertically: the lazy container already clips its own scrollable area to its bounds,
            // so the only thing a caller-side clip can contain is the runway translation below.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (state.clipTimeline) Modifier.clipToBounds() else Modifier)
                    .testTag("send_spike_timeline_pane"),
            ) {
                SpikeTimeline(
                    state = state,
                    sharedScope = sharedScope,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            HorizontalDivider()
            SpikeComposer(state = state)
        }
        val flight = state.flight
        if (flight != null) {
            SpikeGhost(state = state, flight = flight, sharedScope = sharedScope)
        }
    }
}

/**
 * The reversed timeline, in `MessageList.kt`'s topology: `reverseLayout = true` with index 0 at the
 * bottom, so a send inserts at the head and every older row is displaced upward -- the displacement
 * variant B asks `Modifier.animateItem` to carry. The list rests at index 0 / offset 0, which under
 * that topology is the live edge: the newest row sits at the foot of the pane, so the slot a flight
 * lands in is already on screen. Keeping it there across the insert is the re-pin below; see the
 * file KDoc's viewport section for why that needs doing at all.
 *
 * The `graphicsLayer` translation is the harness's runway, in the same place production puts its
 * own (`MessageList.kt:343`): on the LazyColumn, so the whole timeline yields upward as a unit and
 * overflows the pane above. That overflow is the only thing the pane's `clipToBounds` toggle can
 * act on, which is why the runway exists here at all -- without it the toggle would look inert.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SpikeTimeline(
    state: SpikeState,
    sharedScope: SharedTransitionScope?,
    modifier: Modifier = Modifier,
) {
    val flightRowId = state.flight?.rowId
    // Read the presented list HERE, in the timeline's own composition, and let the item lambdas
    // capture that value: the re-pin below then runs in the same composition that hands the new
    // head to the lazy layout, so the measure pass that first presents the insert is the one that
    // honors it. (Pinning from the driver's coroutine instead is a frame too early at a 0 ms
    // insert delay -- the request is consumed by a measure that has not seen the new row yet, and
    // the key anchor wins the one that has.)
    val rows = state.rows
    // LazyColumn re-anchors to the first visible item's KEY across dataset changes, so a head
    // insert carries the viewport along with the OLD head and parks the brand-new landing row just
    // past the foot of the pane. Re-pin index 0 in the same remeasure that presents the insert --
    // the production wiring, in both places it appears (`ChatScreen.kt:1711` for a live message,
    // `ChatListScreen.kt:886` for a promoted conversation). Unconditional here, unlike production's
    // auto-follow gate: the live edge is the only viewport Gate A can be judged from.
    val headId = rows.firstOrNull()?.id
    val headTracker = remember { SpikeHeadTracker(headId) }
    if (headTracker.id != headId) {
        // Nothing to re-pin into or out of an empty timeline; `clear` must not fight the layout.
        val repin = headTracker.id != null && headId != null
        headTracker.id = headId
        if (repin) state.listState.requestScrollToItem(0)
    }
    val boundsTransform = rememberSpikeBoundsTransform(state.slowMotion)
    val placementMs = state.scaled(RowPlacementMs)
    val placementSpec: FiniteAnimationSpec<IntOffset> = remember(placementMs) {
        tween(durationMillis = placementMs, easing = FastOutSlowInEasing)
    }
    LazyColumn(
        state = state.listState,
        modifier = modifier
            .graphicsLayer { translationY = -state.ghostHeight * state.runway.value }
            .testTag("send_spike_timeline"),
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items = rows, key = { it.id }) { row ->
            if (sharedScope != null && row.id == flightRowId) {
                // Variant A's landing endpoint. It composes late -- that is the whole point -- and
                // is born already visible, taking the match over from the ghost in the same frame.
                val sharedState = with(sharedScope) {
                    rememberSharedContentState(key = SpikeGhostKey(row.id))
                }
                SpikeBubble(
                    text = row.text,
                    fromSelf = row.fromSelf,
                    modifier = with(sharedScope) {
                        Modifier.sharedElementWithCallerManagedVisibility(
                            sharedContentState = sharedState,
                            visible = true,
                            boundsTransform = boundsTransform,
                            renderInOverlayDuringTransition = state.renderInOverlay,
                        )
                    },
                )
            } else {
                SpikeBubble(
                    text = row.text,
                    fromSelf = row.fromSelf,
                    modifier = if (state.variant == SpikeVariant.RUNWAY_PROBE) {
                        Modifier.animateItem(
                            fadeInSpec = null,
                            placementSpec = placementSpec,
                            fadeOutSpec = null,
                        )
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/**
 * The bubble that materializes over the composer on the tap frame and waits there for its row.
 *
 * Placement is a layout-phase [Modifier.offset], not a `graphicsLayer` translation: the shared
 * element resolves bounds from layout coordinates, and a harness that moved the ghost in the draw
 * phase would hand the match a take-off point the ghost was never actually at.
 *
 * Only the composer field's TOP is used. Horizontally the ghost is a full-width row whose bubble
 * takes its timeline alignment (right, for a `fromSelf` bubble), so it parks over the send button
 * rather than over the field -- which is production's anchoring exactly (`SendFlight.kt:268-271`,
 * whose ghost is likewise `align(TopStart).fillMaxWidth()` around a right-aligned `MessageBubble`)
 * and is what makes the take-off and the landing share one horizontal axis.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun BoxScope.SpikeGhost(
    state: SpikeState,
    flight: SpikeFlight,
    sharedScope: SharedTransitionScope?,
) {
    val liftMs = state.scaled(GhostLiftMs)
    val lift = remember(flight.rowId) { Animatable(0f) }
    LaunchedEffect(flight.rowId) {
        lift.animateTo(1f, tween(durationMillis = liftMs, easing = LinearOutSlowInEasing))
    }
    val boundsTransform = rememberSpikeBoundsTransform(state.slowMotion)
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .offset {
                val fieldTop = state.composerField?.top ?: return@offset IntOffset.Zero
                val top = fieldTop - state.hostOrigin.y - state.ghostHeight * lift.value
                IntOffset(0, top.roundToInt())
            }
            .onSizeChanged { state.ghostHeight = it.height.toFloat() }
            .testTag("send_spike_ghost"),
    ) {
        val bubbleModifier = if (sharedScope != null) {
            val sharedState = with(sharedScope) {
                rememberSharedContentState(key = SpikeGhostKey(flight.rowId))
            }
            with(sharedScope) {
                Modifier.sharedElementWithCallerManagedVisibility(
                    sharedContentState = sharedState,
                    visible = state.ghostVisible,
                    boundsTransform = boundsTransform,
                    renderInOverlayDuringTransition = state.renderInOverlay,
                )
            }
        } else {
            // Variant B's handoff: nothing animates the ghost into the row, it just leaves. Kept
            // inside this branch so variant A never runs a stray animation for a value it ignores.
            val ghostAlpha by animateFloatAsState(
                targetValue = if (state.ghostVisible) 1f else 0f,
                animationSpec = tween(durationMillis = state.scaled(GhostFadeMs)),
                label = "spike_ghost_alpha",
            )
            Modifier.graphicsLayer { alpha = ghostAlpha }
        }
        SpikeBubble(text = flight.text, fromSelf = true, modifier = bubbleModifier)
    }
}

/**
 * The bounds transform the shared element animates on. Owned by the harness precisely so the
 * slow-motion multiplier has something to scale; the shipped flight uses a spring, but a bounded
 * tween is what makes 10x legible.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun rememberSpikeBoundsTransform(slowMotion: Int): BoundsTransform {
    val durationMs = SharedBoundsMs * slowMotion
    return remember(durationMs) {
        BoundsTransform { _, _ -> tween(durationMillis = durationMs, easing = FastOutSlowInEasing) }
    }
}

/**
 * A chat bubble, deliberately hand-rolled rather than reusing `MessageBubble`: the spike must not
 * be able to perturb the shipped renderer, and a plain colored bubble is enough geometry to judge
 * a bounds match by. [modifier] lands on the bubble box itself, not the full-width row, because the
 * bubble is the shared element's endpoint.
 */
@Composable
private fun SpikeBubble(text: String, fromSelf: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (fromSelf) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (fromSelf) colors.primaryContainer else colors.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (fromSelf) colors.onPrimaryContainer else colors.onSurfaceVariant,
            )
        }
    }
}

/** The fake composer. Its window rect is where every ghost takes off from. */
@Composable
private fun SpikeComposer(state: SpikeState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = { state.draft = it },
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned {
                    state.composerField = Rect(
                        offset = it.positionInWindow(),
                        size = Size(it.size.width.toFloat(), it.size.height.toFloat()),
                    )
                }
                .testTag("send_spike_field"),
            placeholder = { Text("Message") },
            maxLines = 4,
        )
        Button(
            onClick = state::send,
            enabled = state.draft.isNotBlank() && state.flight == null,
            modifier = Modifier.testTag("send_spike_send"),
        ) {
            Text("Send")
        }
    }
}

/** The bench controls. See the file KDoc for what each row is for. */
@Composable
private fun SpikeControls(state: SpikeState, modifier: Modifier = Modifier) {
    val idle = state.flight == null
    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SpikeChipRow(label = "Variant") {
            SpikeVariant.entries.forEach { variant ->
                FilterChip(
                    selected = state.variant == variant,
                    onClick = { state.variant = variant },
                    label = { Text(variant.label) },
                    // Swapping hosts mid-flight tears down the match under observation.
                    enabled = idle,
                )
            }
        }
        SpikeChipRow(label = "Insert") {
            InsertDelayPresets.forEach { delayMs ->
                FilterChip(
                    selected = state.insertDelayMs == delayMs,
                    onClick = { state.insertDelayMs = delayMs },
                    label = { Text("${delayMs}ms") },
                )
            }
        }
        SpikeChipRow(label = "Slow-mo") {
            SlowMotionPresets.forEach { multiplier ->
                FilterChip(
                    selected = state.slowMotion == multiplier,
                    onClick = { state.slowMotion = multiplier },
                    label = { Text("${multiplier}x") },
                )
            }
        }
        SpikeChipRow(label = "Layers") {
            FilterChip(
                selected = state.clipTimeline,
                onClick = { state.clipTimeline = !state.clipTimeline },
                label = { Text("clipToBounds") },
            )
            FilterChip(
                selected = state.renderInOverlay,
                onClick = { state.renderInOverlay = !state.renderInOverlay },
                label = { Text("overlay") },
            )
        }
        SpikeChipRow(label = "Bench") {
            AssistChip(
                onClick = state::clear,
                label = { Text("clear") },
                // Clearing mid-flight would only see the landing row re-inserted a moment later.
                enabled = idle,
            )
            AssistChip(
                onClick = state::release,
                label = { Text("release") },
                enabled = !idle,
            )
        }
    }
}

@Composable
private fun SpikeChipRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
        content()
    }
}
