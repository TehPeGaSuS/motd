package io.github.trevarj.motd.di

import io.github.trevarj.motd.diagnostics.RecordingDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The application scope must survive its own root coroutines. Robolectric because containment logs
 * through `android.util.Log`. Unconfined so every launch below completes inline.
 */
@RunWith(RobolectricTestRunner::class)
class ApplicationScopeTest {
    private val diagnostics = RecordingDiagnostics()
    private val scope = CoroutineModule.applicationScope(Dispatchers.Unconfined, diagnostics)

    @Test
    fun rootFailureIsContainedAndRecorded() {
        scope.launch { error("root launch failed") }

        assertTrue(scope.isActive)
        val recorded = diagnostics.events.single()
        assertEquals("app", recorded.component)
        assertEquals("scope_failure", recorded.event)
        assertEquals("IllegalStateException", recorded.fields["error"])
    }

    @Test
    fun theFailureMessageNeverReachesTheJournal() {
        scope.launch { error("nick!user@secret.host leaked") }

        val recorded = diagnostics.events.single()
        assertFalse(recorded.fields.values.any { it?.toString()?.contains("secret.host") == true })
    }

    @Test
    fun laterRootWorkStillRunsAfterAFailure() {
        val ran = CompletableDeferred<Unit>()

        scope.launch { error("first launch failed") }
        scope.launch { ran.complete(Unit) }

        assertTrue(ran.isCompleted)
        assertEquals(1, diagnostics.events.size)
    }
}
