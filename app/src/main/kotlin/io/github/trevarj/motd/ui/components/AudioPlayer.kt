package io.github.trevarj.motd.ui.components

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.formatAudioDuration
import io.github.trevarj.motd.ui.chat.formatBytes

private const val MAX_COLLAPSED_AUDIO_PLAYERS = 3

@Composable
fun AudioAttachmentPlayers(
    attachments: List<AudioAttachment>,
    playbackState: AudioPlaybackState,
    networkId: Long?,
    isSelf: Boolean,
    onToggle: (AudioAttachment, Long?) -> Unit,
    onSeek: (AudioAttachment, Long) -> Unit,
    onSpeed: (AudioAttachment, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    var expanded by remember(attachments) { mutableStateOf(false) }
    val visible = if (expanded) attachments else attachments.take(MAX_COLLAPSED_AUDIO_PLAYERS)
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        visible.forEachIndexed { index, attachment ->
            AudioAttachmentPlayer(
                attachment = attachment,
                playbackState = playbackState,
                networkId = networkId,
                onToggle = onToggle,
                onSeek = onSeek,
                onSpeed = onSpeed,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .testTag("audio_player_${index}_${attachment.playbackId.hashCode()}"),
            )
        }
        if (!expanded && attachments.size > MAX_COLLAPSED_AUDIO_PLAYERS) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.testTag("audio_player_expand"),
            ) {
                Icon(Icons.Filled.ExpandMore, null)
                Spacer(Modifier.width(4.dp))
                Text("+${attachments.size - MAX_COLLAPSED_AUDIO_PLAYERS}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioAttachmentPlayer(
    attachment: AudioAttachment,
    playbackState: AudioPlaybackState,
    networkId: Long?,
    onToggle: (AudioAttachment, Long?) -> Unit,
    onSeek: (AudioAttachment, Long) -> Unit,
    onSpeed: (AudioAttachment, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val active = playbackState.activeId == attachment.playbackId
    val playing = active && playbackState.playing
    val duration = (if (active) playbackState.durationMs else attachment.durationMs) ?: attachment.durationMs
    val position = if (active) playbackState.positionMs else 0L
    val speed = if (active) playbackState.speed else 1f
    var showDetails by remember { mutableStateOf(false) }
    var confirmHttp by remember { mutableStateOf(false) }
    var scrubValue by remember(active, position) { mutableFloatStateOf(position.toFloat()) }

    Surface(
        modifier = modifier.combinedClickable(
            onClick = {},
            onLongClick = { showDetails = true },
        ),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        if (attachment.cleartextHttp && !active) confirmHttp = true else onToggle(attachment, networkId)
                    },
                    modifier = Modifier.size(42.dp),
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playing) "Pause audio" else "Play audio",
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (attachment.voice) Icons.Outlined.GraphicEq else Icons.Outlined.Info,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            attachment.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${formatAudioDuration(position)} / ${formatAudioDuration(duration)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (attachment.encrypted) {
                            Text(
                                " · encrypted",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (attachment.cleartextHttp) {
                            Icon(
                                Icons.Outlined.Warning,
                                null,
                                modifier = Modifier.padding(start = 4.dp).size(14.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            Slider(
                value = scrubValue.coerceIn(0f, (duration ?: 1L).coerceAtLeast(1L).toFloat()),
                onValueChange = { scrubValue = it },
                onValueChangeFinished = { onSeek(attachment, scrubValue.toLong()) },
                valueRange = 0f..(duration ?: 1L).coerceAtLeast(1L).toFloat(),
                modifier = Modifier.height(28.dp).testTag("audio_player_scrubber"),
            )
            if (attachment.voice) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1f, 1.5f, 2f).forEach { option ->
                        FilterChip(
                            selected = active && speed == option,
                            onClick = { onSpeed(attachment, option) },
                            label = { Text("${option.cleanSpeed()}x") },
                            modifier = Modifier.testTag("audio_speed_${option.cleanSpeed()}"),
                        )
                    }
                }
            }
        }
    }

    if (confirmHttp) {
        AlertDialog(
            onDismissRequest = { confirmHttp = false },
            icon = { Icon(Icons.Outlined.Warning, null) },
            title = { Text("Play cleartext audio?") },
            text = { Text("This link uses HTTP. Anyone on the network path may see or modify it.") },
            confirmButton = {
                TextButton(onClick = { confirmHttp = false; onToggle(attachment, networkId) }) {
                    Text("Play")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmHttp = false }) { Text("Cancel") }
            },
        )
    }

    if (showDetails) {
        ModalBottomSheet(onDismissRequest = { showDetails = false }) {
            AudioDetailsSheet(
                attachment = attachment,
                onCopy = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(attachment.title, attachment.displayUrl))
                },
                onOpen = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, attachment.displayUrl.toUri()))
                },
                onSave = { enqueueDownload(context, attachment) },
            )
        }
    }
}

@Composable
private fun AudioDetailsSheet(
    attachment: AudioAttachment,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Audio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        DetailRow("Link", attachment.displayUrl)
        DetailRow("Type", attachment.mimeType ?: "Unknown")
        DetailRow("Duration", formatAudioDuration(attachment.durationMs))
        DetailRow("Size", attachment.sizeBytes?.let(::formatBytes) ?: "Unknown")
        DetailRow("Expires", attachment.expiry ?: "Unknown")
        DetailRow("Encryption", if (attachment.encrypted) "Host-blind key in URL fragment" else "Off")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, null)
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
            TextButton(onClick = onOpen) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null)
                Spacer(Modifier.width(6.dp))
                Text("Open")
            }
            TextButton(onClick = onSave) {
                Icon(Icons.Outlined.Download, null)
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(value, maxLines = 3, overflow = TextOverflow.Ellipsis) },
    )
}

private fun Float.cleanSpeed(): String =
    if (this == toInt().toFloat()) toInt().toString() else toString()

private fun enqueueDownload(context: Context, attachment: AudioAttachment) {
    val request = DownloadManager.Request(attachment.url.substringBefore('#').toUri())
        .setTitle(attachment.title)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    context.getSystemService(DownloadManager::class.java)?.enqueue(request)
}
