package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CHATHISTORY half of one Ready session: the entry decision that releases the user-facing gate
 * ([decideHistoryCatchUp]), the capability settle it waits on ([awaitHistoryCapDecision]), and the
 * CAP NEW stand-in that covers a decision which declined ([rearmHistoryCatchUp]).
 *
 * These three are extracted from `ConnectionManagerImpl.onReadySession` precisely because every
 * defect they encode is an ORDERING or BOUNDEDNESS defect — nothing a connection-level fixture can
 * observe. Same seam-injected style as the pure decisions in [ConnectionActorTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryCatchUpSessionTest {
    private val ready = HistoryAvailability.Ready(setOf(HistoryReferenceType.TIMESTAMP), 100)

    @Test
    fun anUnansweredCapReqSettlesNegativeInsteadOfStrandingTheEntryGate() = runTest {
        // The server deferred `CAP REQ :draft/chathistory` and then answered nothing. pendingFeatureCaps
        // only sheds a cap on ACK/NAK/DEL, so the settle-aware wait alone never returns here, and
        // `historyCatchUpPending` stays armed for the whole Ready session — C5's defect by a second
        // route. The outer budget is the assertion: an unbounded wait fails the test rather than
        // hanging it.
        val pending = MutableStateFlow(setOf(ConnectionManagerImpl.CHATHISTORY_CAP))
        val decided = withTimeout(120_000) {
            awaitHistoryCapDecision({ HistoryAvailability.NegotiatingOrOffline }, pending)
        }
        assertFalse(decided)
    }

    @Test
    fun aLateCapAckStillSettlesTheDecisionPositively() = runTest {
        val pending = MutableStateFlow(setOf(ConnectionManagerImpl.CHATHISTORY_CAP))
        var availability: HistoryAvailability = HistoryAvailability.NegotiatingOrOffline
        val decision = async { awaitHistoryCapDecision({ availability }, pending) }
        runCurrent()
        assertFalse(decision.isCompleted)

        // The post-BIND ACK lands: the cap leaves the pending set and availability settles Ready.
        availability = ready
        pending.value = emptySet()
        assertTrue(decision.await())
    }

    @Test
    fun anExplicitUnsupportedVerdictNeedsNoWaitAtAll() = runTest {
        val pending = MutableStateFlow(emptySet<String>())
        assertFalse(awaitHistoryCapDecision({ HistoryAvailability.Unsupported }, pending))
        assertTrue(awaitHistoryCapDecision({ ready }, pending))
    }

    @Test
    fun theEntryDecisionClaimsTheCatchUpBeforeTheBackfillTrickle() = runTest {
        // The claim is what the CAP NEW re-arm observes. Resolving it at the END of the branch puts
        // it behind `backfillTargets`, a paced enumeration that runs for as long as an account has
        // targets — so the re-arm would be queued behind a background job for minutes.
        val claimed = CompletableDeferred<Boolean>()
        val backfillEntered = CompletableDeferred<Unit>()
        val releaseBackfill = CompletableDeferred<Unit>()
        var catchUps = 0
        val branch = launch {
            decideHistoryCatchUp(
                awaitHistoryReady = { true },
                awaitReadMarkerSettlement = {},
                stillCurrent = { true },
                claimed = claimed,
                releaseGate = {},
                catchUp = { catchUps++ },
                backfill = {
                    backfillEntered.complete(Unit)
                    releaseBackfill.await()
                },
            )
        }
        runCurrent()

        assertTrue(backfillEntered.isCompleted)
        assertTrue(claimed.isCompleted)
        assertTrue(claimed.await())
        assertEquals(1, catchUps)

        releaseBackfill.complete(Unit)
        branch.join()
    }

    @Test
    fun aDeclinedDecisionReleasesTheGateAndRunsNoBackfill() = runTest {
        val claimed = CompletableDeferred<Boolean>()
        var released = 0
        decideHistoryCatchUp(
            awaitHistoryReady = { false },
            awaitReadMarkerSettlement = {},
            stillCurrent = { true },
            claimed = claimed,
            releaseGate = { released++ },
            catchUp = { throw AssertionError("no catch-up without CHATHISTORY") },
            backfill = { throw AssertionError("a network with no CHATHISTORY has nothing to enumerate") },
        )
        // Released exactly once, and the claim is negative so the CAP NEW re-arm takes over.
        assertEquals(1, released)
        assertFalse(claimed.await())
    }

    @Test
    fun theEntryGateReleasesOnceEvenWhenTheCatchUpThrows() = runTest {
        val claimed = CompletableDeferred<Boolean>()
        var released = 0
        runCatching {
            decideHistoryCatchUp(
                awaitHistoryReady = { true },
                awaitReadMarkerSettlement = {},
                stillCurrent = { true },
                claimed = claimed,
                releaseGate = { released++ },
                catchUp = { error("transport blew up mid-pass") },
                backfill = { throw AssertionError("unreachable") },
            )
        }
        // The gate must not outlive the branch: `historyCatchUpPending` is what every entry-critical
        // wait in the chat screen blocks on, so a stranded gate is an unreachable chat.
        assertEquals(1, released)
    }

    @Test
    fun aCancelledEntryDecisionStillReleasesTheGateAndArmsTheRearm() = runTest {
        val claimed = CompletableDeferred<Boolean>()
        var released = 0
        val branch = launch {
            decideHistoryCatchUp(
                awaitHistoryReady = { awaitCancellation() },
                awaitReadMarkerSettlement = {},
                stillCurrent = { true },
                claimed = claimed,
                releaseGate = { released++ },
                catchUp = {},
                backfill = {},
            )
        }
        runCurrent()
        branch.cancelAndJoin()

        assertEquals(1, released)
        assertFalse(claimed.await())
    }

    @Test
    fun theCapNewRearmRunsTheCatchUpTheEntryDecisionSkipped() = runTest {
        // Problem 1: chathistory sits in the PRE-bind CAP REQ set, so a bouncer that advertises it
        // only through a post-welcome CAP NEW reaches Ready with an empty pending set, settles
        // "unsupported", and — before the re-arm existed — skipped its Ready-session catch-up
        // entirely, with nothing left to re-trigger when the ACK finally landed.
        val claimed = CompletableDeferred<Boolean>()
        val capArrived = CompletableDeferred<Unit>()
        var catchUps = 0
        launch {
            decideHistoryCatchUp(
                awaitHistoryReady = { false },
                awaitReadMarkerSettlement = {},
                stillCurrent = { true },
                claimed = claimed,
                releaseGate = {},
                catchUp = { catchUps++ },
                backfill = {},
            )
        }
        val rearm = launch {
            rearmHistoryCatchUp(
                claimed = claimed,
                awaitCapability = { capArrived.await() },
                stillCurrent = { true },
                catchUp = { catchUps++ },
                backfill = {},
            )
        }
        runCurrent()
        assertEquals(0, catchUps)

        capArrived.complete(Unit)
        rearm.join()
        assertEquals(1, catchUps)
    }

    @Test
    fun theCapNewRearmResolvesWhileTheEntryBranchIsStillTrickling() = runTest {
        // The re-arm observes the CLAIM, never the branch: with a still-running backfill behind it,
        // waiting on the branch itself would leave the re-arm live (and this network's second pass
        // pending) for the whole trickle.
        val claimed = CompletableDeferred<Boolean>()
        val branch = launch {
            decideHistoryCatchUp(
                awaitHistoryReady = { true },
                awaitReadMarkerSettlement = {},
                stillCurrent = { true },
                claimed = claimed,
                releaseGate = {},
                catchUp = {},
                backfill = { awaitCancellation() },
            )
        }
        val rearm = launch {
            rearmHistoryCatchUp(
                claimed = claimed,
                awaitCapability = { throw AssertionError("a claimed catch-up must not be re-issued") },
                stillCurrent = { true },
                catchUp = { throw AssertionError("a claimed catch-up must not be re-issued") },
                backfill = { throw AssertionError("a claimed catch-up must not be re-issued") },
            )
        }
        runCurrent()

        assertTrue(rearm.isCompleted)
        assertFalse(branch.isCompleted)
        branch.cancelAndJoin()
    }

    @Test
    fun theCapNewRearmStandsDownForASupersededSession() = runTest {
        val claimed = CompletableDeferred(false)
        rearmHistoryCatchUp(
            claimed = claimed,
            awaitCapability = {},
            // The actor swapped the client (or the generation moved) while the cap was awaited.
            stillCurrent = { false },
            catchUp = { throw AssertionError("a superseded session owns no pass") },
            backfill = { throw AssertionError("a superseded session owns no pass") },
        )
    }

    @Test
    fun theCapNewRearmAlsoRunsTheBackfillTheEntryBranchWouldHave() = runTest {
        val claimed = CompletableDeferred(false)
        val order = mutableListOf<String>()

        rearmHistoryCatchUp(
            claimed = claimed,
            awaitCapability = {},
            stillCurrent = { true },
            catchUp = { order += "catchUp" },
            backfill = { order += "backfill" },
        )

        // The entry decision is the only OTHER caller of backfillTargets, so an account that only
        // ever reaches history through CAP NEW would otherwise never enumerate targets older than
        // the initial-sync window at all. Ordering matches the entry branch: backfill strictly last.
        assertEquals(listOf("catchUp", "backfill"), order)
    }

    @Test
    fun anUnansweredReadMarkerCapReqStillReleasesTheEntryGate() = runTest {
        val claimed = CompletableDeferred<Boolean>()
        var released = false
        var caughtUp = false

        decideHistoryCatchUp(
            awaitHistoryReady = { true },
            // The bouncer answered the post-welcome CAP REQ with nothing at all.
            awaitReadMarkerSettlement = { CompletableDeferred<Unit>().await() },
            stillCurrent = { true },
            claimed = claimed,
            releaseGate = { released = true },
            catchUp = { caughtUp = true },
            backfill = {},
            readMarkerSettleTimeoutMs = 10_000L,
        )

        // Marker settlement is entry-critical but not unbounded: chat entry must not be held for
        // the whole Ready session by a capability decision the server never makes.
        assertTrue(released)
        assertTrue(caughtUp)
    }
}
