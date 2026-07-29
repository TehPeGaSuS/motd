package io.github.trevarj.motd.data.prefs

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** App-owned color presets kept separate from the frozen settings contract. */
enum class ColorThemePreset {
    SYSTEM, LIGHT, DARK, AMOLED,
    AYU_DARK, AYU_LIGHT, AYU_MIRAGE,
    CATPPUCCIN_LATTE, CATPPUCCIN_MOCHA,
    DRACULA,
    EVERFOREST_DARK, EVERFOREST_LIGHT,
    GRUVBOX_DARK, GRUVBOX_LIGHT,
    KANAGAWA_DRAGON, KANAGAWA_LOTUS, KANAGAWA_WAVE,
    MODUS_OPERANDI, MODUS_VIVENDI,
    MODUS_OPERANDI_TINTED, MODUS_VIVENDI_TINTED,
    MODUS_OPERANDI_DEUTERANOPIA, MODUS_VIVENDI_DEUTERANOPIA,
    MODUS_OPERANDI_TRITANOPIA, MODUS_VIVENDI_TRITANOPIA,
    MONOKAI,
    NORD, NORD_LIGHT,
    ONE_DARK,
    ROSE_PINE, ROSE_PINE_DAWN, ROSE_PINE_MOON,
    SOLARIZED_DARK, SOLARIZED_LIGHT,
    TOKYO_NIGHT,
    ZENBURN,
}

val ColorThemePreset.isFixedPalette: Boolean
    get() = this !in setOf(
        ColorThemePreset.SYSTEM,
        ColorThemePreset.LIGHT,
        ColorThemePreset.DARK,
        ColorThemePreset.AMOLED,
    )

val ColorThemePreset.isDark: Boolean
    get() = when (this) {
        ColorThemePreset.SYSTEM -> false // resolved from the OS by MotdTheme
        ColorThemePreset.LIGHT,
        ColorThemePreset.AYU_LIGHT,
        ColorThemePreset.CATPPUCCIN_LATTE,
        ColorThemePreset.EVERFOREST_LIGHT,
        ColorThemePreset.GRUVBOX_LIGHT,
        ColorThemePreset.KANAGAWA_LOTUS,
        ColorThemePreset.MODUS_OPERANDI,
        ColorThemePreset.MODUS_OPERANDI_TINTED,
        ColorThemePreset.MODUS_OPERANDI_DEUTERANOPIA,
        ColorThemePreset.MODUS_OPERANDI_TRITANOPIA,
        ColorThemePreset.NORD_LIGHT,
        ColorThemePreset.ROSE_PINE_DAWN,
        ColorThemePreset.SOLARIZED_LIGHT,
        -> false
        else -> true
    }

/**
 * Opposite-mode sibling for optional system-mode switching. Dark-only and alternate-dark palettes
 * keep their fixed mode.
 */
val ColorThemePreset.systemPartner: ColorThemePreset?
    get() = when (this) {
        ColorThemePreset.LIGHT -> ColorThemePreset.DARK
        ColorThemePreset.DARK -> ColorThemePreset.LIGHT
        ColorThemePreset.AYU_LIGHT -> ColorThemePreset.AYU_DARK
        ColorThemePreset.AYU_DARK -> ColorThemePreset.AYU_LIGHT
        ColorThemePreset.CATPPUCCIN_LATTE -> ColorThemePreset.CATPPUCCIN_MOCHA
        ColorThemePreset.CATPPUCCIN_MOCHA -> ColorThemePreset.CATPPUCCIN_LATTE
        ColorThemePreset.EVERFOREST_LIGHT -> ColorThemePreset.EVERFOREST_DARK
        ColorThemePreset.EVERFOREST_DARK -> ColorThemePreset.EVERFOREST_LIGHT
        ColorThemePreset.GRUVBOX_LIGHT -> ColorThemePreset.GRUVBOX_DARK
        ColorThemePreset.GRUVBOX_DARK -> ColorThemePreset.GRUVBOX_LIGHT
        ColorThemePreset.KANAGAWA_LOTUS -> ColorThemePreset.KANAGAWA_WAVE
        ColorThemePreset.KANAGAWA_WAVE -> ColorThemePreset.KANAGAWA_LOTUS
        ColorThemePreset.MODUS_OPERANDI -> ColorThemePreset.MODUS_VIVENDI
        ColorThemePreset.MODUS_VIVENDI -> ColorThemePreset.MODUS_OPERANDI
        ColorThemePreset.MODUS_OPERANDI_TINTED -> ColorThemePreset.MODUS_VIVENDI_TINTED
        ColorThemePreset.MODUS_VIVENDI_TINTED -> ColorThemePreset.MODUS_OPERANDI_TINTED
        ColorThemePreset.MODUS_OPERANDI_DEUTERANOPIA -> ColorThemePreset.MODUS_VIVENDI_DEUTERANOPIA
        ColorThemePreset.MODUS_VIVENDI_DEUTERANOPIA -> ColorThemePreset.MODUS_OPERANDI_DEUTERANOPIA
        ColorThemePreset.MODUS_OPERANDI_TRITANOPIA -> ColorThemePreset.MODUS_VIVENDI_TRITANOPIA
        ColorThemePreset.MODUS_VIVENDI_TRITANOPIA -> ColorThemePreset.MODUS_OPERANDI_TRITANOPIA
        ColorThemePreset.NORD_LIGHT -> ColorThemePreset.NORD
        ColorThemePreset.NORD -> ColorThemePreset.NORD_LIGHT
        ColorThemePreset.ROSE_PINE_DAWN -> ColorThemePreset.ROSE_PINE
        ColorThemePreset.ROSE_PINE -> ColorThemePreset.ROSE_PINE_DAWN
        ColorThemePreset.SOLARIZED_LIGHT -> ColorThemePreset.SOLARIZED_DARK
        ColorThemePreset.SOLARIZED_DARK -> ColorThemePreset.SOLARIZED_LIGHT
        else -> null
    }

/** Resolve a paired family against the OS only when the user opted into system-mode following. */
fun resolveAutoPalette(
    themePreset: ColorThemePreset,
    followSystem: Boolean,
    systemDark: Boolean,
): ColorThemePreset {
    if (!followSystem) return themePreset
    val partner = themePreset.systemPartner ?: return themePreset
    return listOf(themePreset, partner).first { it.isDark == systemDark }
}

enum class ChatWallpaperPreset { NONE, CHATTER, CHANNELS, TERMINAL, RELAY, SIGNALS, PIXELS }

@Serializable
data class WallpaperSelection(
    val preset: ChatWallpaperPreset = ChatWallpaperPreset.CHATTER,
    val intensity: Int = DEFAULT_WALLPAPER_INTENSITY,
) {
    fun normalized() = copy(intensity = intensity.coerceIn(0, 100))
}

@Serializable
data class AppearanceConfig(
    val theme: ColorThemePreset = ColorThemePreset.SYSTEM,
    val wallpaper: WallpaperSelection = WallpaperSelection(),
    val uiFontScalePercent: Int = DEFAULT_FONT_SCALE_PERCENT,
    val conversationFontScalePercent: Int = DEFAULT_FONT_SCALE_PERCENT,
    val trueBlack: Boolean = false,
    val followSystem: Boolean = false,
)

interface AppearancePrefs {
    val config: Flow<AppearanceConfig>
    suspend fun setTheme(theme: ColorThemePreset)
    suspend fun setTrueBlack(enabled: Boolean)
    suspend fun setFollowSystem(enabled: Boolean)
    suspend fun setWallpaper(selection: WallpaperSelection)
    suspend fun setUiFontScale(percent: Int)
    suspend fun setConversationFontScale(percent: Int)
}

const val DEFAULT_WALLPAPER_INTENSITY = 80
const val MIN_FONT_SCALE_PERCENT = 80
const val MAX_FONT_SCALE_PERCENT = 140
const val FONT_SCALE_STEP_PERCENT = 5
const val DEFAULT_FONT_SCALE_PERCENT = 100

fun normalizeFontScalePercent(percent: Int): Int {
    val clamped = percent.coerceIn(MIN_FONT_SCALE_PERCENT, MAX_FONT_SCALE_PERCENT)
    return ((clamped + FONT_SCALE_STEP_PERCENT / 2) / FONT_SCALE_STEP_PERCENT) * FONT_SCALE_STEP_PERCENT
}
