package io.github.trevarj.motd.audio

import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploader
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import io.github.trevarj.motd.attachment.normalizedConfig
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import java.io.File
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
import kotlinx.coroutines.flow.first
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
    private val voicePrefs: VoicePrefs,
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
        val wireUrl = if (encrypted != null) {
            "${record.url}#${encrypted.keyFragment}"
        } else {
            record.url
        }
        val wireText = voiceFallback(
            durationMs = request.durationMs,
            mimeType = request.mimeType,
            url = wireUrl,
            encrypted = encrypted != null,
            expiry = record.expiry,
        )
        when (connectionManager.sendMessage(request.bufferId, wireText)) {
            is SendAcceptance.Accepted -> send(VoiceSendProgress.Complete(wireUrl))
            is SendAcceptance.Rejected -> throw VoiceSendException("Voice message could not be sent.")
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
        val fileHost = fileHostEndpoint(networkId)
        if (fileHost != null) {
            return uploadToFileHost(networkId, fileHost, file, name, mimeType, sizeBytes, progress)
        }
        val config = normalizedConfig(
            destination
                ?: voicePrefs.config.first().rememberedDestination
                ?: attachmentPrefs.config.first(),
        )
        val source = AttachmentSource.LocalFile(file, name, mimeType, sizeBytes)
        val result = attachmentUploader.upload(source, config)
            .onEach { update ->
                if (update is UploadProgress.Transferring) progress(update.bytesSent, update.totalBytes)
            }
            .last()
        val record = (result as? UploadProgress.Complete)?.record
            ?: throw VoiceSendException("Upload did not complete.")
        return VoiceUploadRecord(record.url, voiceExpiryFor(config), record)
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
            val connection = route.open(endpoint).apply {
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
                VoiceUploadRecord(resolveLocation(endpoint, location), expiry = null, uploadRecord = null)
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
        val uploadRecord: UploadRecord?,
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

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val STREAM_BUFFER_BYTES = 32 * 1024
        const val USER_AGENT = "motd-Android (https://github.com/trevarj/motd)"
    }
}

class VoiceSendException(message: String) : java.io.IOException(message)
