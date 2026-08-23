package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.di.AppClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handshake's negative space: when no correlated reply ever arrives, the loop must still end,
 * and no internal restart may push the deadline out.
 */
class AgentwireSyncBudgetTest {
    private var now = 0L
    private val clock = AppClock { now }

    @Test
    fun `silence ends at the budget rather than one backoff step later`() =
        runTest {
            val budget = AgentwireSyncBudget(clock).also { it.anchor() }
            val sends = mutableListOf<Long>()
            var timedOut = 0

            retryAgentwireSync(
                budget = budget,
                isReady = { false },
                issue = {
                    sends += now
                    true
                },
                onTimeout = { timedOut += 1 },
                nextId = { "sync-${sends.size}" },
                pause = { now += it },
            )

            // 1s doubling capped at 10s, with the final wait clipped so expiry is exact.
            assertEquals(listOf<Long>(0, 1_000, 3_000, 7_000, 15_000, 25_000), sends)
            assertEquals(AGENTWIRE_SYNC_BUDGET_MS, now)
            assertEquals(6, budget.attempts)
            assertEquals(1, timedOut)
        }

    @Test
    fun `an internal restart reuses the deadline and honours the send floor`() =
        runTest {
            val budget = AgentwireSyncBudget(clock).also { it.anchor() }
            val sends = mutableListOf<Long>()
            var ready = false

            // First job: two sends, then a ResyncRequired tears the job down at t=1000.
            retryAgentwireSync(
                budget = budget,
                isReady = { ready },
                issue = {
                    sends += now
                    ready = sends.size == 2
                    true
                },
                nextId = { "sync-${sends.size}" },
                pause = { now += it },
            )
            assertEquals(listOf<Long>(0, 1_000), sends)
            assertEquals(1_000L, now)

            // The restart must not call anchor(): same deadline, and no burst of requests.
            ready = false
            var timedOut = 0
            retryAgentwireSync(
                budget = budget,
                isReady = { ready },
                issue = {
                    sends += now
                    true
                },
                onTimeout = { timedOut += 1 },
                nextId = { "sync-${sends.size}" },
                pause = { now += it },
            )

            assertEquals(AGENTWIRE_SYNC_MIN_INTERVAL_MS, sends[2] - sends[1])
            sends.zipWithNext { earlier, later ->
                assertTrue("sends must stay $AGENTWIRE_SYNC_MIN_INTERVAL_MS ms apart", later - earlier >= AGENTWIRE_SYNC_MIN_INTERVAL_MS)
            }
            assertEquals(AGENTWIRE_SYNC_BUDGET_MS, now)
            assertEquals(1, timedOut)
            assertEquals(sends.size, budget.attempts)
        }

    @Test
    fun `three consecutive failed writes end the handshake well inside the budget`() =
        runTest {
            val budget = AgentwireSyncBudget(clock).also { it.anchor() }
            var consecutive = 0
            var timedOut = 0

            retryAgentwireSync(
                budget = budget,
                isReady = { false },
                issue = { false },
                onTimeout = { timedOut += 1 },
                onSendFailed = { consecutive = it },
                pause = { now += it },
            )

            assertEquals(AGENTWIRE_SYNC_SEND_FAILURE_LIMIT, consecutive)
            assertEquals(AGENTWIRE_SYNC_SEND_FAILURE_LIMIT, budget.attempts)
            assertEquals(0, timedOut)
            assertTrue(now < AGENTWIRE_SYNC_BUDGET_MS)
        }

    @Test
    fun `re-anchoring after a failure grants a fresh budget`() =
        runTest {
            val budget = AgentwireSyncBudget(clock).also { it.anchor() }
            retryAgentwireSync(budget, isReady = { false }, issue = { true }, pause = { now += it })
            assertEquals(AGENTWIRE_SYNC_BUDGET_MS, now)

            budget.anchor()
            assertEquals(0, budget.attempts)
            var timedOut = 0
            retryAgentwireSync(
                budget = budget,
                isReady = { false },
                issue = { true },
                onTimeout = { timedOut += 1 },
                pause = { now += it },
            )
            assertEquals(2 * AGENTWIRE_SYNC_BUDGET_MS, now)
            assertEquals(1, timedOut)
        }
}
