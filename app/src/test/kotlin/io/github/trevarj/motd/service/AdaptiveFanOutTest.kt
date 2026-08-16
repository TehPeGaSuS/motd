package io.github.trevarj.motd.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The admission control one catch-up pass runs on.
 *
 * The properties under test are the three the pass depends on and cannot observe for itself:
 * admission is FIFO (so the pass's deliberate ordering survives), a shrink applies to admission
 * rather than to slots already held (so a slow server cannot cancel work already in flight), and
 * widening is earned by a run of clean responses rather than by one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveFanOutTest {

    @Test
    fun admitsUpToTheWidthAndQueuesTheRest() = runTest {
        val fanOut = AdaptiveFanOut(initialWidth = 2, ceiling = 4)
        val entered = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val slots = (1..5).map {
            async {
                fanOut.withSlot {
                    entered.incrementAndGet()
                    release.await()
                }
            }
        }
        runCurrent()

        assertEquals(2, entered.get())
        assertEquals(2, fanOut.inFlight)
        release.complete(Unit)
        slots.awaitAll()
        assertEquals(5, entered.get())
        assertEquals(0, fanOut.inFlight)
    }

    @Test
    fun admissionIsFifoSoThePassOrderingSurvives() = runTest {
        // The pass puts the visible chat first on purpose; a slot handed to whichever waiter the
        // scheduler happens to resume would throw that away silently.
        val fanOut = AdaptiveFanOut(initialWidth = 1, ceiling = 1)
        val admitted = Collections.synchronizedList(mutableListOf<Int>())
        val hold = CompletableDeferred<Unit>()

        val leader = async { fanOut.withSlot { admitted += 0; hold.await() } }
        runCurrent()
        val queued = (1..4).map { index ->
            async { fanOut.withSlot { admitted += index } }.also { runCurrent() }
        }

        hold.complete(Unit)
        leader.await()
        queued.awaitAll()

        assertEquals(listOf(0, 1, 2, 3, 4), admitted.toList())
    }

    @Test
    fun aTimeoutHalvesTheWidthAndAStreakOfSuccessesEarnsItBack() {
        val fanOut = AdaptiveFanOut(initialWidth = 6, ceiling = 6, restoreStreak = 3)

        fanOut.onTimeout()
        assertEquals(3, fanOut.currentWidth)
        fanOut.onTimeout()
        assertEquals(1, fanOut.currentWidth)
        // Floor, not zero: a pass that admits nothing makes no progress at all.
        fanOut.onTimeout()
        assertEquals(1, fanOut.currentWidth)

        // One clean response proves nothing; the streak does.
        fanOut.onSuccess()
        fanOut.onSuccess()
        assertEquals(1, fanOut.currentWidth)
        fanOut.onSuccess()
        assertEquals(2, fanOut.currentWidth)

        // A timeout mid-streak forfeits the progress toward widening as well as the width.
        fanOut.onSuccess()
        fanOut.onSuccess()
        fanOut.onTimeout()
        fanOut.onSuccess()
        fanOut.onSuccess()
        assertEquals(1, fanOut.currentWidth)
    }

    @Test
    fun widthNeverGrowsPastTheCeiling() {
        val fanOut = AdaptiveFanOut(initialWidth = 2, ceiling = 2, restoreStreak = 1)

        repeat(10) { fanOut.onSuccess() }

        assertEquals(2, fanOut.currentWidth)
    }

    @Test
    fun shrinkingMidFlightDrainsInsteadOfRevokingHeldSlots() = runTest {
        val fanOut = AdaptiveFanOut(initialWidth = 4, ceiling = 4)
        val entered = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()

        val holders = (1..4).map {
            async {
                fanOut.withSlot {
                    if (entered.incrementAndGet() == 4) waiting.complete(Unit)
                    release.await()
                }
            }
        }
        waiting.await()
        val queued = async { fanOut.withSlot { entered.incrementAndGet() } }
        runCurrent()

        // The server is behind. The four requests already on the wire keep their slots — cancelling
        // one would turn a slow server into a failed pass — but admission is now narrower.
        fanOut.onTimeout()
        assertEquals(2, fanOut.currentWidth)
        assertEquals(4, fanOut.inFlight)
        assertEquals(4, entered.get())

        release.complete(Unit)
        holders.awaitAll()
        queued.await()
        // No deadlock: the over-width holders drained and the queued waiter was admitted after.
        assertEquals(5, entered.get())
        assertEquals(0, fanOut.inFlight)
    }

    @Test
    fun aCancelledWaiterReleasesTheSlotItWasAboutToTake() = runTest {
        val fanOut = AdaptiveFanOut(initialWidth = 1, ceiling = 1)
        val hold = CompletableDeferred<Unit>()
        val entered = AtomicInteger()

        val leader = async { fanOut.withSlot { entered.incrementAndGet(); hold.await() } }
        runCurrent()
        val abandoned = launch { fanOut.withSlot { entered.incrementAndGet() } }
        runCurrent()
        val successor = async { fanOut.withSlot { entered.incrementAndGet() } }
        runCurrent()

        abandoned.cancelAndJoin()
        hold.complete(Unit)
        leader.await()
        successor.await()

        // The abandoned waiter never ran, and its place in the queue did not strand the successor.
        assertEquals(2, entered.get())
        assertEquals(0, fanOut.inFlight)
    }

    @Test
    fun concurrentAcquireAndReleaseNeverExceedsTheWidth() = runTest {
        val fanOut = AdaptiveFanOut(initialWidth = 3, ceiling = 3)
        val live = AtomicInteger()
        val peak = AtomicInteger()

        coroutineScope {
            (1..64).map {
                async {
                    fanOut.withSlot {
                        val now = live.incrementAndGet()
                        peak.updateAndGet { previous -> maxOf(previous, now) }
                        yield()
                        live.decrementAndGet()
                    }
                }
            }.awaitAll()
        }

        assertTrue("peak $peak exceeded the width", peak.get() <= 3)
        assertEquals(0, fanOut.inFlight)
    }
}
