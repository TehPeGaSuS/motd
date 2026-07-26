package io.github.trevarj.motd.audio

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.di.IoDispatcher
import java.io.File
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class AudioPlaybackControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routeProvider: NetworkMediaRouteProvider,
    private val crypto: VoiceCrypto,
    private val cacheStore: AudioCacheStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AudioPlaybackController {
    private val _state = MutableStateFlow(AudioPlaybackState())
    override val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private val controllerReady = CompletableDeferred<MediaController>()
    private var decryptedTemp: File? = null

    init {
        val token = SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                controller = future.get().also { mediaController ->
                    mediaController.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) = updateState(mediaController)
                        override fun onIsPlayingChanged(isPlaying: Boolean) = updateState(mediaController)
                        override fun onPlayerError(error: PlaybackException) {
                            _state.value = _state.value.copy(
                                loading = false,
                                playing = false,
                                error = error.message ?: "Playback failed",
                            )
                        }
                    })
                    updateState(mediaController)
                    controllerReady.complete(mediaController)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (true) {
                controller?.let(::updateState)
                delay(POSITION_POLL_MS)
            }
        }
    }

    override fun play(attachment: AudioAttachment, networkId: Long?, speed: Float) {
        applicationScope.launch {
            _state.value = AudioPlaybackState(
                activeId = attachment.playbackId,
                title = attachment.title,
                url = attachment.displayUrl,
                loading = true,
                speed = speed,
            )
            val playbackUri = try {
                materializePlaybackUri(attachment, networkId)
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    playing = false,
                    error = error.message ?: "Playback failed",
                )
                return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                val mediaController = controller ?: controllerReady.await()
                val item = MediaItem.Builder()
                    .setMediaId(attachment.playbackId)
                    .setUri(playbackUri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(attachment.title).build())
                    .build()
                mediaController.setMediaItem(item)
                mediaController.prepare()
                mediaController.playbackParameters = PlaybackParameters(speed)
                mediaController.play()
                updateState(mediaController)
            }
        }
    }

    override fun toggle(attachment: AudioAttachment, networkId: Long?) {
        val current = state.value
        if (current.activeId == attachment.playbackId && current.playing) {
            pause()
        } else {
            play(attachment, networkId, current.speed.takeIf { current.activeId == attachment.playbackId } ?: 1f)
        }
    }

    override fun pause() {
        controller?.pause()
        controller?.let(::updateState)
    }

    override fun toggleActive() {
        val mediaController = controller ?: return
        if (mediaController.isPlaying) mediaController.pause() else mediaController.play()
        updateState(mediaController)
    }

    override fun seekTo(itemId: String, positionMs: Long) {
        val mediaController = controller ?: return
        if (state.value.activeId != itemId) return
        mediaController.seekTo(positionMs.coerceAtLeast(0))
        updateState(mediaController)
    }

    override fun setSpeed(itemId: String, speed: Float) {
        val mediaController = controller ?: return
        if (state.value.activeId != itemId) return
        mediaController.playbackParameters = PlaybackParameters(speed)
        updateState(mediaController)
    }

    private suspend fun materializePlaybackUri(attachment: AudioAttachment, networkId: Long?): android.net.Uri {
        if (!attachment.encrypted) return attachment.url.substringBefore('#').toUri()
        val fragment = attachment.url.substringAfter('#', "")
        if (fragment.isBlank()) throw IllegalArgumentException("Encrypted voice link has no key.")
        decryptedTemp?.delete()
        val cipherText = downloadEncrypted(attachment.url.substringBefore('#'), networkId)
        val plain = crypto.decrypt(cipherText, fragment)
        cipherText.delete()
        cacheStore.trim()
        decryptedTemp = plain
        return plain.toUri()
    }

    private suspend fun downloadEncrypted(url: String, networkId: Long?): File = withContext(ioDispatcher) {
        val route = networkId?.let { routeProvider.routeForNetwork(it) }
        if (route?.proxyError != null) throw IllegalStateException(route.proxyError)
        route.useOrNull { mediaRoute ->
            val connection = (mediaRoute?.open(url)
                ?: java.net.URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                useCaches = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/octet-stream, */*")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("Download failed (HTTP $code).")
                val out = cacheStore.tempFile("voice-ciphertext-", ".motdvoice")
                connection.inputStream.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out
            } finally {
                connection.disconnect()
            }
        }
    }

    private inline fun <T> NetworkMediaRoute?.useOrNull(block: (NetworkMediaRoute?) -> T): T =
        try {
            block(this)
        } finally {
            this?.close()
        }

    private fun updateState(mediaController: MediaController) {
        val duration = mediaController.duration.takeIf { it >= 0 }
        val mediaId = mediaController.currentMediaItem?.mediaId
        _state.value = _state.value.copy(
            activeId = mediaId ?: _state.value.activeId,
            loading = mediaController.playbackState == Player.STATE_BUFFERING,
            playing = mediaController.isPlaying,
            positionMs = mediaController.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            bufferedMs = mediaController.bufferedPosition.coerceAtLeast(0L),
            speed = mediaController.playbackParameters.speed,
            error = null,
        )
        if (mediaController.playbackState == Player.STATE_ENDED) {
            mediaController.pause()
            mediaController.seekTo(0)
            _state.value = _state.value.copy(playing = false, positionMs = 0, loading = false)
        }
    }

    private companion object {
        const val POSITION_POLL_MS = 250L
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val USER_AGENT = "motd-Android (https://github.com/trevarj/motd)"
    }
}
