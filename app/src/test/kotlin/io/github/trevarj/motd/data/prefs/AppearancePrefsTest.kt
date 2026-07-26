package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearancePrefsTest {
    private val prefs: AppearancePrefs = AppearancePrefsImpl(ApplicationProvider.getApplicationContext<Context>())

    @Test fun defaults_areSystemAndChatterAtEighty() {
        assertEquals(
            AppearanceConfig(
                ColorThemePreset.SYSTEM,
                WallpaperSelection(ChatWallpaperPreset.CHATTER, 80),
                100,
                100,
            ),
            AppearanceConfig(),
        )
    }

    @Test fun themeAndWallpaper_roundTrip() = runTest {
        prefs.setTheme(ColorThemePreset.KANAGAWA_WAVE)
        prefs.setTrueBlack(true)
        prefs.setFollowSystem(true)
        prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.RELAY, 73))
        assertEquals(ColorThemePreset.KANAGAWA_LOTUS, prefs.config.first().theme)
        assertEquals(true, prefs.config.first().trueBlack)
        assertEquals(true, prefs.config.first().followSystem)
        assertEquals(WallpaperSelection(ChatWallpaperPreset.RELAY, 73), prefs.config.first().wallpaper)
    }

    @Test fun followSystem_defaultsFalseAndRoundTrips() = runTest {
        assertEquals(false, prefs.config.first().followSystem)
        prefs.setFollowSystem(true)
        assertEquals(true, prefs.config.first().followSystem)
        prefs.setFollowSystem(false)
        assertEquals(false, prefs.config.first().followSystem)
    }

    @Test fun legacyAmoledTheme_migratesToAutomaticFamilyWithTrueBlack() {
        assertEquals(
            StoredThemeResolution(ColorThemePreset.LIGHT, true),
            resolveStoredTheme(ColorThemePreset.AMOLED.name, explicitTrueBlack = null),
        )
        assertEquals(
            StoredThemeResolution(ColorThemePreset.LIGHT, false),
            resolveStoredTheme(ColorThemePreset.AMOLED.name, explicitTrueBlack = false),
        )
    }

    @Test fun legacyAmoledSetter_preservesCompatibility() = runTest {
        prefs.setTheme(ColorThemePreset.AMOLED)
        assertEquals(ColorThemePreset.LIGHT, prefs.config.first().theme)
        assertEquals(true, prefs.config.first().trueBlack)
    }

    @Test fun storedDarkSibling_migratesToAutomaticThemeFamily() {
        assertEquals(
            StoredThemeResolution(ColorThemePreset.CATPPUCCIN_LATTE, false),
            resolveStoredTheme(ColorThemePreset.CATPPUCCIN_MOCHA.name, explicitTrueBlack = false),
        )
    }

    @Test fun wallpaperIntensity_isClampedAtomically() = runTest {
        prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.SIGNALS, 500))
        assertEquals(WallpaperSelection(ChatWallpaperPreset.SIGNALS, 100), prefs.config.first().wallpaper)
        prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.PIXELS, -9))
        assertEquals(WallpaperSelection(ChatWallpaperPreset.PIXELS, 0), prefs.config.first().wallpaper)
    }

    @Test fun fontScales_areIndependentRoundedAndClamped() = runTest {
        prefs.setUiFontScale(83)
        prefs.setConversationFontScale(999)
        assertEquals(85, prefs.config.first().uiFontScalePercent)
        assertEquals(140, prefs.config.first().conversationFontScalePercent)

        prefs.setUiFontScale(-20)
        prefs.setConversationFontScale(117)
        assertEquals(80, prefs.config.first().uiFontScalePercent)
        assertEquals(115, prefs.config.first().conversationFontScalePercent)

        prefs.setUiFontScale(DEFAULT_FONT_SCALE_PERCENT)
        prefs.setConversationFontScale(DEFAULT_FONT_SCALE_PERCENT)
        assertEquals(DEFAULT_FONT_SCALE_PERCENT, prefs.config.first().uiFontScalePercent)
        assertEquals(DEFAULT_FONT_SCALE_PERCENT, prefs.config.first().conversationFontScalePercent)
    }
}
