package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ComposerToolsSettingsTest {
    private val repository: SettingsRepository =
        DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun composerToolsDefaultOnForPreferencesAndOldSerializedSettings() =
        runTest {
            val defaults = Settings()
            assertTrue(defaults.showComposerEmoji)
            assertTrue(defaults.showComposerFormattingTools)

            val storedDefaults = repository.settings.first()
            assertTrue(storedDefaults.showComposerEmoji)
            assertTrue(storedDefaults.showComposerFormattingTools)

            val oldSettings = Json.decodeFromString<Settings>("{}")
            assertTrue(oldSettings.showComposerEmoji)
            assertTrue(oldSettings.showComposerFormattingTools)
        }

    @Test
    fun formattingToolsPreferenceRoundTrips() =
        runTest {
            repository.setShowComposerFormattingTools(false)
            assertFalse(repository.settings.first().showComposerFormattingTools)

            repository.setShowComposerFormattingTools(true)
            assertTrue(repository.settings.first().showComposerFormattingTools)
        }
}
