package io.github.trevarj.motd.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Perceptual polar coordinates for identity colors: lightness, chroma, hue.
 *
 * HSL is what the palette used before, and it lies about both axes -- equal HSL lightness looks
 * wildly different between yellow and blue, and equal saturation carries far more color at some
 * hues than others. Snapping identity bins onto an HSL wheel is why the old palette had hues that
 * were visually adjacent and tiers that barely separated. Oklab is uniform enough that evenly
 * spaced hues actually look evenly spaced.
 */
internal data class Oklch(val lightness: Float, val chroma: Float, val hue: Float)

private fun toLinear(component: Float): Float =
    if (component <= 0.04045f) component / 12.92f
    else ((component + 0.055f) / 1.055f).pow(2.4f)

private fun toGamma(component: Float): Float =
    if (component <= 0.0031308f) component * 12.92f
    else 1.055f * component.coerceAtLeast(0f).pow(1f / 2.4f) - 0.055f

/** Linear sRGB -> Oklab (Ottosson's matrices). */
private fun linearToOklab(r: Float, g: Float, b: Float): FloatArray {
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    return floatArrayOf(
        0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
        1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
        0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
    )
}

/** Oklab -> linear sRGB; components may fall outside 0..1 when the color is out of gamut. */
private fun oklabToLinear(lightness: Float, a: Float, b: Float): FloatArray {
    val l = (lightness + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
    val m = (lightness - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
    val s = (lightness - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
    return floatArrayOf(
        4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
        -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
        -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s,
    )
}

internal fun Color.toOklch(): Oklch {
    val lab = linearToOklab(toLinear(red), toLinear(green), toLinear(blue))
    val hue = (atan2(lab[2], lab[1]) * 180f / PI.toFloat() + 360f) % 360f
    return Oklch(lab[0], hypot(lab[1], lab[2]), hue)
}

private const val GAMUT_SLACK = 0.0005f

private fun inGamut(lightness: Float, chroma: Float, hue: Float): Boolean {
    val radians = hue * PI.toFloat() / 180f
    val linear = oklabToLinear(lightness, chroma * cos(radians), chroma * sin(radians))
    return linear.all { it >= -GAMUT_SLACK && it <= 1f + GAMUT_SLACK }
}

/** Widest chroma sRGB can hold at this lightness and hue. */
internal fun maxChroma(lightness: Float, hue: Float): Float {
    if (lightness <= 0.001f || lightness >= 0.999f) return 0f
    var low = 0f
    var high = 0.42f
    repeat(18) {
        val mid = (low + high) / 2f
        if (inGamut(lightness, mid, hue)) low = mid else high = mid
    }
    return low
}

/**
 * Chroma as a fraction of what is reachable at this lightness and hue. This is the axis that
 * separates a muted palette from a loud one; absolute chroma mostly tracks which hues a theme
 * happened to pick, so it cannot describe a theme's character.
 */
internal fun Color.relativeChroma(): Float {
    val lch = toOklch()
    val ceiling = maxChroma(lch.lightness, lch.hue)
    return if (ceiling > 0f) (lch.chroma / ceiling).coerceIn(0f, 1f) else 0f
}

/** OkLCH -> [Color], reducing chroma to the gamut boundary rather than clipping (which shifts hue). */
internal fun oklchColor(lightness: Float, chroma: Float, hue: Float): Color {
    val l = lightness.coerceIn(0f, 1f)
    val h = ((hue % 360f) + 360f) % 360f
    val c = if (inGamut(l, chroma, h)) chroma else {
        var low = 0f
        var high = chroma
        repeat(18) {
            val mid = (low + high) / 2f
            if (inGamut(l, mid, h)) low = mid else high = mid
        }
        low
    }
    val radians = h * PI.toFloat() / 180f
    val linear = oklabToLinear(l, c * cos(radians), c * sin(radians))
    return Color(
        toGamma(linear[0]).coerceIn(0f, 1f),
        toGamma(linear[1]).coerceIn(0f, 1f),
        toGamma(linear[2]).coerceIn(0f, 1f),
    )
}

/** Same hue and relative chroma, re-fitted to the gamut at a new lightness. */
internal fun oklchColorAt(lightness: Float, relativeChroma: Float, hue: Float): Color =
    oklchColor(lightness, maxChroma(lightness, hue) * relativeChroma, hue)

