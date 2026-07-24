package io.github.trevarj.motd.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.nickColorPaletteFromPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Nick-color resolution order + CLASSIC-palette equality with the legacy nickColor generator. */
class NickColorResolveTest {

    private val nicks = listOf("alice", "Bob", "carol_", "#chan", "dave|away", "eve")
    private val fallback = Color(0.5f, 0.5f, 0.5f)
    private val themeColors = listOf(Color.Red, Color.Green, Color.Blue)

    @Test
    fun disabled_returnsFallback() {
        val c = resolveNickColor("alice", isDark = true, enabled = false,
            palette = NickColorPalette.VIVID, overrides = mapOf("alice" to 120), fallback = fallback)
        assertEquals(fallback, c)
    }

    @Test
    fun override_beats_hash() {
        // Override present -> hueColor for that hue; differs from the palette-hash color.
        val overridden = resolveNickColor("alice", isDark = false, enabled = true,
            palette = NickColorPalette.CLASSIC, overrides = mapOf("alice" to 210), fallback = fallback)
        assertEquals(hueColor(210, false, NickColorPalette.CLASSIC), overridden)
        assertNotEquals(paletteNickColor("alice", false, NickColorPalette.CLASSIC), overridden)
    }

    @Test
    fun override_lookup_is_normalized() {
        // Overrides are keyed by normalized nick; a raw " Alice " still resolves.
        val c = resolveNickColor(" Alice ", isDark = true, enabled = true,
            palette = NickColorPalette.CLASSIC, overrides = mapOf("alice" to 90), fallback = fallback)
        assertEquals(hueColor(90, true, NickColorPalette.CLASSIC), c)
    }

    @Test
    fun classicPalette_noOverride_equals_legacy_nickColor() {
        for (nick in nicks) {
            for (isDark in listOf(true, false)) {
                assertEquals(
                    "CLASSIC palette must match legacy nickColor for $nick dark=$isDark",
                    nickColor(nick, isDark),
                    paletteNickColor(nick, isDark, NickColorPalette.CLASSIC),
                )
                // ...and through the full resolver with no override.
                assertEquals(
                    nickColor(nick, isDark),
                    resolveNickColor(nick, isDark, enabled = true,
                        palette = NickColorPalette.CLASSIC, overrides = emptyMap(), fallback = fallback),
                )
            }
        }
    }

    @Test
    fun hueColor_rendersEachPickerHue() {
        val hues = listOf(0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330)
        for (h in hues) {
            for (palette in NickColorPalette.entries) {
                // Renders a concrete (non-Unspecified) color for each mode.
                assertNotEquals(Color.Unspecified, hueColor(h, true, palette, themeColors))
                assertNotEquals(Color.Unspecified, hueColor(h, false, palette, themeColors))
            }
        }
    }

    @Test
    fun hueColor_clampsOutOfRange() {
        // Out-of-range hues clamp into 0..359 (359 vs 400 both clamp to the same color).
        assertEquals(hueColor(359, true, NickColorPalette.VIVID), hueColor(400, true, NickColorPalette.VIVID))
        assertEquals(
            hueColor(0, false, NickColorPalette.THEME, themeColors),
            hueColor(-20, false, NickColorPalette.THEME, themeColors),
        )
    }

    @Test
    fun themePalette_usesActiveThemeAccents() {
        val warm = listOf(Color.Red, Color.Yellow, Color.Magenta)
        val cool = listOf(Color.Blue, Color.Cyan, Color.Green)

        assertEquals(Color.Red, hueColor(0, false, NickColorPalette.THEME, warm))
        assertNotEquals(
            paletteNickColor("alice", false, NickColorPalette.THEME, warm),
            paletteNickColor("alice", false, NickColorPalette.THEME, cool),
        )
    }

    @Test
    fun themePalette_isDefault_andMigratesPastel() {
        assertEquals(NickColorPalette.THEME, Settings().nickColorPalette)
        assertEquals(NickColorPalette.THEME, nickColorPaletteFromPreference(null))
        assertEquals(NickColorPalette.THEME, nickColorPaletteFromPreference("DEFAULT"))
        assertEquals(NickColorPalette.THEME, nickColorPaletteFromPreference("PASTEL"))
        assertEquals(NickColorPalette.CLASSIC, nickColorPaletteFromPreference("CLASSIC"))
        assertEquals(NickColorPalette.VIVID, nickColorPaletteFromPreference("VIVID"))
    }

    @Test
    fun scheme_avatar_ignoresEnabledFlag() {
        // With coloring disabled, sender text goes neutral but avatars keep their generated color.
        val scheme = NickColorScheme(enabled = false, palette = NickColorPalette.CLASSIC,
            overrides = emptyMap(), isDark = true)
        assertEquals(fallback, scheme.nick("alice", fallback))
        assertEquals(paletteNickColor("alice", true, NickColorPalette.CLASSIC), scheme.avatar("alice"))
    }
}
