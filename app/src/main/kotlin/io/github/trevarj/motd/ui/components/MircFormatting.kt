package io.github.trevarj.motd.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

private const val CH_BOLD = '\u0002'
private const val CH_COLOR = '\u0003'
private const val CH_ITALIC = '\u001D'
private const val CH_UNDERLINE = '\u001F'
private const val CH_STRIKETHROUGH = '\u001E'
private const val CH_MONOSPACE = '\u0011'
private const val CH_REVERSE = '\u0016'
private const val CH_RESET = '\u000F'

/**
 * mIRC's original 16-color palette (codes 0-15), the only range every IRC client and bouncer is
 * guaranteed to render consistently. Codes 16-98 (HexChat's later "256-color" extension) are
 * parsed and stripped like any other color code so the digits never leak into the message body,
 * but are not mapped to a color: that extended palette isn't part of the original mIRC spec, isn't
 * consistently implemented across clients, and no source of truth for its exact RGB values is
 * reliable enough to hardcode here without risking silently wrong colors.
 */
private val MIRC_COLORS_16 =
    intArrayOf(
        0xFFFFFFFF.toInt(),
        0xFF000000.toInt(),
        0xFF00007F.toInt(),
        0xFF009300.toInt(),
        0xFFFF0000.toInt(),
        0xFF7F0000.toInt(),
        0xFF9C009C.toInt(),
        0xFFFC7F00.toInt(),
        0xFFFFFF00.toInt(),
        0xFF00FC00.toInt(),
        0xFF009393.toInt(),
        0xFF00FFFF.toInt(),
        0xFF0000FC.toInt(),
        0xFFFF00FF.toInt(),
        0xFF7F7F7F.toInt(),
        0xFFD2D2D2.toInt(),
    )

private fun mircColor(index: Int): Color? = MIRC_COLORS_16.getOrNull(index)?.let(::Color)

internal data class MircRun(
    val text: String,
    val style: SpanStyle,
)

internal fun mircFormattedText(text: String): AnnotatedString {
    if (text.none { it.code in 0x01..0x1F }) return AnnotatedString(text)
    return buildAnnotatedString {
        for (run in parseMircFormatting(text)) withStyle(run.style) { append(run.text) }
    }
}

/**
 * Splits raw IRC text on mIRC formatting control codes (bold/italic/underline/strikethrough/
 * monospace/reverse/color/reset) into styled runs with the codes themselves stripped. A run
 * carries the SpanStyle that was active when its text was appended; unset properties are left
 * null/Unspecified so the caller's own base style shows through on merge.
 */
internal fun parseMircFormatting(text: String): List<MircRun> {
    if (text.none { it.code in 0x01..0x1F }) return listOf(MircRun(text, SpanStyle()))

    val runs = mutableListOf<MircRun>()
    val sb = StringBuilder()
    var bold = false
    var italic = false
    var underline = false
    var strikethrough = false
    var monospace = false
    var reversed = false
    var fg: Int? = null
    var bg: Int? = null

    fun currentStyle(): SpanStyle {
        val effectiveFg = if (reversed) bg else fg
        val effectiveBg = if (reversed) fg else bg
        val decorations =
            buildList {
                if (underline) add(TextDecoration.Underline)
                if (strikethrough) add(TextDecoration.LineThrough)
            }
        return SpanStyle(
            color = effectiveFg?.let { mircColor(it) } ?: Color.Unspecified,
            background = effectiveBg?.let { mircColor(it) } ?: Color.Unspecified,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }

    fun flush() {
        if (sb.isNotEmpty()) {
            runs += MircRun(sb.toString(), currentStyle())
            sb.clear()
        }
    }

    fun readDigits(
        source: String,
        from: Int,
        maxDigits: Int,
    ): Pair<Int?, Int> {
        var i = from
        while (i < source.length && source[i].isDigit() && i - from < maxDigits) i++
        return if (i > from) source.substring(from, i).toInt() to i else null to from
    }

    var i = 0
    while (i < text.length) {
        when (text[i]) {
            CH_BOLD -> {
                flush()
                bold = !bold
                i++
            }

            CH_ITALIC -> {
                flush()
                italic = !italic
                i++
            }

            CH_UNDERLINE -> {
                flush()
                underline = !underline
                i++
            }

            CH_STRIKETHROUGH -> {
                flush()
                strikethrough = !strikethrough
                i++
            }

            CH_MONOSPACE -> {
                flush()
                monospace = !monospace
                i++
            }

            CH_REVERSE -> {
                flush()
                reversed = !reversed
                i++
            }

            CH_RESET -> {
                flush()
                bold = false
                italic = false
                underline = false
                strikethrough = false
                monospace = false
                reversed = false
                fg = null
                bg = null
                i++
            }

            CH_COLOR -> {
                flush()
                i++
                val (newFg, afterFg) = readDigits(text, i, 2)
                i = afterFg
                var newBg: Int? = null
                if (i < text.length && text[i] == ',') {
                    val (bgVal, afterBg) = readDigits(text, i + 1, 2)
                    if (bgVal != null) {
                        newBg = bgVal
                        i = afterBg
                    }
                }
                if (newFg == null && newBg == null) {
                    fg = null
                    bg = null
                } else {
                    if (newFg != null) fg = newFg
                    if (newBg != null) bg = newBg
                }
            }

            else -> {
                sb.append(text[i])
                i++
            }
        }
    }
    flush()
    return runs
}
