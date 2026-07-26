package io.github.trevarj.motd.audio

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
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
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.di.IoDispatcher
import java.io.File
import java.net.HttpURLConnection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private val mediaCache: AudioMediaCache,
    private val waveformRepository: AudioWaveformRepository,
    private val waveformAnalyzer: AudioWaveformAnalyzer,
    private val db: MotdDatabase,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AudioPlaybackController {
    private val _state = MutableStateFlow(AudioPlaybackState())
    override val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()
    override val waveforms: StateFlow<Map<String, AudioWaveform>> = waveformRepository.waveforms
    private var controller: MediaController? = null
    private val controllerReady = CompletableDeferred<MediaController>()
    private var decryptedTemp: File? = null
    private var playJob: Job? = null
    private var activeRequest: AudioPlaybackRequest? = null
    private var generation = 0L

    init {
        val token = SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { mediaController ->
                        controller = mediaController.also { connected ->
                            connected.addListener(object : Player.Listener {
                                override fun onPlaybackStateChanged(playbackState: Int) = updateState(connected)
                                override fun onIsPlayingChanged(isPlaying: Boolean) = updateState(connected)
                                override fun onPlayerError(error: PlaybackException) {
                                    _state.value = _state.value.copy(
                                        loading = false,
                                        loadingFraction = null,
                                        playing = false,
                                        error = error.message ?: "Playback failed",
                                    )
                                }
                            })
                            updateState(connected)
                        }
                        controllerReady.complete(mediaController)
                    }
                    .onFailure { error ->
                        controllerReady.completeExceptionally(error)
                        _state.value = _state.value.copy(
                            loading = false,
                            error = error.message ?: "Audio service unavailable",
                        )
                    }
            },
            ContextCompat.getMainExecutor(context),
        )
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (true) {
                withContext(Dispatchers.Main.immediate) {
                    if (_state.value.activeId != null) controller?.let(::updateState)
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    override fun play(request: AudioPlaybackRequest, speed: Float) {
        val session = ++generation
        playJob?.cancel()
        activeRequest = request
        cleanupPlaintext()
        val attachment = request.attachment
        _state.value = AudioPlaybackState(
            activeId = attachment.playbackId,
            title = attachment.title,
            url = attachment.displayUrl,
            loading = true,
            speed = speed,
            attachment = attachment,
            origin = request.origin,
            waveform = attachment.waveform ?: waveformRepository.waveforms.value[attachment.playbackId],
        )
        playJob = applicationScope.launch {
            if (attachment.waveform == null) {
                waveformRepository.load(attachment.playbackId)?.let { waveform ->
                    if (session == generation) _state.value = _state.value.copy(waveform = waveform)
                }
            }
            val networkName = request.origin?.networkId?.let { networkId ->
                withContext(ioDispatcher) { db.networkDao().byId(networkId)?.name }
            }
            if (session == generation) _state.value = _state.value.copy(networkName = networkName)
            val playbackUri = try {
                materializePlaybackUri(request, session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (session == generation) {
                    cleanupPlaintext()
                    _state.value = _state.value.copy(
                        loading = false,
                        loadingFraction = null,
                        playing = false,
                        error = error.message ?: "Playback failed",
                    )
                }
                return@launch
            }
            if (session != generation) return@launch
            withContext(Dispatchers.Main.immediate) {
                val mediaController = controller ?: controllerReady.await()
                val item = MediaItem.Builder()
                    .setMediaId(attachment.playbackId)
                    .setUri(playbackUri)
                    .setMediaMetadata(mediaMetadata(request, networkName))
                    .build()
                mediaController.setMediaItem(item)
                mediaController.prepare()
                mediaController.playbackParameters = PlaybackParameters(speed)
                mediaController.play()
                updateState(mediaController)
            }
        }
    }

    override fun toggle(request: AudioPlaybackRequest) {
        val current = state.value
        if (current.activeId != request.attachment.playbackId) {
            play(request)
            return
        }
        when {
            current.loading -> cancelLoading()
            current.error != null -> retryActive()
            else -> toggleActive()
        }
    }

    override fun pause() {
        applicationScope.launch(Dispatchers.Main.immediate) {
            controller?.pause()
            controller?.let(::updateState)
        }
    }

    override fun dismiss(itemId: String) {
        if (state.value.activeId != itemId) return
        val requestToAnalyze = activeRequest
        generation++
        playJob?.cancel()
        playJob = null
        activeRequest = null
        applicationScope.launch(Dispatchers.Main.immediate) {
            controller?.run {
                stop()
                clearMediaItems()
            }
            cleanupPlaintext()
            _state.value = AudioPlaybackState()
        }
        requestToAnalyze?.let(::analyzeCachedAudio)
    }

    override fun cancelLoading() {
        val current = state.value
        if (!current.loading || current.activeId == null) return
        generation++
        playJob?.cancel()
        playJob = null
        applicationScope.launch(Dispatchers.Main.immediate) {
            controller?.run {
                stop()
                clearMediaItems()
            }
            cleanupPlaintext()
            _state.value = current.copy(
                loading = false,
                loadingFraction = null,
                playing = false,
                positionMs = 0,
                bufferedMs = 0,
                error = null,
            )
        }
    }

    override fun retryActive() {
        val request = activeRequest ?: return
        play(request, state.value.speed)
    }

    override fun toggleActive() {
        val current = state.value
        if (current.loading) {
            cancelLoading()
            return
        }
        if (current.error != null || controller?.currentMediaItem == null) {
            retryActive()
            return
        }
        applicationScope.launch(Dispatchers.Main.immediate) {
            val mediaController = controller ?: return@launch
            if (mediaController.isPlaying) mediaController.pause() else mediaController.play()
            updateState(mediaController)
        }
    }

    override fun seekTo(itemId: String, positionMs: Long) {
        applicationScope.launch(Dispatchers.Main.immediate) {
            val mediaController = controller ?: return@launch
            if (state.value.activeId != itemId || state.value.loading) return@launch
            val duration = state.value.durationMs
            mediaController.seekTo(positionMs.coerceAtLeast(0).let { value ->
                if (duration == null) value else value.coerceAtMost(duration)
            })
            updateState(mediaController)
        }
    }

    override fun setSpeed(itemId: String, speed: Float) {
        applicationScope.launch(Dispatchers.Main.immediate) {
            val mediaController = controller ?: return@launch
            if (state.value.activeId != itemId) return@launch
            mediaController.playbackParameters = PlaybackParameters(speed)
            updateState(mediaController)
        }
    }

    private suspend fun materializePlaybackUri(
        request: AudioPlaybackRequest,
        session: Long,
    ): android.net.Uri {
        val attachment = request.attachment
        if (!attachment.encrypted) return attachment.url.substringBefore('#').toUri()
        val fragment = attachment.url.substringAfter('#', "")
        if (fragment.isBlank()) throw IllegalArgumentException("Encrypted voice link has no key.")
        val cipherText = downloadEncrypted(
            url = attachment.url.substringBefore('#'),
            networkId = request.networkId,
            onProgress = { fraction ->
                if (session == generation) {
                    _state.value = _state.value.copy(loading = true, loadingFraction = fraction)
                }
            },
        )
        currentCoroutineContext().ensureActive()
        val plain = withContext(ioDispatcher) { crypto.decrypt(cipherText, fragment) }
        if (session != generation) {
            plain.delete()
            throw CancellationException("Playback was replaced.")
        }
        decryptedTemp = plain
        cacheStore.trim()
        return plain.toUri()
    }

    private suspend fun downloadEncrypted(
        url: String,
        networkId: Long?,
        onProgress: (Float?) -> Unit,
    ): File = withContext(ioDispatcher) {
        cacheStore.ciphertextFile(url).takeIf { it.isFile && it.length() > 0 }?.let {
            onProgress(1f)
            return@withContext it
        }
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
            val partial = cacheStore.tempFile("voice-ciphertext-", ".part")
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("Download failed (HTTP $code).")
                val total = connection.contentLengthLong.takeIf { it > 0 }
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        var received = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            onProgress(total?.let { (received.toFloat() / it).coerceIn(0f, 1f) })
                        }
                    }
                }
                val destination = cacheStore.ciphertextFile(url)
                if (!partial.renameTo(destination)) {
                    partial.copyTo(destination, overwrite = true)
                    partial.delete()
                }
                destination
            } finally {
                partial.delete()
                connection.disconnect()
            }
        }
    }

    private fun mediaMetadata(request: AudioPlaybackRequest, networkName: String?): MediaMetadata {
        val origin = request.origin
        val extras = Bundle().apply {
            origin?.let {
                putLong(EXTRA_BUFFER_ID, it.bufferId)
                putLong(EXTRA_EVENT_ID, it.eventId)
                putString(EXTRA_MSGID, it.msgid)
                putLong(EXTRA_SERVER_TIME, it.serverTime)
            }
        }
        return MediaMetadata.Builder()
            .setTitle(request.attachment.title)
            .setArtist(origin?.contextLabel(networkName))
            .setExtras(extras)
            .build()
    }

    private fun updateState(mediaController: MediaController) {
        val current = _state.value
        if (current.activeId == null) return
        if (mediaController.playbackState == Player.STATE_ENDED) {
            dismiss(current.activeId)
            return
        }
        val duration = mediaController.duration.takeIf { it >= 0 }
        val mediaId = mediaController.currentMediaItem?.mediaId
        _state.value = current.copy(
            activeId = mediaId ?: current.activeId,
            loading = mediaController.playbackState == Player.STATE_BUFFERING,
            loadingFraction = null,
            playing = mediaController.isPlaying,
            positionMs = mediaController.currentPosition.coerceAtLeast(0L),
            durationMs = duration ?: current.attachment?.durationMs,
            bufferedMs = mediaController.bufferedPosition.coerceAtLeast(0L),
            speed = mediaController.playbackParameters.speed,
            error = null,
        )
    }

    private fun cleanupPlaintext() {
        decryptedTemp?.delete()
        decryptedTemp = null
    }

    private fun analyzeCachedAudio(request: AudioPlaybackRequest) {
        val attachment = request.attachment
        if (attachment.encrypted || attachment.waveform != null ||
            !attachment.url.startsWith("http", ignoreCase = true)
        ) return
        applicationScope.launch(ioDispatcher) {
            val local = cacheStore.tempFile("audio-analysis-", ".media")
            try {
                if (!mediaCache.copyIfComplete(attachment.url.substringBefore('#'), local)) return@launch
                val waveform = waveformAnalyzer.analyze(local) ?: return@launch
                waveformRepository.put(attachment.playbackId, waveform)
            } finally {
                local.delete()
            }
        }
    }

    private inline fun <T> NetworkMediaRoute?.useOrNull(block: (NetworkMediaRoute?) -> T): T =
        try {
            block(this)
        } finally {
            this?.close()
        }

    private companion object {
        const val POSITION_POLL_MS = 250L
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val DOWNLOAD_BUFFER_BYTES = 32 * 1024
        const val USER_AGENT = "motd-Android (https://github.com/trevarj/motd)"
        const val EXTRA_BUFFER_ID = "motd.audio.buffer_id"
        const val EXTRA_EVENT_ID = "motd.audio.event_id"
        const val EXTRA_MSGID = "motd.audio.msgid"
        const val EXTRA_SERVER_TIME = "motd.audio.server_time"
    }
}

fun AudioPlaybackOrigin.contextLabel(networkName: String? = null, includeNetwork: Boolean = false): String {
    val actor = if (isSelf) "You" else sender
    val conversationLabel = when {
        directMessage && !isSelf -> "Direct message"
        else -> conversation
    }
    return buildString {
        append(actor)
        append(" · ")
        append(conversationLabel)
        if (includeNetwork && !networkName.isNullOrBlank()) {
            append(" · ")
            append(networkName)
        }
    }
}
