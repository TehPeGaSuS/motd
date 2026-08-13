package io.github.trevarj.motd.avatar

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.ui.theme.NICK_BIN_COUNT
import io.github.trevarj.motd.ui.theme.NICK_BIN_HUES
import io.github.trevarj.motd.ui.theme.nickBinLightness
import io.github.trevarj.motd.ui.theme.nickBinSaturation
import kotlin.math.abs
import kotlin.math.floor

private const val AVATAR_SIZE_PX = 64

/**
 * System UI renders notification people from bitmaps rather than the app's Compose tree. Keep the
 * three selectable avatar treatments recognizable at notification scale without loading remote
 * content while posting an incoming message.
 */
internal fun notificationAvatarIcon(context: Context, name: String, style: AvatarStyle): IconCompat =
    IconCompat.createWithBitmap(notificationAvatarBitmap(context, name, style))

internal fun notificationAvatarBitmap(context: Context, name: String, style: AvatarStyle): Bitmap {
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    val accent = notificationNickColor(name, dark)
    return createBitmap(AVATAR_SIZE_PX, AVATAR_SIZE_PX).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (style) {
            AvatarStyle.MONOGRAM -> drawMonogram(canvas, paint, name, accent, dark)
            AvatarStyle.INITIALS -> drawInitials(canvas, paint, name, accent)
            AvatarStyle.IRC_SPRITE -> drawIrcSprite(canvas, paint, name, dark)
        }
    }
}

private fun drawMonogram(canvas: Canvas, paint: Paint, name: String, accent: Int, dark: Boolean) {
    val base = if (dark) Color.rgb(37, 39, 43) else Color.rgb(243, 241, 248)
    val fill = ColorUtils.blendARGB(base, accent, if (dark) 0.26f else 0.18f)
    paint.style = Paint.Style.FILL
    paint.color = fill
    canvas.drawCircle(32f, 32f, 31f, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2f
    paint.color = ColorUtils.setAlphaComponent(accent, 102)
    canvas.drawCircle(32f, 32f, 30f, paint)
    drawCenteredText(canvas, paint, avatarInitials(name).take(1), ColorUtils.blendARGB(accent, if (dark) Color.WHITE else Color.BLACK, 0.30f), 27f)
}

private fun drawInitials(canvas: Canvas, paint: Paint, name: String, accent: Int) {
    paint.style = Paint.Style.FILL
    paint.color = accent
    canvas.drawCircle(32f, 32f, 31f, paint)
    drawCenteredText(canvas, paint, avatarInitials(name), onColorFor(accent), 23f)
}

private fun drawIrcSprite(canvas: Canvas, paint: Paint, name: String, dark: Boolean) {
    // Mirrors SpritePalette.from: vivid shadow/mid/highlight ramp from the nick hue over a neutral
    // disc (one container step lighter in dark so the sprite doesn't sink into the shade tray).
    val hue = notificationNickHue(name)
    val base = if (dark) Color.rgb(54, 52, 59) else Color.rgb(243, 241, 248)
    val panel = if (dark) Color.rgb(18, 20, 23) else Color.WHITE
    val shade = hslToColor(hue, if (dark) 0.62f else 0.58f, if (dark) 0.30f else 0.72f)
    val mid = hslToColor(hue, if (dark) 0.72f else 0.70f, if (dark) 0.48f else 0.50f)
    val highlight = hslToColor(hue, if (dark) 0.80f else 0.72f, if (dark) 0.62f else 0.42f)
    val ink = onColorFor(highlight)
    val variant = stableVariant(name)

    paint.style = Paint.Style.FILL
    paint.color = base
    canvas.drawCircle(32f, 32f, 31f, paint)

    paint.color = shade
    canvas.drawRoundRect(RectF(15f, 39f, 49f, 61f), 9f, 9f, paint)
    paint.color = mid
    canvas.drawRoundRect(RectF(20f, 43f, 44f, 59f), 6f, 6f, paint)

    paint.color = highlight
    when (variant % 3) {
        0 -> canvas.drawRoundRect(RectF(16f, 14f, 48f, 42f), 10f, 10f, paint)
        1 -> canvas.drawRoundRect(RectF(13f, 16f, 51f, 41f), 7f, 7f, paint)
        else -> canvas.drawCircle(32f, 28f, 16f, paint)
    }

    paint.color = panel
    when ((variant / 3) % 3) {
        0 -> canvas.drawRoundRect(RectF(21f, 24f, 43f, 31f), 3f, 3f, paint)
        1 -> {
            canvas.drawRoundRect(RectF(18f, 24f, 29f, 31f), 3f, 3f, paint)
            canvas.drawRoundRect(RectF(35f, 24f, 46f, 31f), 3f, 3f, paint)
        }
        else -> {
            canvas.drawCircle(24f, 27.5f, 4.5f, paint)
            canvas.drawCircle(40f, 27.5f, 4.5f, paint)
        }
    }
    paint.color = mid
    paint.strokeWidth = 1.5f
    canvas.drawLine(23f, 27.5f, 41f, 27.5f, paint)

    when ((variant / 9) % 3) {
        0 -> {
            paint.color = ink
            paint.strokeWidth = 2f
            canvas.drawLine(32f, 14f, 32f, 7f, paint)
            paint.color = mid
            canvas.drawCircle(32f, 6f, 2.5f, paint)
        }
        1 -> {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = ink
            canvas.drawArc(RectF(13f, 10f, 51f, 44f), 205f, 130f, false, paint)
            paint.style = Paint.Style.FILL
        }
        else -> {
            paint.color = ink
            canvas.drawRoundRect(RectF(18f, 13f, 46f, 18f), 2.5f, 2.5f, paint)
        }
    }

    paint.color = ink
    canvas.drawRoundRect(RectF(29f, 47f, 35f, 53f), 1.5f, 1.5f, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2f
    paint.color = ColorUtils.setAlphaComponent(mid, 133)
    canvas.drawCircle(32f, 32f, 30f, paint)
}

private fun drawCenteredText(canvas: Canvas, paint: Paint, text: String, color: Int, size: Float) {
    paint.style = Paint.Style.FILL
    paint.color = color
    paint.textSize = size
    paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    paint.textAlign = Paint.Align.CENTER
    val baseline = 32f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(text, 32f, baseline, paint)
}

private fun avatarInitials(name: String): String {
    val stripped = name.trimStart('#', '&', '@', '+', '~', '%', '!').ifEmpty { name }
    val words = stripped.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    val chars = when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        stripped.length >= 2 -> stripped.take(2)
        else -> stripped.take(1).ifEmpty { "?" }
    }
    return chars.uppercase()
}

// Mirrors NickColor's CLASSIC curated bins (16 hues x 2 tiers, golden-ratio-multiplied seed) so
// notification people match their in-app identity colors.
private fun notificationNickBin(name: String): Int {
    var hash = 0
    for (char in canonicalAvatarNick(name)) hash = hash * 31 + char.code
    val spread = abs(hash.toLong()) * 0.618033988749895
    return ((spread - floor(spread)) * NICK_BIN_COUNT).toInt().coerceIn(0, NICK_BIN_COUNT - 1)
}

private fun notificationNickHue(name: String): Float =
    NICK_BIN_HUES[notificationNickBin(name) % NICK_BIN_HUES.size]

private fun notificationNickColor(name: String, dark: Boolean): Int {
    val bin = notificationNickBin(name)
    val bold = bin < NICK_BIN_HUES.size
    return hslToColor(
        notificationNickHue(name),
        nickBinSaturation(NickColorPalette.CLASSIC, dark, bold),
        nickBinLightness(NickColorPalette.CLASSIC, dark, bold),
    )
}

private fun hslToColor(hue: Float, saturation: Float, lightness: Float): Int =
    ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness))

private fun stableVariant(name: String): Int = canonicalAvatarNick(name).fold(0) { hash, char ->
    hash * 31 + char.code
} and Int.MAX_VALUE

private fun onColorFor(color: Int): Int =
    if (ColorUtils.calculateLuminance(color) < 0.5) Color.WHITE else Color.BLACK
