package io.github.trevarj.motd.audio

import java.io.File

data class ActiveVoiceRecording(
    val file: File,
    val startedAtMs: Long,
    val mimeType: String,
    val extension: String,
    val processingFallback: Boolean = false,
)

data class CompletedVoiceRecording(
    val file: File,
    val durationMs: Long,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long,
    val waveform: AudioWaveform = AudioWaveform.EMPTY,
)

data class VoiceRecordingProfile(
    val quality: VoiceRecordingQuality = VoiceRecordingQuality.BALANCED,
    val noiseReduction: Boolean = true,
)

fun VoiceRecordingQuality.bitRate(opus: Boolean): Int = when (this) {
    VoiceRecordingQuality.DATA_SAVER -> if (opus) 24_000 else 32_000
    VoiceRecordingQuality.BALANCED -> if (opus) 48_000 else 64_000
    VoiceRecordingQuality.HIGH -> if (opus) 64_000 else 96_000
}

interface VoiceRecorder {
    fun start(
        profile: VoiceRecordingProfile = VoiceRecordingProfile(),
        nowMs: Long = System.currentTimeMillis(),
    ): ActiveVoiceRecording
    fun currentAmplitude(): Int?
    fun stop(nowMs: Long = System.currentTimeMillis()): CompletedVoiceRecording?
    fun cancel()
}
