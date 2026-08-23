package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.audio.AudioWaveform

private const val WAVEFORM_BAR_COUNT = 48

/** Compact audio timeline used by both received audio and staged voice-message previews. */
@Composable
fun WaveformScrubber(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    seed: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    bufferedValue: Float = 0f,
    waveform: AudioWaveform? = null,
) {
    val fraction = value.coerceIn(0f, 1f)
    val bufferedFraction = bufferedValue.coerceIn(fraction, 1f)
    val bars =
        remember(seed, waveform) {
            waveform?.normalized?.resampleBars(WAVEFORM_BAR_COUNT)
                ?: waveformBars(seed, WAVEFORM_BAR_COUNT)
        }
    val playedColor = MaterialTheme.colorScheme.primary
    val bufferedColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
    val remainingColor = MaterialTheme.colorScheme.outlineVariant
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(36.dp)
                .semantics {
                    contentDescription = "Audio position"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { target ->
                        if (!enabled) return@setProgress false
                        onValueChange(target.coerceIn(0f, 1f))
                        onValueChangeFinished()
                        true
                    }
                }.pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        fun update(x: Float) {
                            onValueChange((x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                        update(down.position.x)
                        down.consume()
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            update(change.position.x)
                            pressed = change.pressed
                            change.consume()
                        }
                        onValueChangeFinished()
                    }
                },
    ) {
        val step = size.width / bars.size
        val strokeWidth = (step * 0.5f).coerceAtLeast(2f)
        bars.forEachIndexed { index, heightFraction ->
            val x = step * (index + 0.5f)
            val barHeight = size.height * heightFraction
            val color =
                when {
                    !enabled -> disabledColor
                    x / size.width <= fraction -> playedColor
                    x / size.width <= bufferedFraction -> bufferedColor
                    else -> remainingColor
                }
            drawLine(
                color = color,
                start = Offset(x, (size.height - barHeight) / 2f),
                end = Offset(x, (size.height + barHeight) / 2f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

internal fun List<Float>.resampleBars(count: Int): List<Float> {
    if (isEmpty()) return emptyList()
    return List(count.coerceAtLeast(1)) { index ->
        val start = index * size / count
        val end = ((index + 1) * size / count).coerceAtLeast(start + 1)
        subList(start.coerceAtMost(lastIndex), end.coerceAtMost(size)).maxOrNull().orEmptyPeak()
    }
}

private fun Float?.orEmptyPeak(): Float = (this ?: 0f).coerceIn(0.08f, 1f)

internal fun waveformBars(
    seed: String,
    count: Int,
): List<Float> {
    var state = seed.hashCode().takeIf { it != 0 } ?: 0x6d2b79f5
    return List(count.coerceAtLeast(1)) { index ->
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        val noise = (state and Int.MAX_VALUE) / Int.MAX_VALUE.toFloat()
        val envelope = 0.65f + 0.35f * kotlin.math.abs(kotlin.math.sin((index + 1) * 0.72f))
        (0.2f + noise * 0.65f * envelope).coerceIn(0.2f, 0.9f)
    }
}
