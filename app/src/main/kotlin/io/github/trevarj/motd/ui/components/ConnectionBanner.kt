package io.github.trevarj.motd.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieClipSpec
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.LottieDynamicProperties
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.model.KeyPath
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.lottieStrokeColor
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.delay

/** Avoid flashing a transient reconnect/connecting banner during short network handoffs. */
internal const val CONNECTION_BANNER_GRACE_MS = 3_000L

/**
 * Thin banner under the top bar summarizing connection health across all networks. Hidden when
 * every network is [IrcClientState.Ready] or the user dismisses the current status. Derives a
 * single line from the aggregate worst state.
 */
@Composable
fun ConnectionBanner(
    states: Map<Long, IrcClientState>,
    networkName: (Long) -> String?,
    modifier: Modifier = Modifier,
) {
    val status = bannerStatus(states, networkName)
    var transientGraceElapsed by remember { mutableStateOf(false) }
    var dismissedStatusKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(status?.transient) {
        transientGraceElapsed = false
        if (status?.transient == true) {
            // Connecting -> registering -> retrying is one unhealthy episode. Keying the timer by
            // transientness keeps those internal state changes from restarting the same grace.
            delay(CONNECTION_BANNER_GRACE_MS)
            transientGraceElapsed = true
        }
    }
    LaunchedEffect(status) {
        // Dismissal lasts for this exact state only. Once the connection recovers, a later
        // independent failure/reconnect remains visible instead of inheriting an old dismissal.
        if (status == null) dismissedStatusKey = null
    }
    val visibleStatus = visibleBannerStatus(status, dismissedStatusKey, transientGraceElapsed)
    // The glyph's phase is owned here, above AnimatedContent. A status TEXT change while still
    // connecting (a second network starting) produces a new content instance, and an animatable
    // living inside it would snap the arc back to 0 degrees in the middle of the crossfade.
    // `glyphSnapshot` mirrors the non-null status AnimatedContent keeps rendering through its exit,
    // which is the window the resolve beat has to land in.
    val glyphSnapshot = rememberLatestNonNull(visibleStatus)
    val allReady = states.isNotEmpty() && states.values.all { it is IrcClientState.Ready }
    val glyphProgress = rememberConnectionGlyphProgress(connectionBeat(glyphSnapshot, allReady))
    AnimatedContent(
        targetState = visibleStatus,
        transitionSpec = {
            val contentTransform = when {
                initialState == null ->
                    (fadeIn(MotdMotion.fadeIn) +
                        expandVertically(animationSpec = MotdMotion.contentSize)) togetherWith
                        ExitTransition.None
                targetState == null ->
                    EnterTransition.None togetherWith
                        (fadeOut(MotdMotion.fadeOut) +
                            shrinkVertically(animationSpec = MotdMotion.contentSize))
                else -> fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut)
            }
            // expand/shrink already own the null <-> content size change. Disable
            // AnimatedContent's default SizeTransform so the same height is not animated twice.
            contentTransform.using(null)
        },
        modifier = modifier,
        label = "connection_banner",
    ) { current ->
        // AnimatedContent retains this non-null snapshot while it runs the exit transition.
        if (current == null) return@AnimatedContent
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (current.error) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Whether this snapshot gets a glyph at all; the beat it would show is already being
            // driven above, so a text change under a running arc never restarts it.
            if (connectionBeat(current, allReady) != null) ConnectionStateGlyph(glyphProgress)
            Text(
                text = current.text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (current.error) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { dismissedStatusKey = current.dismissalKey },
                modifier = Modifier.size(32.dp).testTag("connection_banner_dismiss"),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The two named beats packed into [R.raw.connection_state]. */
internal enum class ConnectionBeat { CONNECTING, RESOLVE }

/**
 * Frame ranges of the one connection asset, at its 60fps timebase.
 *
 * The connecting beat is a seamless loop: frame [ConnectingLast] draws the arc back at its frame-0
 * angle, so it is played exclusive of its end. The resolve beat fades that arc out and draws the
 * check on, once.
 */
internal object ConnectionStateFrames {
    const val Total = 63
    const val ConnectingFirst = 0
    const val ConnectingLast = 54
    const val ResolveFirst = 54
    const val ResolveLast = 63

    /** Composition progress of each beat's settled frame, for the animator-scale-off snap. */
    val connectingProgress: Float = ConnectingFirst.toFloat() / Total
    val resolvedProgress: Float = (ResolveLast - 1).toFloat() / Total

    fun clipSpec(beat: ConnectionBeat?): LottieClipSpec = when (beat) {
        ConnectionBeat.RESOLVE -> LottieClipSpec.Frame(ResolveFirst, ResolveLast, maxInclusive = false)
        else -> LottieClipSpec.Frame(ConnectingFirst, ConnectingLast, maxInclusive = false)
    }

    fun settledProgress(beat: ConnectionBeat?): Float =
        if (beat == ConnectionBeat.RESOLVE) resolvedProgress else connectingProgress
}

/**
 * Which beat the banner's leading glyph shows.
 *
 * [snapshot] is what the banner is currently rendering, which during an exit transition is still
 * the last connecting status. [allReady] is explicit rather than inferred from a null status:
 * [bannerStatus] also returns null for a quiescent Disconnected network and for no networks at all,
 * and a manual disconnect mid-connect must not play the success beat. An error banner shows no
 * glyph, as before -- its text carries the failure.
 */
internal fun connectionBeat(snapshot: BannerStatus?, allReady: Boolean): ConnectionBeat? = when {
    snapshot == null || snapshot.error -> null
    allReady -> ConnectionBeat.RESOLVE
    else -> ConnectionBeat.CONNECTING
}

/** Holds the most recent non-null [value], mirroring what AnimatedContent keeps through its exit. */
@Composable
private fun rememberLatestNonNull(value: BannerStatus?): BannerStatus? {
    val holder = remember { mutableStateOf(value) }
    if (value != null) holder.value = value
    return holder.value
}

/**
 * Drives the connection asset for [beat] and returns its composition progress.
 *
 * Hoisted above the banner's AnimatedContent so the arc keeps its phase across content instances.
 * Only the beat, composition, and motion gate re-key the underlying animation, so a status text
 * change never restarts the spinner. Nothing is driven until a glyph has actually been shown.
 */
@Composable
private fun rememberConnectionGlyphProgress(beat: ConnectionBeat?): Float {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.connection_state),
    )
    val motionEnabled = LocalLottieMotionEnabled.current
    val progress by animateLottieCompositionAsState(
        composition = composition,
        isPlaying = motionEnabled && beat != null,
        iterations = if (beat == ConnectionBeat.CONNECTING) LottieConstants.IterateForever else 1,
        clipSpec = ConnectionStateFrames.clipSpec(beat),
    )
    return if (motionEnabled) progress else ConnectionStateFrames.settledProgress(beat)
}

/**
 * The banner's leading glyph: a looping arc spinner while connecting, resolving into a drawn-on
 * check once every network is Ready. One asset, two frame ranges, recolored from the active theme.
 */
@Composable
private fun ConnectionStateGlyph(progress: Float) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.connection_state),
    )
    val arcColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val checkColor = MaterialTheme.colorScheme.primary.toArgb()
    val dynamicProperties = remember(arcColor, checkColor) {
        // Built directly rather than through rememberLottieDynamicProperty, which keys on the
        // vararg keypath array's identity and so rebuilds (and re-resolves keypaths) every pass.
        // The arc keeps the banner's existing progress ink; the check earns the theme accent.
        LottieDynamicProperties(
            listOf(
                lottieStrokeColor(arcColor, KeyPath("arc", "**")),
                lottieStrokeColor(checkColor, KeyPath("check", "**")),
            ),
        )
    }
    LottieAnimation(
        composition = composition,
        progress = { progress },
        dynamicProperties = dynamicProperties,
        modifier = Modifier.size(14.dp),
    )
}

internal data class BannerStatus(
    val text: String,
    val error: Boolean,
    val transient: Boolean,
) {
    val dismissalKey: String
        get() = "$error:$transient:$text"
}

internal fun visibleBannerStatus(
    status: BannerStatus?,
    dismissedStatusKey: String?,
    transientGraceElapsed: Boolean,
): BannerStatus? = when {
    status == null || status.dismissalKey == dismissedStatusKey -> null
    !status.transient || transientGraceElapsed -> status
    else -> null
}

/** null when nothing to report (empty or all Ready). Prefers errors over in-progress states. */
internal fun bannerStatus(
    states: Map<Long, IrcClientState>,
    networkName: (Long) -> String?,
): BannerStatus? {
    if (states.isEmpty()) return null

    // Fatal failure wins the banner.
    states.entries.firstOrNull { (_, s) -> s is IrcClientState.Failed }?.let { (id, s) ->
        val failed = s as IrcClientState.Failed
        val name = networkName(id)
        val prefix = name?.let { "$it: " } ?: ""
        return if (failed.fatal) {
            BannerStatus("$prefix${failed.reason}", error = true, transient = false)
        } else {
            // Non-fatal: still surface the reason so a retry loop is diagnosable, not just "Offline".
            BannerStatus("${prefix}reconnecting — ${failed.reason}", error = true, transient = true)
        }
    }

    // Only active in-flight states get a progress banner. A plain Disconnected row is quiescent
    // (for example an old imported network or a manually disconnected account); showing it as
    // "Connecting…" makes a healthy bouncer child look stuck.
    val pending = states.entries.firstOrNull { (_, s) ->
        s is IrcClientState.Connecting || s is IrcClientState.Registering
    } ?: return null

    val name = networkName(pending.key)
    return BannerStatus(
        name?.let { "Connecting to $it…" } ?: "Connecting…",
        error = false,
        transient = true,
    )
}

@Preview
@Composable
private fun ConnectionBannerConnectingPreview() {
    MotdTheme {
        ConnectionBanner(
            states = mapOf(1L to IrcClientState.Connecting),
            networkName = { "Libera" },
        )
    }
}

@Preview
@Composable
private fun ConnectionBannerOfflinePreview() {
    MotdTheme {
        ConnectionBanner(
            states = mapOf(1L to IrcClientState.Failed("timeout", fatal = false)),
            networkName = { "Libera" },
        )
    }
}
