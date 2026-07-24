package io.github.trevarj.motd.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.normalizeNick
import kotlin.math.abs
import kotlin.math.floor

// Golden-ratio conjugate: spreads sequential hashes into well-separated hues.
private const val GOLDEN_RATIO_CONJUGATE = 0.618033988749895

/**
 * Deterministic per-nick color used for sender names and avatar backgrounds.
 *
 * The nick is hashed to a 0..1 seed, multiplied by the golden ratio conjugate and wrapped, which
 * scatters similar nicks across the hue wheel instead of clustering them. Saturation/lightness are
 * tuned per mode so text stays legible on the mode's surfaces.
 */
fun nickColor(nick: String, isDark: Boolean): Color {
    val hue = nickHue(nick)
    // Brighter, less-saturated on dark; deeper, more-saturated on light.
    val saturation = if (isDark) 0.55f else 0.65f
    val lightness = if (isDark) 0.68f else 0.42f
    return hslColor(hue, saturation, lightness)
}

/** Golden-ratio hash hue in degrees (0..360). Shared by nickColor + paletteNickColor so the
 *  CLASSIC palette maps to identical hues as the legacy generator. */
private fun nickHue(nick: String): Float {
    // Stable, case-insensitive hash so "Alice" and "alice" share a color.
    var hash = 0
    for (c in nick.lowercase()) {
        hash = hash * 31 + c.code
    }
    val seed = (abs(hash) % 1000) / 1000.0
    return ((seed + GOLDEN_RATIO_CONJUGATE) % 1.0).toFloat() * 360f
}

// Palette saturation/lightness per mode; the hue always comes from the hash or an override.
private fun paletteSaturation(palette: NickColorPalette, isDark: Boolean): Float = when (palette) {
    NickColorPalette.THEME,
    NickColorPalette.CLASSIC -> if (isDark) 0.55f else 0.65f
    NickColorPalette.VIVID -> if (isDark) 0.80f else 0.85f
}

private fun paletteLightness(palette: NickColorPalette, isDark: Boolean): Float = when (palette) {
    NickColorPalette.THEME,
    NickColorPalette.CLASSIC -> if (isDark) 0.68f else 0.42f
    NickColorPalette.VIVID -> if (isDark) 0.62f else 0.38f
}

/**
 * Per-nick color scheme resolved once per theme and passed via [LocalNickColors]. Wraps the pure
 * resolvers so components only need `LocalNickColors.current.nick(...)` / `.avatar(...)`.
 */
@Immutable
class NickColorScheme(
    val enabled: Boolean,
    val palette: NickColorPalette,
    val overrides: Map<String, Int>, // normalized nick -> hue
    val isDark: Boolean,
    private val textBackgrounds: List<Color> = emptyList(),
    private val themeColors: List<Color> = emptyList(),
) {
    // Memoize the per-nick color: it depends only on the (bounded) nick set for a given scheme.
    // A new scheme instance is created whenever palette/overrides/theme change, discarding this
    // cache, so it never goes stale. ConcurrentHashMap guards against any off-main composition.
    private val identityCache = java.util.concurrent.ConcurrentHashMap<String, Color>()
    private val textCache = java.util.concurrent.ConcurrentHashMap<String, Color>()
    private fun identityColor(nick: String): Color = identityCache.getOrPut(nick) {
        resolveNickColor(
            nick,
            isDark,
            enabled = true,
            palette,
            overrides,
            Color.Unspecified,
            themeColors,
        )
    }
    private fun textColor(nick: String): Color = textCache.getOrPut(nick) {
        val identity = identityColor(nick)
        if (textBackgrounds.isEmpty()) identity else ensureContrast(identity, textBackgrounds)
    }

    /** Sender-name/reply-accent color; [fallback] when coloring is disabled. */
    fun nick(nick: String, fallback: Color): Color = if (!enabled) fallback else textColor(nick)

    /** Avatar background: override + palette always apply (never falls back to neutral). */
    fun avatar(name: String): Color = identityColor(name)

    /** Color-picker position rendered through the same palette as nick and channel identities. */
    fun hue(hue: Int): Color = hueColor(hue, isDark, palette, themeColors)
}

/** Theme-derived defaults for previews and un-provided contexts. */
val LocalNickColors: ProvidableCompositionLocal<NickColorScheme> =
    staticCompositionLocalOf {
        NickColorScheme(
            enabled = true,
            palette = NickColorPalette.THEME,
            overrides = emptyMap(),
            isDark = false,
            themeColors = listOf(
                MotdLightScheme.primary,
                MotdLightScheme.tertiary,
                MotdLightScheme.secondary,
            ),
        )
    }

/** Resolution order: disabled -> fallback; override hue -> hueColor; else palette hash. Pure. */
fun resolveNickColor(
    nick: String,
    isDark: Boolean,
    enabled: Boolean,
    palette: NickColorPalette,
    overrides: Map<String, Int>,
    fallback: Color,
    themeColors: List<Color> = emptyList(),
): Color {
    if (!enabled) return fallback
    val override = overrides[normalizeNick(nick)]
    return if (override != null) hueColor(override, isDark, palette, themeColors)
    else paletteNickColor(nick, isDark, palette, themeColors)
}

/** Palette hash color: golden-ratio hue (identical hue math to nickColor) + palette S/L. Pure. */
fun paletteNickColor(
    nick: String,
    isDark: Boolean,
    palette: NickColorPalette,
    themeColors: List<Color> = emptyList(),
): Color {
    val hue = nickHue(nick)
    return if (palette == NickColorPalette.THEME) {
        themePaletteColor(hue, themeColors, isDark)
    } else {
        hslColor(hue, paletteSaturation(palette, isDark), paletteLightness(palette, isDark))
    }
}

/** Fixed-hue color with the palette's S/L for the mode (override rendering + picker swatches). */
fun hueColor(
    hue: Int,
    isDark: Boolean,
    palette: NickColorPalette,
    themeColors: List<Color> = emptyList(),
): Color {
    val position = hue.coerceIn(0, 359).toFloat()
    return if (palette == NickColorPalette.THEME) {
        themePaletteColor(position, themeColors, isDark)
    } else {
        hslColor(position, paletteSaturation(palette, isDark), paletteLightness(palette, isDark))
    }
}

/**
 * Treat the active theme accents as a cyclic gradient. Hashes and manual overrides select a stable
 * position around primary -> tertiary -> secondary -> primary, keeping identities distinct while
 * ensuring every generated color belongs to the current theme.
 */
private fun themePaletteColor(position: Float, themeColors: List<Color>, isDark: Boolean): Color {
    val colors = themeColors.distinct()
    if (colors.isEmpty()) {
        return hslColor(
            position,
            paletteSaturation(NickColorPalette.CLASSIC, isDark),
            paletteLightness(NickColorPalette.CLASSIC, isDark),
        )
    }
    if (colors.size == 1) return colors.single()

    val scaled = position.coerceIn(0f, 359f) / 360f * colors.size
    val start = floor(scaled).toInt().coerceAtMost(colors.lastIndex)
    val amount = scaled - floor(scaled)
    return lerp(colors[start], colors[(start + 1) % colors.size], amount)
}

/** HSL -> RGB Color (h in degrees, s/l in 0..1). */
internal fun hslColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = h / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(r1 + m, g1 + m, b1 + m)
}
