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
 * Deterministic per-nick color used for sender names and avatar backgrounds. Equivalent to the
 * CLASSIC hash palette: the spread seed snapped to the curated bin table.
 */
fun nickColor(nick: String, isDark: Boolean): Color =
    paletteNickColor(nick, isDark, NickColorPalette.CLASSIC)

/**
 * Hash seed in 0..1, scattered by *multiplying* with the golden-ratio conjugate. Adjacent hashes
 * (`bob1`/`bob2`, `alice`/`alice_` — the x31 hash is linear in trailing chars) land ~0.618 apart
 * instead of adjacent; the previous additive form was a constant rotation that spread nothing.
 */
private fun nickHueSeed(nick: String): Double {
    // Stable, case-insensitive hash so "Alice" and "alice" share a color.
    var hash = 0
    for (c in nick.lowercase()) {
        hash = hash * 31 + c.code
    }
    val spread = abs(hash.toLong()) * GOLDEN_RATIO_CONJUGATE
    return spread - floor(spread)
}

/** Continuous spread hue in degrees; used by the THEME accent-gradient position and overrides. */
private fun nickHue(nick: String): Float = (nickHueSeed(nick) * 360.0).toFloat()

/**
 * Curated identity bins: 16 hues x 2 saturation/lightness tiers. Slots are spaced wider through
 * the perceptually compressed green/cyan (95..195) and blue (200..280) HSL bands, so any two
 * different bins stay clearly distinguishable; hash collisions become exact shares rather than
 * misleading near-misses. Continuous hue hashing put ~7% of unrelated nick pairs within a
 * confusable distance (deltaE < 12); the table cuts that by ~3x.
 */
internal val NICK_BIN_HUES = floatArrayOf(
    0f, 16f, 32f, 48f, 66f, 85f, 110f, 140f, 170f, 195f, 220f, 248f, 275f, 297f, 318f, 340f,
)
internal const val NICK_BIN_COUNT = 32

internal fun nickBin(nick: String): Int =
    (nickHueSeed(nick) * NICK_BIN_COUNT).toInt().coerceIn(0, NICK_BIN_COUNT - 1)

/** Bold tier is saturated and mode-deep; soft tier is a clearly separated pastel of the same hue. */
internal fun nickBinSaturation(palette: NickColorPalette, isDark: Boolean, bold: Boolean): Float =
    when (palette) {
        NickColorPalette.THEME,
        NickColorPalette.CLASSIC -> if (isDark) (if (bold) 0.72f else 0.48f) else (if (bold) 0.75f else 0.55f)
        NickColorPalette.VIVID -> if (isDark) (if (bold) 0.85f else 0.62f) else (if (bold) 0.88f else 0.68f)
    }

internal fun nickBinLightness(palette: NickColorPalette, isDark: Boolean, bold: Boolean): Float =
    when (palette) {
        NickColorPalette.THEME,
        NickColorPalette.CLASSIC -> if (isDark) (if (bold) 0.60f else 0.76f) else (if (bold) 0.38f else 0.52f)
        NickColorPalette.VIVID -> if (isDark) (if (bold) 0.56f else 0.72f) else (if (bold) 0.36f else 0.50f)
    }

// Continuous saturation/lightness for manual hue overrides (the picker remains a full wheel).
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

/** Palette hash color: curated bin for hash palettes; tiered accent-gradient lerp for THEME. Pure. */
fun paletteNickColor(
    nick: String,
    isDark: Boolean,
    palette: NickColorPalette,
    themeColors: List<Color> = emptyList(),
): Color {
    val bin = nickBin(nick)
    val bold = bin < NICK_BIN_HUES.size
    return if (palette == NickColorPalette.THEME) {
        // THEME stays constrained to the accent gradient; the soft tier adds a second
        // distinguishing axis since three accents can't carry much hue variance alone.
        themePaletteColor(nickHue(nick), themeColors, isDark, softTier = !bold)
    } else {
        hslColor(
            NICK_BIN_HUES[bin % NICK_BIN_HUES.size],
            nickBinSaturation(palette, isDark, bold),
            nickBinLightness(palette, isDark, bold),
        )
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
 * ensuring every generated color belongs to the current theme. The soft tier shifts the result
 * toward the mode's contrast pole, giving hash colors a second axis within the theme's gamut.
 */
private fun themePaletteColor(
    position: Float,
    themeColors: List<Color>,
    isDark: Boolean,
    softTier: Boolean = false,
): Color {
    val colors = themeColors.distinct()
    val base = when {
        colors.isEmpty() -> hslColor(
            position,
            paletteSaturation(NickColorPalette.CLASSIC, isDark),
            paletteLightness(NickColorPalette.CLASSIC, isDark),
        )
        colors.size == 1 -> colors.single()
        else -> {
            val scaled = position.coerceIn(0f, 359f) / 360f * colors.size
            val start = floor(scaled).toInt().coerceAtMost(colors.lastIndex)
            val amount = scaled - floor(scaled)
            lerp(colors[start], colors[(start + 1) % colors.size], amount)
        }
    }
    return if (softTier) lerp(base, if (isDark) Color.White else Color.Black, 0.22f) else base
}

/**
 * HSL hue (degrees, 0..360) of an already-resolved color. Lets the sprite ramp rebuild
 * saturation/lightness around whichever identity color the active palette produced (CLASSIC hash,
 * THEME lerp, or a manual override), so vivid sprites stay consistent with sender-name hues.
 */
internal fun colorHue(color: Color): Float {
    val max = maxOf(color.red, color.green, color.blue)
    val min = minOf(color.red, color.green, color.blue)
    val delta = max - min
    if (delta == 0f) return 0f
    val segment = when (max) {
        color.red -> (color.green - color.blue) / delta
        color.green -> (color.blue - color.red) / delta + 2f
        else -> (color.red - color.green) / delta + 4f
    }
    return (segment * 60f + 360f) % 360f
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
