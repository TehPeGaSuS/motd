package io.github.trevarj.motd.gesture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.agentwire.AgentwirePrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GesturePrefsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs: GesturePrefs = GesturePrefsImpl(context)

    @Test fun defaultsToOff() = runTest {
        assertEquals(false, prefs.enabled.first())
    }

    @Test fun enabledFlag_roundTrips() = runTest {
        prefs.setEnabled(true)
        assertEquals(true, prefs.enabled.first())
        prefs.setEnabled(false)
        assertEquals(false, prefs.enabled.first())
    }

    /** The two labs keep separate stores: switching one must never move the other. */
    @Test fun gestureStore_isIndependentOfTheAgentwireLab() = runTest {
        val agentwire = AgentwirePrefs(context)
        prefs.setEnabled(true)
        assertEquals(false, agentwire.enabled.first())

        agentwire.setEnabled(true)
        prefs.setEnabled(false)
        assertEquals(true, agentwire.enabled.first())
        assertEquals(false, prefs.enabled.first())
    }
}
