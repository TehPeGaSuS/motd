package io.github.trevarj.motd.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPositionPollerTest {
    @Test
    fun `poller stays idle until started and remains single`() =
        runTest {
            var ticks = 0
            val poller = AudioPositionPoller(backgroundScope, 250) { ticks++ }

            advanceTimeBy(1_000)
            assertEquals(0, ticks)
            assertFalse(poller.isRunning)

            poller.start()
            poller.start()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(5, ticks)
            assertTrue(poller.isRunning)

            poller.stop()
            advanceTimeBy(1_000)
            assertEquals(5, ticks)
            assertFalse(poller.isRunning)
        }
}
