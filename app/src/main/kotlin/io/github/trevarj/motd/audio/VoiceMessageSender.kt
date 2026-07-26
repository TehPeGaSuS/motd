package io.github.trevarj.motd.audio

import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploader
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.normalizedConfig
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

data class VoiceSendRequest(
    val bufferId: Long,
    val file: File,
    val durationMs: Long,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long,
    val waveform: AudioWaveform = AudioWaveform.EMPTY,
    val encrypt: Boolean,
    val destination: PasteBackendConfig? = null,
)

sealed interface VoiceSendProgress {
    data class Uploading(val bytesSent: Long, val totalBytes: Long?) : VoiceSendProgress
    data class Complete(val url: String) : VoiceSendProgress
}

interface VoiceMessageSender {
    fun send(request: VoiceSendRequest): Flow<VoiceSendProgress>
}

@Singleton
class VoiceMessageSenderImpl @Inject constructor(
    private val db: MotdDatabase,
    private val connectionManager: ConnectionManager,
    private val attachmentPrefs: AttachmentPrefs,
    private val attachmentUploader: AttachmentUploader,
    private val routeProvider: NetworkMediaRouteProvider,
    private val crypto: VoiceCrypto,
) : VoiceMessageSender {
    override fun send(request: VoiceSendRequest): Flow<VoiceSendProgress> = channelFlow {
        val buffer = db.bufferDao().observeById(request.bufferId)
            ?: throw VoiceSendException("Conversation no longer exists.")
        val encrypted = if (request.encrypt) crypto.encrypt(request.file) else null
        val uploadFile = encrypted?.file ?: request.file
        val uploadMime = encrypted?.mimeType ?: request.mimeType
        val uploadName = voiceFileName(request, encrypted != null)
        val record = try {
            uploadVoiceFile(
                networkId = buffer.networkId,
                file = uploadFile,
                name = uploadName,
                mimeType = uploadMime,
                sizeBytes = uploadFile.length(),
                destination = request.destination,
                progress = { sent, total -> send(VoiceSendProgress.Uploading(sent, total)) },
            )
        } finally {
            encrypted?.file?.delete()
        }
        val baseWireUrl = if (encrypted != null) {
            "${record.url}#${encrypted.keyFragment}"
        } else {
            record.url
        }
        val waveformUrl = appendAudioWaveform(baseWireUrl, request.waveform)
        val waveformText = voiceFallback(
            durationMs = request.durationMs,
            mimeType = request.mimeType,
            url = waveformUrl,
            encrypted = encrypted != null,
            expiry = record.expiry,
        )
        val wireUrl = if (wireBytes(buffer.name, waveformText) <= MAX_IRC_WIRE_BYTES) waveformUrl else baseWireUrl
        val wireText = if (wireUrl == waveformUrl) waveformText else voiceFallback(
            durationMs = request.durationMs,
            mimeType = request.mimeType,
            url = wireUrl,
            encrypted = encrypted != null,
            expiry = record.expiry,
        )
        when (val acceptance = connectionManager.sendMessage(request.bufferId, wireText)) {
            is SendAcceptance.Accepted -> send(VoiceSendProgress.Complete(wireUrl))
            is SendAcceptance.Rejected -> throw VoiceSendException(
                "Upload finished, but IRC rejected the message (${acceptance.reason.name.lowercase()}).",
            )
        }
    }

    private suspend fun uploadVoiceFile(
        networkId: Long,
        file: File,
        name: String,
        mimeType: String,
        sizeBytes: Long,
        destination: PasteBackendConfig?,
        progress: suspend (Long, Long?) -> Unit,
    ): VoiceUploadRecord {
        val selected = destination?.let(::normalizedConfig)
        if (selected == null) {
            val fileHost = fileHostEndpoint(networkId)
                ?: throw VoiceSendException(
                    "This IRC network is not advertising a Soju file host. Choose another upload destination.",
                )
            return try {
                uploadToFileHost(networkId, fileHost, file, name, mimeType, sizeBytes, progress)
            } catch (error: VoiceSendException) {
                throw error
            } catch (error: IOException) {
                throw VoiceSendException(
                    "Could not upload to the Soju file host: ${error.message ?: "connection failed"}.",
                    error,
                )
            }
        }
        val source = AttachmentSource.LocalFile(file, name, mimeType, sizeBytes)
        val result = attachmentUploader.upload(source, selected)
            .onEach { update ->
                if (update is UploadProgress.Transferring) progress(update.bytesSent, update.totalBytes)
            }
            .last()
        val record = (result as? UploadProgress.Complete)?.record
            ?: throw VoiceSendException("Upload did not complete.")
        attachmentPrefs.addUpload(record)
        return VoiceUploadRecord(record.url, voiceExpiryFor(selected))
    }

    private fun fileHostEndpoint(networkId: Long): String? {
        val ready = connectionManager.connectionStates.value[networkId] as? IrcClientState.Ready
            ?: return null
        return ready.isupport["soju.im/FILEHOST"]?.takeIf { raw ->
            runCatching {
                val uri = URI(raw)
                uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
            }.getOrDefault(false)
        }
    }

    private suspend fun uploadToFileHost(
        networkId: Long,
        endpoint: String,
        file: File,
        name: String,
        mimeType: String,
        sizeBytes: Long,
        progress: suspend (Long, Long?) -> Unit,
    ): VoiceUploadRecord = withContext(Dispatchers.IO) {
        val route = routeProvider.routeForNetwork(networkId)
            ?: throw VoiceSendException("No route for this network.")
        if (route.proxyError != null) throw VoiceSendException(route.proxyError)
        route.use {
            probeFileHost(route, endpoint, mimeType)
            val connection = route.open(endpoint, authenticated = true).apply {
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", mimeType)
                setRequestProperty("Content-Disposition", "attachment; filename=\"${name.sanitizeHeader()}\"")
                setRequestProperty("User-Agent", USER_AGENT)
                setFixedLengthStreamingMode(sizeBytes)
            }
            try {
                connection.outputStream.use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(STREAM_BUFFER_BYTES)
                        var sent = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            sent += count
                            progress(sent, sizeBytes)
                        }
                    }
                }
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code != HttpURLConnection.HTTP_CREATED || location.isNullOrBlank()) {
                    throw VoiceSendException("FILEHOST upload failed (HTTP $code).")
                }
                VoiceUploadRecord(resolveLocation(endpoint, location), expiry = null)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun probeFileHost(route: NetworkMediaRoute, endpoint: String, mimeType: String) {
        val connection = route.open(endpoint).apply {
            requestMethod = "OPTIONS"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299 && code != HttpURLConnection.HTTP_NO_CONTENT) {
                throw VoiceSendException("FILEHOST probe failed (HTTP $code).")
            }
            val acceptPost = connection.getHeaderField("Accept-Post")
            if (!acceptPost.isNullOrBlank() && !acceptPost.acceptsMime(mimeType)) {
                throw VoiceSendException("FILEHOST does not accept $mimeType.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private data class VoiceUploadRecord(
        val url: String,
        val expiry: String?,
    )

    private fun resolveLocation(endpoint: String, location: String): String {
        val resolved = URI(endpoint).resolve(location).toString()
        val uri = URI(resolved)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || uri.host.isNullOrBlank()) {
            throw VoiceSendException("FILEHOST returned an invalid HTTPS URL.")
        }
        return resolved
    }

    private fun voiceFileName(request: VoiceSendRequest, encrypted: Boolean): String =
        if (encrypted) {
            "voice-${UUID.randomUUID()}.motdvoice"
        } else {
            "voice-${UUID.randomUUID()}${request.extension}"
        }

    private fun voiceFallback(
        durationMs: Long,
        mimeType: String,
        url: String,
        encrypted: Boolean,
        expiry: String?,
    ): String = buildString {
        append("[voice ")
        if (encrypted) append("encrypted ")
        append(formatAudioDuration(durationMs))
        append(' ')
        append(mimeType)
        expiry?.takeIf(String::isNotBlank)?.let {
            append(" expires=")
            append(it)
        }
        append("] ")
        append(url)
    }

    private fun voiceExpiryFor(config: PasteBackendConfig): String? = when (config.backend.name) {
        "UGUU" -> "3h"
        "LITTERBOX" -> config.litterboxExpiry
        "CRAFTERBIN", "ZERO_X_ZERO", "CUSTOM_0X0" -> config.expiry
        "X0_AT" -> "3-100d"
        else -> null
    }

    private fun String.sanitizeHeader(): String =
        replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_")

    private fun String.acceptsMime(mimeType: String): Boolean {
        val requested = mimeType.lowercase(Locale.ROOT)
        return split(',').map { it.substringBefore(';').trim().lowercase(Locale.ROOT) }.any { accepted ->
            accepted == "*/*" ||
                accepted == requested ||
                accepted.endsWith("/*") && requested.startsWith(accepted.removeSuffix("*"))
        }
    }

    private fun wireBytes(target: String, text: String): Int =
        "PRIVMSG $target :$text\r\n".toByteArray(StandardCharsets.UTF_8).size

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val STREAM_BUFFER_BYTES = 32 * 1024
        const val USER_AGENT = "motd-Android (https://github.com/trevarj/motd)"
        const val MAX_IRC_WIRE_BYTES = 480
    }
}

class VoiceSendException(message: String, cause: Throwable? = null) : IOException(message, cause)
