package io.github.trevarj.motd.ui.components

import androidx.compose.animation.core.TweenSpec
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.theme.MotdMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionBannerTest {

    @Test
    fun connectingStatusIsTransientAndUsesGraceWindow() {
        val status = bannerStatus(mapOf(1L to IrcClientState.Connecting)) { "Libera" }

        assertEquals("Connecting to Libera…", status?.text)
        assertTrue(status?.transient == true)
        assertEquals(3_000L, CONNECTION_BANNER_GRACE_MS)
    }

    @Test
    fun fatalFailureIsImmediateWhileRetryFailureIsTransient() {
        val fatal = bannerStatus(mapOf(1L to IrcClientState.Failed("bad cert", fatal = true))) { "Libera" }
        val retry = bannerStatus(mapOf(1L to IrcClientState.Failed("timeout", fatal = false))) { "Libera" }

        assertFalse(fatal?.transient == true)
        assertTrue(retry?.transient == true)
    }

    @Test
    fun readyStateClearsBannerAfterFailureOrConnecting() {
        assertNull(
            bannerStatus(
                mapOf(1L to IrcClientState.Ready("neo", emptySet(), emptyMap())),
            ) { "Libera" },
        )
    }

    @Test
    fun dismissedStatusStaysHiddenUntilConnectionStatusChanges() {
        val accountRequired = bannerStatus(
            mapOf(1L to IrcClientState.Failed("ACCOUNT_REQUIRED", fatal = true)),
        ) { "Libera" }
        val reconnecting = bannerStatus(
            mapOf(1L to IrcClientState.Connecting),
        ) { "Libera" }

        assertNull(
            visibleBannerStatus(
                accountRequired,
                accountRequired?.dismissalKey,
                transientGraceElapsed = true,
            ),
        )
        assertEquals(
            reconnecting,
            visibleBannerStatus(reconnecting, accountRequired?.dismissalKey, transientGraceElapsed = true),
        )
    }

    @Test
    fun transientStatusWaitsForGraceBeforeAppearing() {
        val connecting = bannerStatus(mapOf(1L to IrcClientState.Connecting)) { "Libera" }

        assertNull(visibleBannerStatus(connecting, dismissedStatusKey = null, transientGraceElapsed = false))
        assertEquals(
            connecting,
            visibleBannerStatus(connecting, dismissedStatusKey = null, transientGraceElapsed = true),
        )
    }

    @Test
    fun connectingSnapshotLoopsWhileAnythingIsStillPending() {
        val connecting = bannerStatus(mapOf(1L to IrcClientState.Connecting)) { "Libera" }

        assertEquals(ConnectionBeat.CONNECTING, connectionBeat(connecting, allReady = false))
    }

    @Test
    fun connectingSnapshotResolvesOnceEveryNetworkIsReady() {
        // The snapshot the banner is still rendering during its exit is what earns the check.
        val connecting = bannerStatus(mapOf(1L to IrcClientState.Connecting)) { "Libera" }

        assertEquals(ConnectionBeat.RESOLVE, connectionBeat(connecting, allReady = true))
    }

    @Test
    fun manualDisconnectMidConnectDoesNotClaimSuccess() {
        // bannerStatus is null for a quiescent Disconnected network too, so readiness has to be
        // asked for explicitly: aborting a connect must keep spinning, never draw the check.
        val states: Map<Long, IrcClientState> = mapOf(1L to IrcClientState.Disconnected)
        val connecting = bannerStatus(mapOf(1L to IrcClientState.Connecting)) { "Libera" }
        val allReady = states.isNotEmpty() && states.values.all { it is IrcClientState.Ready }

        assertNull(bannerStatus(states) { "Libera" })
        assertFalse(allReady)
        assertEquals(ConnectionBeat.CONNECTING, connectionBeat(connecting, allReady))
    }

    @Test
    fun errorAndAbsentBannersRenderNoGlyph() {
        val fatal = bannerStatus(mapOf(1L to IrcClientState.Failed("bad cert", fatal = true))) { "Libera" }
        val retry = bannerStatus(mapOf(1L to IrcClientState.Failed("timeout", fatal = false))) { "Libera" }

        assertNull(connectionBeat(fatal, allReady = false))
        assertNull(connectionBeat(retry, allReady = false))
        assertNull(connectionBeat(snapshot = null, allReady = true))
    }

    @Test
    fun beatFrameRangesHandOffWithoutGapOrOverlap() {
        with(ConnectionStateFrames) {
            assertEquals(0, ConnectingFirst)
            // The loop's exclusive end is the resolve's first frame: one asset, two named beats.
            assertEquals(ResolveFirst, ConnectingLast)
            assertEquals(Total, ResolveLast)
            assertTrue(ConnectingLast > ConnectingFirst)
            assertTrue(ResolveLast > ResolveFirst)
            // Animator-scale-off snaps: the arc parked at rest, the check fully drawn.
            assertEquals(0f, connectingProgress, 0f)
            assertEquals((Total - 1f) / Total, resolvedProgress, 0f)
        }
    }

    @Test
    fun resolveBeatFitsInsideTheBannerExitFade() {
        // The banner is fully faded MotdMotion.fadeOut after the status clears, so a resolve that
        // outlasts that window would never be seen. Keep the beat inside it, with a frame to spare.
        val exitFadeFrames = (MotdMotion.fadeOut as TweenSpec<*>).durationMillis * 60 / 1_000

        with(ConnectionStateFrames) {
            // ResolveLast is the exclusive clip end, so the last frame actually drawn is one below.
            assertTrue((ResolveLast - 1) - ResolveFirst <= exitFadeFrames)
        }
    }
}
