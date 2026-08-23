package io.github.trevarj.motd.irc.client

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LagMonitorTest {
    private fun TestScope.monitor(
        sent: MutableList<String> = mutableListOf(),
        registered: () -> Boolean = { true },
        clock: () -> Long = { 0L },
        intervalMs: Long = 30_000L,
    ): Pair<LagMonitor, MutableStateFlow<Long?>> {
        val sink = MutableStateFlow<Long?>(null)
        return LagMonitor(
            scope = backgroundScope,
            sendPing = { payload -> sent += payload },
            isRegistered = registered,
            sink = sink,
            intervalMs = intervalMs,
            nowMs = clock,
        ) to sink
    }

    @Test
    fun `periodic probe sends a lag ping and pong records rtt`() =
        runTest {
            val sent = mutableListOf<String>()
            var clock = 0L
            val (monitor, _) = monitor(sent = sent, clock = { clock })
            monitor.start()

            // No probe before the first interval elapses.
            runCurrent()
            assertTrue(sent.isEmpty())

            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(listOf("motd-lag-1"), sent)

            clock = 250L
            assertTrue(monitor.onPong(listOf("motd-lag-1")))
            assertEquals(250L, monitor.lag.value)

            monitor.stop()
        }

    @Test
    fun `unmatched pong is not consumed and leaves lag unchanged`() =
        runTest {
            val sent = mutableListOf<String>()
            val (monitor, _) = monitor(sent = sent)
            monitor.start()
            advanceTimeBy(30_001)
            runCurrent()
            // A watchdog keepalive PONG (motd-<epoch>) must not match a lag probe.
            assertFalse(monitor.onPong(listOf("motd-1700000000000")))
            assertNull(monitor.lag.value)
            monitor.stop()
        }

    @Test
    fun `pong matches any param position`() =
        runTest {
            val sent = mutableListOf<String>()
            var clock = 0L
            val (monitor, _) = monitor(sent = sent, clock = { clock })
            monitor.start()
            advanceTimeBy(30_001)
            runCurrent()
            clock = 100L
            // Some servers prepend their name: PONG <server> <token>.
            assertTrue(monitor.onPong(listOf("irc.example.org", "motd-lag-1")))
            assertEquals(100L, monitor.lag.value)
            monitor.stop()
        }

    @Test
    fun `probes are skipped until registered`() =
        runTest {
            val sent = mutableListOf<String>()
            var registered = false
            val (monitor, _) = monitor(sent = sent, registered = { registered })
            monitor.start()
            advanceTimeBy(30_001)
            runCurrent()
            assertTrue(sent.isEmpty())

            registered = true
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(listOf("motd-lag-1"), sent)
            monitor.stop()
        }

    @Test
    fun `reset clears the published reading`() =
        runTest {
            var clock = 0L
            val (monitor, _) = monitor(clock = { clock })
            monitor.start()
            advanceTimeBy(30_001)
            runCurrent()
            clock = 500L
            assertTrue(monitor.onPong(listOf("motd-lag-1")))
            assertEquals(500L, monitor.lag.value)

            monitor.reset()
            assertNull(monitor.lag.value)
            monitor.stop()
        }

    @Test
    fun `implausibly large rtt is discarded`() =
        runTest {
            var clock = 0L
            val (monitor, _) = monitor(clock = { clock })
            monitor.start()
            advanceTimeBy(30_001)
            runCurrent()
            clock = 10 * 60 * 1000L // 10 minutes after send
            assertTrue(monitor.onPong(listOf("motd-lag-1")))
            // Beyond MAX_LAG_MS: the probe is retired but no reading is published.
            assertNull(monitor.lag.value)
            monitor.stop()
        }

    @Test
    fun `start resets a previously published reading`() =
        runTest {
            var clock = 0L
            val (monitor, sink) = monitor(clock = { clock })
            sink.value = 999L
            monitor.start()
            // start() clears the sink so a reused monitor does not surface a stale RTT.
            assertNull(monitor.lag.value)
            monitor.stop()
        }
}
