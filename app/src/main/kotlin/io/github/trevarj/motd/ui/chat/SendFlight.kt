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
 * The ghost's absolute top for one frame.
 *
 * While the landing row has not reported (and while the flight is still short of the hover line),
 * the lift holds the bubble one bubble height above the composer top: visible in-flight feedback
 * during however long persistence takes. The landing row's bottom edge is pinned to the foot of
 * the reversed list while its gap opens upward, so the hover line coincides with the slot the row
 * will occupy and the handoff from lift to flight cannot jump. min keeps whichever is higher.
 *
 * Once the landing exists, [landingTop] -- the top of its reported rect, which parent clipping
 * limits to the gap actually opened so far -- floors the result. The lift starts on the tap frame
 * but the gap can only open once persistence lands the row, so the hover otherwise outruns the
 * vacated space and the opaque ghost slides over the bottom of the neighbour above; over another
 * sender's bubble that reads as the send covering their text. The floor makes the ghost ride the
 * gap's edge instead, and it also bounds the flight spring's deliberate overshoot at the group
 * gap the row opened rather than letting the bounce poke into the neighbour.
 */
internal fun sendFlightGhostTop(
    startTop: Float,
    ghostHeight: Float,
    landingTop: Float?,
    landingBottom: Float?,
    flightFraction: Float,
    liftFraction: Float,
): Float {
    val hoverTop = startTop - ghostHeight * liftFraction
    val flightTop = landingBottom?.let { startTop + (it - ghostHeight - startTop) * flightFraction }
        ?: startTop
    val unclamped = min(hoverTop, flightTop)
    return if (landingTop == null) unclamped else max(unclamped, landingTop)
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
