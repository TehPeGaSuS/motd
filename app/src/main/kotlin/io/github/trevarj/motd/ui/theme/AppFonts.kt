package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.FontChoice
import java.io.File

/**
 * Variable-weight JetBrains Mono, built from the two bundled TTFs (upright + italic). Each entry
 * pins a static weight instance via [FontVariation.Settings] rather than shipping separate static
 * files.
 */
val JetBrainsMonoFamily: FontFamily = FontFamily(
    Font(
        R.font.jetbrains_mono_wght,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.jetbrains_mono_wght,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.jetbrains_mono_wght,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
    Font(
        R.font.jetbrains_mono_italic_wght,
        weight = FontWeight.Normal,
        style = FontStyle.Italic,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.jetbrains_mono_italic_wght,
        weight = FontWeight.Medium,
        style = FontStyle.Italic,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.jetbrains_mono_italic_wght,
        weight = FontWeight.Bold,
        style = FontStyle.Italic,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

/**
 * Resolve a user's font choice to a Compose [FontFamily]; null keeps the platform default.
 * CUSTOM has no bundled resource — it always resolves through [rememberAppFontFamily] instead,
 * which is why it falls through to null (system) here.
 */
fun FontChoice.fontFamily(): FontFamily? = when (this) {
    FontChoice.SYSTEM -> null
    FontChoice.SANS -> FontFamily.SansSerif
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.MONOSPACE -> FontFamily.Monospace
    FontChoice.JETBRAINS_MONO -> JetBrainsMonoFamily
    FontChoice.CUSTOM -> null
}

/**
 * Composable font resolution that additionally handles CUSTOM by loading [customFontFile] from
 * disk. Remembered on the file's path and last-modified time so a re-import (same path, new bytes)
 * invalidates the cached [FontFamily]. A missing file falls back to the platform default, which
 * covers a restored backup whose font binary never traveled (see ConfigurationBackup).
 */
@Composable
fun rememberAppFontFamily(choice: FontChoice, customFontFile: File?): FontFamily? {
    if (choice != FontChoice.CUSTOM) return choice.fontFamily()
    val file = customFontFile?.takeIf { it.exists() } ?: return null
    return remember(file.absolutePath, file.lastModified()) {
        FontFamily(Font(file))
    }
}

/** Apply [family] to every text role, leaving size/line-height/tracking untouched. Null is a no-op. */
internal fun Typography.withFontFamily(family: FontFamily?): Typography {
    if (family == null) return this
    return copy(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family),
    )
}
