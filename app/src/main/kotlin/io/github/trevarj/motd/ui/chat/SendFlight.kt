package io.github.trevarj.motd.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * Where a send flight starts and where it ends, in the coordinates of the chat surface that hosts
 * the overlay.
 *
 * Both rects are reported from `onGloballyPositioned`, which runs on every layout pass. They live
 * in snapshot state that only [SendFlightOverlay] reads, so a composer growing a line or a
 * timeline settling never recomposes the timeline itself.
 */
@Stable
internal class SendFlightAnchors {
    /**
     * Every rect is reported in window coordinates, because the composer and a timeline row have
     * no common local space. [hostOrigin] is the overlay's own window position, which turns them
     * back into the offsets the ghost is placed with.
     */
    var hostOrigin by mutableStateOf(Offset.Zero)
    var composerField by mutableStateOf<Rect?>(null)
    /** The landing row, keyed by event id so a report can be traced back to the row that made it. */
    var landingRow by mutableStateOf<Pair<Long, Rect>?>(null)

    fun reportLandingRow(eventId: Long, bounds: Rect) {
        landingRow = eventId to bounds
    }

    fun local(bounds: Rect): Rect = bounds.translate(-hostOrigin.x, -hostOrigin.y)
}

/**
 * One tap's motion, shared by the bubble in flight and the gap it is flying into.
 *
 * A single progress value drives both, which is the whole point: the conversation opens up
 * underneath the arriving bubble instead of jumping open first and leaving the bubble to chase a
 * hole that is already there. Both sides read it from deferred lambdas, so the spring invalidates
 * one layer and one row's layout per frame and never recomposes the timeline.
 */
@Stable
internal class SendFlightMotion {
    /** The flight proper: the bubble's travel into its slot and the gap opening beneath it. */
    val progress = Animatable(0f)

    /**
     * The immediate pre-landing rise, started on the tap frame before the pending row exists.
     * Without it the ghost waits at the composer top -- occluded by the input bar -- for as long
     * as the send takes to persist, so a slow send looked like a swallowed message. See
     * [sendFlightGhostTop] for how it blends with [progress].
     */
    val lift = Animatable(0f)
}

/**
 * How far the lift raises the ghost above the composer field's top while the row is persisting:
 * enough for the bubble's crown to peek over the input bar as in-flight feedback, small enough
 * that it stays inside the timeline's foot padding instead of covering the newest bubble. The
 * full-height hover this replaces put the ghost exactly where the neighbour above still was,
 * which read as the send covering another sender's text.
 */
internal val SendFlightHoverPeek = 24.dp

/**
 * The ghost's absolute top for one frame.
 *
 * While the landing row has not reported (and while the flight is still short of the hover line),
 * the lift holds the bubble a small peek ([peekHeight]) above the composer top: visible in-flight
 * feedback during however long persistence takes, without rising into the neighbour above. min
 * keeps whichever of hover and flight is higher, so the flight takes over the moment it passes
 * the peek line -- both terms are continuous, so the handoff cannot jump.
 *
 * The flight term is floored at [landingTop] -- the top of the landing's reported rect, which
 * parent clipping limits to the gap actually opened so far. The flight path starts below the
 * list foot and shares the gap's spring, so the floor is a safety net, not the driver: it only
 * bites when the spring's overshoot would poke the bubble past the gap into the neighbour, and
 * then by at most the overshoot itself. The hover is deliberately NOT floored: at the frame the
 * landing first reports, the gap is still zero-height, and flooring the already-lifted hover
 * there snapped the ghost down to the foot in one frame -- a visible jolt.
 */
internal fun sendFlightGhostTop(
    startTop: Float,
    ghostHeight: Float,
    peekHeight: Float,
    landingTop: Float?,
    landingBottom: Float?,
    flightFraction: Float,
    liftFraction: Float,
): Float {
    val hoverTop = startTop - min(ghostHeight, peekHeight) * liftFraction
    val flightTop = landingBottom?.let { startTop + (it - ghostHeight - startTop) * flightFraction }
        ?: return hoverTop
    val flooredFlight = landingTop?.let { max(flightTop, it) } ?: flightTop
    return min(hoverTop, flooredFlight)
}

/**
 * The bubble that rises from the composer into the timeline after a send.
 *
 * The ghost does not imitate the row it becomes -- it *is* that row, rendered by the same
 * [MessageBubble] the timeline uses. That renderer dispatches on layout density, resolves the
 * grouped-corner silhouette from [showSender], and builds the same linkified body, quoted reply,
 * and status/time line, so the replica cannot drift from its landing row by construction. A
 * hand-copied bubble had already drifted on three of those axes.
 *
 * Only `translationY` animates. Nothing scales, so the text is rasterised once and never distorts,
 * and the handoff to the real row is a swap between identical pixels.
 *
 * The ghost is invisible to the semantics tree ([clearAndSetSemantics], which clears the whole
 * subtree including the bubble's own click semantics). Its text duplicates a real row's, and a
 * second match would make every `onNodeWithText` assertion in chat ambiguous.
 */
@Composable
internal fun BoxScope.SendFlightOverlay(
    flight: OutgoingFlight?,
    anchors: SendFlightAnchors,
    motion: SendFlightMotion,
    selfNick: String,
    showSender: Boolean,
    networkId: Long?,
    knownNicks: Set<String>,
    identityRules: IrcIdentityRules,
) {
    // Read nothing while idle: an overlay that sampled the anchors unconditionally would recompose
    // on every composer layout pass for the whole life of the screen.
    if (flight == null) return
    val field = anchors.composerField?.let(anchors::local) ?: return
    // Pinned for the life of the tap. The composer empties on the same frame the flight launches,
    // so a live rect would shrink under the ghost and make it jump on its first step.
    val start = remember(flight.token) { field }
    // The row shows its Room timestamp; the ghost shows the clock for the moment it launched, built
    // with the timeline's own formatter so 12/24-hour and locale can never disagree. The launch
    // instant comes from the flight, which is also what row matching and grouping are decided by.
    val formatTime = rememberMessageTimeFormatter()
    val launchedAt = flight.launchedAtMs
    val time = remember(flight.token, formatTime) { formatTime(launchedAt) }

    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .graphicsLayer {
                // Re-read the landing every frame rather than snapshotting it: the row's rect
                // moves while the keyboard and the list settle, and a stale target lands crooked.
                val landing = anchors.landingRow?.second?.let(anchors::local)
                translationY = sendFlightGhostTop(
                    startTop = start.top,
                    ghostHeight = size.height,
                    peekHeight = SendFlightHoverPeek.toPx(),
                    landingTop = landing?.top,
                    landingBottom = landing?.bottom,
                    flightFraction = motion.progress.value,
                    liftFraction = motion.lift.value,
                )
            }
            .clearAndSetSemantics {},
    ) {
        MessageBubble(
            sender = selfNick,
            text = flight.text,
            timeMs = launchedAt,
            isSelf = true,
            kind = MessageKind.PRIVMSG,
            showSender = showSender,
            networkId = networkId,
            formattedTime = time,
            pending = true,
            reply = flight.replyText?.let { ReplyPreviewData(flight.replySender.orEmpty(), it) },
            knownNicks = knownNicks,
            identityRules = identityRules,
        )
    }
}
