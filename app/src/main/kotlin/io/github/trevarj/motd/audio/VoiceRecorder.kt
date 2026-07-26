package io.github.trevarj.motd.audio

import java.io.File

data class ActiveVoiceRecording(
    val file: File,
    val startedAtMs: Long,
    val mimeType: String,
    val extension: String,
)

data class CompletedVoiceRecording(
    val file: File,
    val durationMs: Long,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long,
)

interface VoiceRecorder {
    fun start(nowMs: Long = System.currentTimeMillis()): ActiveVoiceRecording
    fun stop(nowMs: Long = System.currentTimeMillis()): CompletedVoiceRecording?
    fun cancel()
}
