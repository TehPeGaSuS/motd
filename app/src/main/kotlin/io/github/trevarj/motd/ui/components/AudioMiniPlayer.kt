package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.audio.AudioPlaybackOrigin
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.contextLabel
import io.github.trevarj.motd.audio.formatAudioDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioMiniPlayer(
    state: AudioPlaybackState,
    onToggle: () -> Unit,
    onCancelLoading: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenOrigin: (AudioPlaybackOrigin) -> Unit,
    modifier: Modifier = Modifier,
    includeNetwork: Boolean = false,
) {
    val attachment = state.attachment ?: return
    if (state.activeId == null) return
    var showDetails by remember(state.activeId) { mutableStateOf(false) }
    val duration = state.durationMs ?: attachment.durationMs
    val played = duration?.takeIf { it > 0 }?.let { state.positionMs.toFloat() / it } ?: 0f
    val buffered = state.loadingFraction
        ?: duration?.takeIf { it > 0 }?.let { state.bufferedMs.toFloat() / it }
        ?: 0f
    val originLabel = state.origin?.contextLabel(state.networkName, includeNetwork)
    val time = "${formatAudioDuration(state.positionMs)} / ${formatAudioDuration(duration)}"

    Surface(
        modifier = modifier.fillMaxWidth().height(MINI_PLAYER_HEIGHT).testTag("audio_mini_player"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = when {
                        state.loading -> onCancelLoading
                        state.error != null -> onRetry
                        else -> onToggle
                    },
                    modifier = Modifier.size(44.dp).testTag(
                        when {
                            state.loading -> "audio_mini_cancel_loading"
                            state.error != null -> "audio_mini_retry"
                            else -> "audio_mini_toggle"
                        },
                    ),
                ) {
                    when {
                        state.loading -> state.loadingFraction?.let { fraction ->
                            CircularProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                            )
                        } ?: CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                        )
                        state.error != null -> Icon(Icons.Filled.Refresh, "Retry audio")
                        state.playing -> Icon(Icons.Filled.Pause, "Pause audio")
                        else -> Icon(Icons.Filled.PlayArrow, "Play audio")
                    }
                }
                Spacer(Modifier.width(4.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = { state.origin?.let(onOpenOrigin) },
                            onLongClick = { showDetails = true },
                        )
                        .testTag("audio_mini_context"),
                ) {
                    Text(
                        text = if (attachment.voice) originLabel ?: attachment.title else attachment.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.error?.let { "Couldn’t play · $it" }
                            ?: if (attachment.voice) time else listOfNotNull(originLabel, time).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.error == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp).testTag("audio_mini_close")) {
                    Icon(Icons.Filled.Close, "Close audio player")
                }
            }
            BufferedProgressScrubber(
                played = played,
                buffered = buffered,
                enabled = duration != null && duration > 0 && !state.loading,
                onSeek = { fraction -> duration?.let { onSeek((fraction * it).toLong()) } },
                modifier = Modifier.fillMaxWidth().testTag("audio_mini_scrubber"),
            )
        }
    }

    if (showDetails) {
        ModalBottomSheet(onDismissRequest = { showDetails = false }) {
            AudioDetailsSheet(
                attachment = attachment,
                origin = state.origin,
            )
        }
    }
}

@Composable
private fun BufferedProgressScrubber(
    played: Float,
    buffered: Float,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playedFraction = played.coerceIn(0f, 1f)
    val bufferedFraction = buffered.coerceIn(playedFraction, 1f)
    val remainingColor = MaterialTheme.colorScheme.outlineVariant
    val bufferedColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
    val playedColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .height(8.dp)
            .semantics {
                contentDescription = "Audio position"
                progressBarRangeInfo = ProgressBarRangeInfo(playedFraction, 0f..1f)
                setProgress { target ->
                    if (!enabled) return@setProgress false
                    onSeek(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun update(x: Float) = onSeek((x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                    update(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        update(change.position.x)
                        val pressed = change.pressed
                        change.consume()
                        if (!pressed) break
                    }
                }
            },
    ) {
        val y = size.height / 2f
        drawLine(remainingColor, Offset.Zero.copy(y = y), Offset(size.width, y), strokeWidth = size.height / 2f)
        drawLine(bufferedColor, Offset.Zero.copy(y = y), Offset(size.width * bufferedFraction, y), strokeWidth = size.height / 2f)
        drawLine(playedColor, Offset.Zero.copy(y = y), Offset(size.width * playedFraction, y), strokeWidth = size.height / 2f)
    }
}

private val MINI_PLAYER_HEIGHT = 56.dp
