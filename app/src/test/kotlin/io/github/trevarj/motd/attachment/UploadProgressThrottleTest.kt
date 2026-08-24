package io.github.trevarj.motd.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadProgressThrottleTest {
    @Test
    fun `fast hundred megabyte upload emits at most every 256 KiB`() {
        val throttle = UploadProgressThrottle(startedAtNanos = 0)
        var emissions = 0
        var sent = 0L
        repeat(100 * 1024 * 1024 / (32 * 1024)) {
            sent += 32L * 1024L
            if (throttle.shouldEmit(sent, nowNanos = 0)) emissions++
        }
        if (throttle.shouldEmit(sent, nowNanos = 0, final = true)) emissions++

        assertEquals(400, emissions)
    }

    @Test
    fun `slow upload reports every hundred milliseconds and final bytes`() {
        val throttle = UploadProgressThrottle(startedAtNanos = 0)

        assertFalse(throttle.shouldEmit(32 * 1024L, nowNanos = 99_000_000))
        assertTrue(throttle.shouldEmit(64 * 1024L, nowNanos = 100_000_000))
        assertFalse(throttle.shouldEmit(65 * 1024L, nowNanos = 150_000_000))
        assertTrue(throttle.shouldEmit(65 * 1024L, nowNanos = 150_000_000, final = true))
        assertFalse(throttle.shouldEmit(65 * 1024L, nowNanos = 200_000_000, final = true))
    }
}
