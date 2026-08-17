package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoAwaySettingsTest {
    private val repository: SettingsRepository =
        DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun auto_away_is_off_by_default_with_the_documented_delay_and_no_message() {
        val defaults = Settings()
        assertFalse(defaults.autoAwayEnabled)
        assertEquals(DEFAULT_AUTO_AWAY_MINUTES, defaults.autoAwayMinutes)
        assertEquals("", defaults.autoAwayMessage)
    }

    @Test
    fun auto_away_preferences_round_trip() = runTest {
        repository.setAutoAwayEnabled(true)
        repository.setAutoAwayMinutes(30)
        repository.setAutoAwayMessage("  gone fishing  ")

        val saved = repository.settings.first()
        assertTrue(saved.autoAwayEnabled)
        assertEquals(30, saved.autoAwayMinutes)
        assertEquals("gone fishing", saved.autoAwayMessage)
    }

    @Test
    fun a_blank_message_falls_back_to_the_stored_default() = runTest {
        repository.setAutoAwayMessage("brb")
        assertEquals("brb", repository.settings.first().autoAwayMessage)

        repository.setAutoAwayMessage("   ")
        assertEquals("", repository.settings.first().autoAwayMessage)
    }

    @Test
    fun an_off_list_delay_is_coerced_to_the_default() = runTest {
        repository.setAutoAwayMinutes(7)
        assertEquals(DEFAULT_AUTO_AWAY_MINUTES, repository.settings.first().autoAwayMinutes)
    }

    @Test
    fun stored_delays_are_snapped_onto_the_offered_choices() {
        assertEquals(1, autoAwayMinutesFromPreference(1))
        assertEquals(60, autoAwayMinutesFromPreference(60))
        assertEquals(DEFAULT_AUTO_AWAY_MINUTES, autoAwayMinutesFromPreference(null))
        assertEquals(DEFAULT_AUTO_AWAY_MINUTES, autoAwayMinutesFromPreference(0))
        assertEquals(DEFAULT_AUTO_AWAY_MINUTES, autoAwayMinutesFromPreference(120))
    }
}
