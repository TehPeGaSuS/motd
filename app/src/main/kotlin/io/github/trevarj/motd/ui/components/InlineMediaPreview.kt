package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.chat.isVideoUrl

/**
 * Inline media shared by every chat density. Images retain the full-screen image-viewer action;
 * videos initially decode one representative frame and allocate a player only after an explicit
 * tap, preventing a scrolling history window from starting many network streams at once.
 */
@Composable
internal fun InlineMediaPreview(
    url: String,
    modifier: Modifier,
    onImageClick: (String) -> Unit,
    onLongPress: () -> Unit,
) {
    if (isVideoUrl(url)) {
        InlineVideoPreview(url = url, modifier = modifier, onLongPress = onLongPress)
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = modifier.combinedClickable(
                onClick = { onImageClick(url) },
                onLongClick = onLongPress,
            ),
        )
    }
}

@Composable
private fun InlineVideoPreview(
    url: String,
    modifier: Modifier,
    onLongPress: () -> Unit,
) {
    var playing by rememberSaveable(url) { mutableStateOf(false) }
    if (playing) {
        val context = LocalContext.current
        val player = remember(url) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                play()
            }
        }
        DisposableEffect(player) {
            onDispose(player::release)
        }
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = true
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = modifier.testTag("inline_video_preview"),
        )
    } else {
        val context = LocalContext.current
        val frame = remember(url) {
            ImageRequest.Builder(context)
                .data(url)
                .videoFrameMillis(0)
                .build()
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .testTag("inline_video_preview")
                .combinedClickable(
                    onClick = { playing = true },
                    onLongClick = onLongPress,
                ),
        ) {
            AsyncImage(
                model = frame,
                contentDescription = stringResource(R.string.chat_video_preview),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = stringResource(R.string.chat_video_play),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.56f)),
            )
        }
    }
}
