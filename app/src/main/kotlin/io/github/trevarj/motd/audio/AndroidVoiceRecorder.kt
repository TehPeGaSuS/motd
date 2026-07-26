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
    private val amplitudes = mutableListOf<Int>()

    init {
        recordingDirectory().listFiles()?.forEach(File::delete)
    }

    override fun start(profile: VoiceRecordingProfile, nowMs: Long): ActiveVoiceRecording {
        check(recorder == null) { "A voice recording is already active." }
        val format = recordingFormat()
        amplitudes.clear()
        return try {
            startRecorder(format, profile, nowMs, processingFallback = false)
        } catch (processedError: Exception) {
            if (!profile.noiseReduction) throw processedError
            try {
                startRecorder(format, profile.copy(noiseReduction = false), nowMs, processingFallback = true)
            } catch (naturalError: Exception) {
                naturalError.addSuppressed(processedError)
                throw naturalError
            }
        }
    }

    override fun currentAmplitude(): Int? = recorder?.let { activeRecorder ->
        runCatching { activeRecorder.maxAmplitude }.getOrNull()?.also(amplitudes::add)
    }

    private fun startRecorder(
        format: Format,
        profile: VoiceRecordingProfile,
        nowMs: Long,
        processingFallback: Boolean,
    ): ActiveVoiceRecording {
        val file = File.createTempFile("voice-", format.extension, recordingDirectory())
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            mediaRecorder.setAudioSource(
                if (profile.noiseReduction) {
                    MediaRecorder.AudioSource.DEFAULT
                } else {
                    MediaRecorder.AudioSource.MIC
                },
            )
            mediaRecorder.setOutputFormat(format.outputFormat)
            mediaRecorder.setAudioEncoder(format.audioEncoder)
            mediaRecorder.setAudioChannels(AUDIO_CHANNELS)
            mediaRecorder.setAudioEncodingBitRate(profile.quality.bitRate(format.opus))
            mediaRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            ActiveVoiceRecording(
                file = file,
                startedAtMs = nowMs,
                mimeType = format.mimeType,
                extension = format.extension,
                processingFallback = processingFallback,
            ).also {
                recorder = mediaRecorder
                active = it
            }
        } catch (error: Exception) {
            runCatching { mediaRecorder.reset() }
            runCatching { mediaRecorder.release() }
            file.delete()
            throw error
        }
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
                    waveform = AudioWaveform.fromAmplitudes(amplitudes),
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
        amplitudes.clear()
    }

    private data class Format(
        val outputFormat: Int,
        val audioEncoder: Int,
        val mimeType: String,
        val extension: String,
        val opus: Boolean,
    )

    private fun recordingDirectory() = File(context.cacheDir, "voice-recordings").also { it.mkdirs() }

    private fun recordingFormat(): Format =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Format(
                outputFormat = MediaRecorder.OutputFormat.OGG,
                audioEncoder = MediaRecorder.AudioEncoder.OPUS,
                mimeType = "audio/ogg",
                extension = ".ogg",
                opus = true,
            )
        } else {
            Format(
                outputFormat = MediaRecorder.OutputFormat.MPEG_4,
                audioEncoder = MediaRecorder.AudioEncoder.AAC,
                mimeType = "audio/mp4",
                extension = ".m4a",
                opus = false,
            )
        }

    private companion object {
        const val AUDIO_CHANNELS = 1
        const val AUDIO_SAMPLE_RATE = 48_000
        const val MIN_VALID_DURATION_MS = 1_000L
    }
}
