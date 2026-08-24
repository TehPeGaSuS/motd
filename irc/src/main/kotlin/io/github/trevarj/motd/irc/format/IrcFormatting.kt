package io.github.trevarj.motd.irc.format

const val IRC_BOLD: Char = '\u0002'
const val IRC_COLOR: Char = '\u0003'
const val IRC_HEX_COLOR: Char = '\u0004'
const val IRC_MONOSPACE: Char = '\u0011'
const val IRC_RESET: Char = '\u000F'
const val IRC_REVERSE: Char = '\u0016'
const val IRC_ITALIC: Char = '\u001D'
const val IRC_STRIKETHROUGH: Char = '\u001E'
const val IRC_UNDERLINE: Char = '\u001F'

enum class IrcTextStyle(
    val control: Char,
) {
    BOLD(IRC_BOLD),
    ITALIC(IRC_ITALIC),
    UNDERLINE(IRC_UNDERLINE),
    STRIKETHROUGH(IRC_STRIKETHROUGH),
    MONOSPACE(IRC_MONOSPACE),
}

sealed interface IrcColor {
    data class Numeric(
        val code: Int,
    ) : IrcColor {
        init {
            require(code in 0..98)
        }
    }

    data class Hex(
        val rgb: Int,
    ) : IrcColor {
        init {
            require(rgb in 0..0xFFFFFF)
        }
    }
}

data class IrcFormatState(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val monospace: Boolean = false,
    val reverse: Boolean = false,
    val foreground: IrcColor? = null,
    val background: IrcColor? = null,
) {
    val isDefault: Boolean
        get() = this == IrcFormatState()

    fun enabled(style: IrcTextStyle): Boolean =
        when (style) {
            IrcTextStyle.BOLD -> bold
            IrcTextStyle.ITALIC -> italic
            IrcTextStyle.UNDERLINE -> underline
            IrcTextStyle.STRIKETHROUGH -> strikethrough
            IrcTextStyle.MONOSPACE -> monospace
        }

    fun with(
        style: IrcTextStyle,
        enabled: Boolean,
    ): IrcFormatState =
        when (style) {
            IrcTextStyle.BOLD -> copy(bold = enabled)
            IrcTextStyle.ITALIC -> copy(italic = enabled)
            IrcTextStyle.UNDERLINE -> copy(underline = enabled)
            IrcTextStyle.STRIKETHROUGH -> copy(strikethrough = enabled)
            IrcTextStyle.MONOSPACE -> copy(monospace = enabled)
        }
}

data class IrcStyleRun(
    val start: Int,
    val end: Int,
    val state: IrcFormatState,
)

data class IrcFormattedText(
    val rawText: String,
    val visibleText: String,
    val runs: List<IrcStyleRun>,
    val activeState: IrcFormatState,
    val rawToVisible: IntArray,
    val visibleToRaw: IntArray,
    internal val characterStates: List<IrcFormatState>,
) {
    fun rawOffset(visibleOffset: Int): Int = visibleToRaw[visibleOffset.coerceIn(0, visibleText.length)]

    fun visibleOffset(rawOffset: Int): Int = rawToVisible[rawOffset.coerceIn(0, rawText.length)]

    fun stateAtVisible(offset: Int): IrcFormatState = characterStates.getOrNull(offset.coerceAtMost(visibleText.lastIndex)) ?: activeState
}

/** Parse IRC formatting controls without consuming malformed color arguments. */
fun parseIrcFormatting(raw: String): IrcFormattedText {
    val visible = StringBuilder(raw.length)
    val states = ArrayList<IrcFormatState>(raw.length)
    val rawToVisible = IntArray(raw.length + 1)
    val visibleToRaw = ArrayList<Int>(raw.length + 1).apply { add(0) }
    var state = IrcFormatState()
    var i = 0

    fun markHidden(until: Int) {
        while (i < until) {
            i++
            rawToVisible[i] = visible.length
        }
        visibleToRaw[visible.length] = i
    }

    fun toggle(style: IrcTextStyle) {
        state = state.with(style, !state.enabled(style))
        markHidden(i + 1)
    }

    while (i < raw.length) {
        rawToVisible[i] = visible.length
        when (raw[i]) {
            IRC_BOLD -> {
                toggle(IrcTextStyle.BOLD)
            }

            IRC_ITALIC -> {
                toggle(IrcTextStyle.ITALIC)
            }

            IRC_UNDERLINE -> {
                toggle(IrcTextStyle.UNDERLINE)
            }

            IRC_STRIKETHROUGH -> {
                toggle(IrcTextStyle.STRIKETHROUGH)
            }

            IRC_MONOSPACE -> {
                toggle(IrcTextStyle.MONOSPACE)
            }

            IRC_REVERSE -> {
                state = state.copy(reverse = !state.reverse)
                markHidden(i + 1)
            }

            IRC_RESET -> {
                state = IrcFormatState()
                markHidden(i + 1)
            }

            IRC_COLOR -> {
                val parsed = parseNumericColor(raw, i + 1)
                state =
                    if (parsed.foregroundPresent) {
                        state.copy(
                            foreground = parsed.foreground,
                            background = if (parsed.backgroundPresent) parsed.background else state.background,
                        )
                    } else {
                        state.copy(foreground = null, background = null)
                    }
                markHidden(parsed.end)
            }

            IRC_HEX_COLOR -> {
                val parsed = parseHexColor(raw, i + 1)
                state =
                    if (parsed.foregroundPresent) {
                        state.copy(
                            foreground = parsed.foreground,
                            background = if (parsed.backgroundPresent) parsed.background else state.background,
                        )
                    } else {
                        state.copy(foreground = null, background = null)
                    }
                markHidden(parsed.end)
            }

            else -> {
                val codePoint = raw.codePointAt(i)
                val count = Character.charCount(codePoint)
                repeat(count) { offset ->
                    visible.append(raw[i + offset])
                    states += state
                    rawToVisible[i + offset + 1] = visible.length
                    visibleToRaw += i + offset + 1
                }
                i += count
            }
        }
    }
    rawToVisible[raw.length] = visible.length
    visibleToRaw[visible.length] = raw.length
    return IrcFormattedText(
        rawText = raw,
        visibleText = visible.toString(),
        runs = buildRuns(states),
        activeState = state,
        rawToVisible = rawToVisible,
        visibleToRaw = visibleToRaw.toIntArray(),
        characterStates = states,
    )
}

private data class ParsedColor(
    val foregroundPresent: Boolean,
    val foreground: IrcColor?,
    val backgroundPresent: Boolean,
    val background: IrcColor?,
    val end: Int,
)

private fun parseNumericColor(
    text: String,
    start: Int,
): ParsedColor {
    var end = start
    while (end < text.length && text[end].isDigit() && end - start < 2) end++
    if (end == start) return ParsedColor(false, null, false, null, start)
    val foregroundCode = text.substring(start, end).toInt()
    var backgroundPresent = false
    var background: IrcColor? = null
    if (end < text.length && text[end] == ',') {
        var backgroundEnd = end + 1
        while (backgroundEnd < text.length && text[backgroundEnd].isDigit() && backgroundEnd - end <= 2) backgroundEnd++
        if (backgroundEnd > end + 1) {
            backgroundPresent = true
            background = numericColor(text.substring(end + 1, backgroundEnd).toInt())
            end = backgroundEnd
        }
    }
    return ParsedColor(true, numericColor(foregroundCode), backgroundPresent, background, end)
}

private fun numericColor(code: Int): IrcColor? = code.takeIf { it in 0..98 }?.let(IrcColor::Numeric)

private fun parseHexColor(
    text: String,
    start: Int,
): ParsedColor {
    val foregroundEnd = start + 6
    if (foregroundEnd > text.length || text.substring(start, foregroundEnd).any { !it.isHexDigit() }) {
        return ParsedColor(false, null, false, null, start)
    }
    var end = foregroundEnd
    var backgroundPresent = false
    var background: IrcColor? = null
    if (end < text.length && text[end] == ',') {
        val backgroundEnd = end + 7
        if (backgroundEnd <= text.length && text.substring(end + 1, backgroundEnd).all(Char::isHexDigit)) {
            backgroundPresent = true
            background = IrcColor.Hex(text.substring(end + 1, backgroundEnd).toInt(16))
            end = backgroundEnd
        }
    }
    return ParsedColor(
        true,
        IrcColor.Hex(text.substring(start, foregroundEnd).toInt(16)),
        backgroundPresent,
        background,
        end,
    )
}

private fun Char.isHexDigit(): Boolean = isDigit() || lowercaseChar() in 'a'..'f'

private fun buildRuns(states: List<IrcFormatState>): List<IrcStyleRun> {
    if (states.isEmpty()) return emptyList()
    val runs = ArrayList<IrcStyleRun>()
    var start = 0
    var state = states.first()
    for (index in 1 until states.size) {
        if (states[index] != state) {
            runs += IrcStyleRun(start, index, state)
            start = index
            state = states[index]
        }
    }
    runs += IrcStyleRun(start, states.size, state)
    return runs
}

data class IrcFormatEdit(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int = selectionStart,
)

fun toggleIrcStyle(
    raw: String,
    selectionStart: Int,
    selectionEnd: Int,
    style: IrcTextStyle,
): IrcFormatEdit {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, raw.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(start, raw.length)
    if (start == end) {
        val text = raw.substring(0, start) + style.control + style.control + raw.substring(end)
        return IrcFormatEdit(text, start + 1)
    }
    return rewriteSelection(raw, start, end) { selected ->
        val enabled = selected.isNotEmpty() && selected.all { it.enabled(style) }
        selected.map { it.with(style, !enabled) }
    }
}

fun applyIrcColors(
    raw: String,
    selectionStart: Int,
    selectionEnd: Int,
    foreground: Int?,
    background: Int?,
): IrcFormatEdit {
    require(foreground == null || foreground in 0..98)
    require(background == null || background in 0..98)
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, raw.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(start, raw.length)
    val readableForeground = foreground ?: background?.let { readableForeground(it) }
    if (start == end) {
        val control = numericColorControl(readableForeground, background)
        val closing = IRC_COLOR.toString()
        val text = raw.substring(0, start) + control + closing + raw.substring(end)
        return IrcFormatEdit(text, start + control.length)
    }
    return rewriteSelection(raw, start, end) { selected ->
        selected.map {
            it.copy(
                foreground = readableForeground?.let(IrcColor::Numeric),
                background = background?.let(IrcColor::Numeric),
            )
        }
    }
}

fun clearIrcFormatting(
    raw: String,
    selectionStart: Int,
    selectionEnd: Int,
): IrcFormatEdit {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, raw.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(start, raw.length)
    if (start == end) {
        val text = raw.substring(0, start) + IRC_RESET + raw.substring(start)
        return IrcFormatEdit(text, start + 1)
    }
    return rewriteSelection(raw, start, end) { selected -> selected.map { IrcFormatState() } }
}

private fun rewriteSelection(
    raw: String,
    rawStart: Int,
    rawEnd: Int,
    change: (List<IrcFormatState>) -> List<IrcFormatState>,
): IrcFormatEdit {
    val parsed = parseIrcFormatting(raw)
    val visibleStart = parsed.visibleOffset(rawStart)
    val visibleEnd = parsed.visibleOffset(rawEnd)
    if (visibleStart == visibleEnd) return IrcFormatEdit(raw, rawStart, rawEnd)
    val states = parsed.characterStates.toMutableList()
    val replacement = change(states.subList(visibleStart, visibleEnd))
    for (index in replacement.indices) states[visibleStart + index] = replacement[index]
    val serialized = serializeVisibleIrc(parsed.visibleText, states)
    val reparsed = parseIrcFormatting(serialized)
    return IrcFormatEdit(
        text = serialized,
        selectionStart = reparsed.rawOffset(visibleStart),
        selectionEnd = reparsed.rawOffset(visibleEnd),
    )
}

internal fun serializeVisibleIrc(
    visible: String,
    states: List<IrcFormatState>,
): String =
    buildString(visible.length) {
        var previous = IrcFormatState()
        visible.indices.forEach { index ->
            val next = states[index]
            append(stateTransition(previous, next))
            append(visible[index])
            previous = next
        }
        if (!previous.isDefault) append(IRC_RESET)
    }

fun restoreIrcState(state: IrcFormatState): String = stateTransition(IrcFormatState(), state)

private fun stateTransition(
    from: IrcFormatState,
    to: IrcFormatState,
): String {
    if (!from.isDefault && to.isDefault) return IRC_RESET.toString()
    return buildString {
        if (from.bold != to.bold) append(IRC_BOLD)
        if (from.italic != to.italic) append(IRC_ITALIC)
        if (from.underline != to.underline) append(IRC_UNDERLINE)
        if (from.strikethrough != to.strikethrough) append(IRC_STRIKETHROUGH)
        if (from.monospace != to.monospace) append(IRC_MONOSPACE)
        if (from.reverse != to.reverse) append(IRC_REVERSE)
        if (from.foreground != to.foreground || from.background != to.background) {
            if (from.background != null && to.background == null && to.foreground != null) append(IRC_COLOR)
            append(colorTransition(to.foreground, to.background))
        }
    }
}

private fun colorTransition(
    foreground: IrcColor?,
    background: IrcColor?,
): String =
    when {
        foreground == null && background == null -> {
            IRC_COLOR.toString()
        }

        foreground is IrcColor.Hex && (background == null || background is IrcColor.Hex) -> {
            buildString {
                append(IRC_HEX_COLOR)
                append(foreground.rgb.toString(16).padStart(6, '0'))
                background?.let { append(',').append(it.rgb.toString(16).padStart(6, '0')) }
            }
        }

        else -> {
            val fg = (foreground as? IrcColor.Numeric)?.code ?: readableForeground((background as IrcColor.Numeric).code)
            numericColorControl(fg, (background as? IrcColor.Numeric)?.code)
        }
    }

private fun numericColorControl(
    foreground: Int?,
    background: Int?,
): String =
    buildString {
        append(IRC_COLOR)
        foreground?.let { append(it.toString().padStart(2, '0')) }
        background?.let { append(',').append(it.toString().padStart(2, '0')) }
    }

/** Black on light colors, white on dark colors; always emits valid background syntax. */
fun readableForeground(background: Int): Int = if (background in LIGHT_NUMERIC_COLORS) 1 else 0

private val LIGHT_NUMERIC_COLORS = setOf(0, 7, 8, 9, 11, 15, 42, 43, 44, 45, 47, 48, 49, 50, 51, 52, 53, 54)

/**
 * Split one formatted payload into independently renderable UTF-8 components. Each component starts
 * from default state and closes with reset, so formatting cannot leak across physical IRC messages.
 */
fun splitIrcFormattedUtf8(
    raw: String,
    maxBytes: Int,
): List<String> {
    require(maxBytes > 0) { "maxBytes must be positive" }
    if (raw.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(raw)
    val parsed = parseIrcFormatting(raw)
    return splitVisibleUtf8(parsed.visibleText, parsed.characterStates, maxBytes)
}

/** Split physical lines while carrying active formatting across newline boundaries. */
fun splitIrcFormattedLinesUtf8(
    raw: String,
    maxBytes: Int,
): List<List<String>> {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val parsed = parseIrcFormatting(raw.replace("\r\n", "\n").replace('\r', '\n'))
    val lines = ArrayList<List<String>>()
    var start = 0
    for (index in 0..parsed.visibleText.length) {
        if (index == parsed.visibleText.length || parsed.visibleText[index] == '\n') {
            lines +=
                if (index == start) {
                    listOf("")
                } else {
                    splitVisibleUtf8(
                        parsed.visibleText.substring(start, index),
                        parsed.characterStates.subList(start, index),
                        maxBytes,
                    )
                }
            start = index + 1
        }
    }
    return lines
}

private fun splitVisibleUtf8(
    visible: String,
    states: List<IrcFormatState>,
    maxBytes: Int,
): List<String> {
    if (visible.isEmpty()) return emptyList()
    val serialized = serializeVisibleIrc(visible, states)
    if (serialized.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(serialized)
    val result = ArrayList<String>()
    var start = 0
    while (start < visible.length) {
        var end = start
        var lastSpaceEnd = -1
        var candidate = ""
        while (end < visible.length) {
            val next = end + Character.charCount(visible.codePointAt(end))
            val nextSerialized = serializeVisibleIrc(visible.substring(start, next), states.subList(start, next))
            if (nextSerialized.toByteArray(Charsets.UTF_8).size > maxBytes) break
            candidate = nextSerialized
            end = next
            if (visible[end - 1] == ' ') lastSpaceEnd = end
        }
        require(end > start) { "maxBytes is smaller than one formatted UTF-8 code point" }
        if (end < visible.length && lastSpaceEnd > start) {
            end = lastSpaceEnd
            candidate = serializeVisibleIrc(visible.substring(start, end), states.subList(start, end))
        }
        result += candidate
        start = end
    }
    return result
}

fun ircStateAtRawOffset(
    raw: String,
    rawOffset: Int,
): IrcFormatState = parseIrcFormatting(raw.substring(0, rawOffset.coerceIn(0, raw.length))).activeState

fun isIrcStyleSelected(
    raw: String,
    selectionStart: Int,
    selectionEnd: Int,
    style: IrcTextStyle,
): Boolean {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, raw.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(start, raw.length)
    if (start == end) return ircStateAtRawOffset(raw, start).enabled(style)
    val parsed = parseIrcFormatting(raw)
    val visibleStart = parsed.visibleOffset(start)
    val visibleEnd = parsed.visibleOffset(end)
    return visibleStart < visibleEnd &&
        parsed.characterStates.subList(visibleStart, visibleEnd).all { it.enabled(style) }
}

fun plainIrcText(raw: String): String = parseIrcFormatting(raw).visibleText
