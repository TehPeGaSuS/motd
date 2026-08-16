package io.github.trevarj.motd.service

import io.github.trevarj.motd.diagnostics.RecordingDiagnostics
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric because a refused start is logged through `android.util.Log`. */
@RunWith(RobolectricTestRunner::class)
class ForegroundStartTest {
    private val diagnostics = RecordingDiagnostics()

    @Test
    fun acceptedStartReportsSuccessAndRecordsNothing() {
        var started = false

        assertTrue(startForegroundSafely(diagnostics, source = "service") { started = true })

        assertTrue(started)
        assertTrue(diagnostics.events.isEmpty())
    }

    @Test
    fun refusedStartIsContainedAndRecorded() {
        // What Android 12+ answers a background foreground-service start with.
        val accepted = startForegroundSafely(diagnostics, source = "activity") {
            throw SecurityException("startForegroundService() not allowed")
        }

        assertFalse(accepted)
        val recorded = diagnostics.events.single()
        assertEquals("lifecycle", recorded.component)
        assertEquals("foreground_start_refused", recorded.event)
        assertEquals("activity", recorded.fields["source"])
        assertEquals("SecurityException", recorded.fields["error"])
    }

    @Test
    fun cancellationStillPropagates() {
        var propagated = false

        try {
            startForegroundSafely(diagnostics, source = "keeper") {
                throw CancellationException("caller cancelled")
            }
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
        assertTrue(diagnostics.events.isEmpty())
    }
}
