package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
        // New stage-1 fields keep sane defaults when absent from the store (data-class defaults,
        // not read through prefs.config here since the DataStore singleton is shared across the
        // test methods in this class).
        assertEquals(FontChoice.SYSTEM, AppearanceConfig().fontChoice)
        assertEquals(true, AppearanceConfig().showTimestamps)
        assertEquals(TimeFormat.AUTO, AppearanceConfig().timeFormat)
        assertEquals(MessageSpacing.DEFAULT, AppearanceConfig().messageSpacing)
        assertEquals(BubbleCornerStyle.ROUNDED, AppearanceConfig().bubbleCornerStyle)
        assertEquals(LauncherIcon.DEFAULT, AppearanceConfig().launcherIcon)
        assertEquals("", AppearanceConfig().customFontName)
    }

    @Test fun newAppearanceFields_roundTripNonDefaultValues() =
        runTest {
            prefs.setFontChoice(FontChoice.JETBRAINS_MONO)
            prefs.setShowTimestamps(false)
            prefs.setTimeFormat(TimeFormat.H24)
            prefs.setMessageSpacing(MessageSpacing.RELAXED)
            prefs.setBubbleCornerStyle(BubbleCornerStyle.SQUARE)
            prefs.setLauncherIcon(LauncherIcon.GRUVBOX)

            val config = prefs.config.first()
            assertEquals(FontChoice.JETBRAINS_MONO, config.fontChoice)
            assertEquals(false, config.showTimestamps)
            assertEquals(TimeFormat.H24, config.timeFormat)
            assertEquals(MessageSpacing.RELAXED, config.messageSpacing)
            assertEquals(BubbleCornerStyle.SQUARE, config.bubbleCornerStyle)
            assertEquals(LauncherIcon.GRUVBOX, config.launcherIcon)
        }

    @Test fun customFontName_roundTrips() =
        runTest {
            // The data-class default (empty) is covered by defaults_areSystemAndChatterAtEighty; this
            // DataStore instance is shared across every test method in this class (see that test's
            // comment), so a leading "reads empty" assertion here would be order-dependent.
            prefs.setCustomFontName("Iosevka Term.ttf")
            assertEquals("Iosevka Term.ttf", prefs.config.first().customFontName)
            prefs.setFontChoice(FontChoice.CUSTOM)
            assertEquals(FontChoice.CUSTOM, prefs.config.first().fontChoice)
            prefs.setCustomFontName("")
            assertEquals("", prefs.config.first().customFontName)
        }

    @Test fun garbageStoredEnumStrings_decodeToDefaults() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.appearanceDataStore.edit {
                it[stringPreferencesKey("font_choice_v1")] = "not-a-font"
                it[stringPreferencesKey("time_format_v1")] = "not-a-format"
                it[stringPreferencesKey("message_spacing_v1")] = "not-a-spacing"
                it[stringPreferencesKey("bubble_corner_style_v1")] = "not-a-style"
                it[stringPreferencesKey("launcher_icon_v1")] = "not-an-icon"
            }

            val config = prefs.config.first()
            assertEquals(FontChoice.SYSTEM, config.fontChoice)
            assertEquals(TimeFormat.AUTO, config.timeFormat)
            assertEquals(MessageSpacing.DEFAULT, config.messageSpacing)
            assertEquals(BubbleCornerStyle.ROUNDED, config.bubbleCornerStyle)
            assertEquals(LauncherIcon.DEFAULT, config.launcherIcon)
        }

    @Test fun themeAndWallpaper_roundTrip() =
        runTest {
            prefs.setTheme(ColorThemePreset.KANAGAWA_WAVE)
            prefs.setTrueBlack(true)
            prefs.setFollowSystem(true)
            prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.RELAY, 73))
            assertEquals(ColorThemePreset.KANAGAWA_WAVE, prefs.config.first().theme)
            assertEquals(true, prefs.config.first().trueBlack)
            assertEquals(true, prefs.config.first().followSystem)
            assertEquals(WallpaperSelection(ChatWallpaperPreset.RELAY, 73), prefs.config.first().wallpaper)
        }

    @Test fun followSystem_defaultsFalseAndRoundTrips() =
        runTest {
            assertEquals(false, prefs.config.first().followSystem)
            prefs.setFollowSystem(true)
            assertEquals(true, prefs.config.first().followSystem)
            prefs.setFollowSystem(false)
            assertEquals(false, prefs.config.first().followSystem)
        }

    @Test fun legacyAmoledTheme_migratesToAutomaticFamilyWithTrueBlack() {
        assertEquals(
            StoredThemeResolution(ColorThemePreset.DARK, true),
            resolveStoredTheme(ColorThemePreset.AMOLED.name, explicitTrueBlack = null),
        )
        assertEquals(
            StoredThemeResolution(ColorThemePreset.DARK, false),
            resolveStoredTheme(ColorThemePreset.AMOLED.name, explicitTrueBlack = false),
        )
    }

    @Test fun legacyAmoledSetter_preservesCompatibility() =
        runTest {
            prefs.setTheme(ColorThemePreset.AMOLED)
            assertEquals(ColorThemePreset.DARK, prefs.config.first().theme)
            assertEquals(true, prefs.config.first().trueBlack)
        }

    @Test fun storedDarkSibling_remainsAnExplicitSelection() {
        assertEquals(
            StoredThemeResolution(ColorThemePreset.CATPPUCCIN_MOCHA, false),
            resolveStoredTheme(ColorThemePreset.CATPPUCCIN_MOCHA.name, explicitTrueBlack = false),
        )
    }

    @Test fun wallpaperIntensity_isClampedAtomically() =
        runTest {
            prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.SIGNALS, 500))
            assertEquals(WallpaperSelection(ChatWallpaperPreset.SIGNALS, 100), prefs.config.first().wallpaper)
            prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.PIXELS, -9))
            assertEquals(WallpaperSelection(ChatWallpaperPreset.PIXELS, 0), prefs.config.first().wallpaper)
        }

    @Test fun fontScales_areIndependentRoundedAndClamped() =
        runTest {
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
