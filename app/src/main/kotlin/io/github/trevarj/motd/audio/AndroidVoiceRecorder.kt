package io.github.trevarj.motd.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidVoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var active: ActiveVoiceRecording? = null

    override fun start(nowMs: Long): ActiveVoiceRecording {
        check(recorder == null) { "A voice recording is already active." }
        val format = recordingFormat()
        val file = File(context.cacheDir, "voice-recordings").also { it.mkdirs() }
            .let { dir -> File.createTempFile("voice-", format.extension, dir) }
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder.setOutputFormat(format.outputFormat)
        mediaRecorder.setAudioEncoder(format.audioEncoder)
        mediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)
        mediaRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
        mediaRecorder.setOutputFile(file.absolutePath)
        mediaRecorder.prepare()
        mediaRecorder.start()
        val started = ActiveVoiceRecording(file, nowMs, format.mimeType, format.extension)
        recorder = mediaRecorder
        active = started
        return started
    }

    override fun stop(nowMs: Long): CompletedVoiceRecording? {
        val mediaRecorder = recorder ?: return null
        val started = active ?: return null
        recorder = null
        active = null
        return try {
            mediaRecorder.stop()
            mediaRecorder.reset()
            mediaRecorder.release()
            val duration = (nowMs - started.startedAtMs).coerceAtLeast(0L)
            val size = started.file.length()
            if (duration < MIN_VALID_DURATION_MS || size <= 0L) {
                started.file.delete()
                null
            } else {
                CompletedVoiceRecording(
                    file = started.file,
                    durationMs = duration,
                    mimeType = started.mimeType,
                    extension = started.extension,
                    sizeBytes = size,
                )
            }
        } catch (error: RuntimeException) {
            runCatching { mediaRecorder.release() }
            started.file.delete()
            null
        }
    }

    override fun cancel() {
        val mediaRecorder = recorder
        val started = active
        recorder = null
        active = null
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        started?.file?.delete()
    }

    private data class Format(
        val outputFormat: Int,
        val audioEncoder: Int,
        val mimeType: String,
        val extension: String,
    )

    private fun recordingFormat(): Format =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Format(
                outputFormat = MediaRecorder.OutputFormat.OGG,
                audioEncoder = MediaRecorder.AudioEncoder.OPUS,
                mimeType = "audio/ogg",
                extension = ".ogg",
            )
        } else {
            Format(
                outputFormat = MediaRecorder.OutputFormat.MPEG_4,
                audioEncoder = MediaRecorder.AudioEncoder.AAC,
                mimeType = "audio/mp4",
                extension = ".m4a",
            )
        }

    private companion object {
        const val AUDIO_BIT_RATE = 24_000
        const val AUDIO_SAMPLE_RATE = 48_000
        const val MIN_VALID_DURATION_MS = 1_000L
    }
}
