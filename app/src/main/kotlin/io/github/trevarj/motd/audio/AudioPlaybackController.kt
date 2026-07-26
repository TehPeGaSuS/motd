package io.github.trevarj.motd.audio

import kotlinx.coroutines.flow.StateFlow

data class AudioPlaybackState(
    val activeId: String? = null,
    val title: String? = null,
    val url: String? = null,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val bufferedMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
)

interface AudioPlaybackController {
    val state: StateFlow<AudioPlaybackState>
    fun play(attachment: AudioAttachment, networkId: Long?, speed: Float = 1f)
    fun toggle(attachment: AudioAttachment, networkId: Long?)
    fun toggleActive()
    fun pause()
    fun seekTo(itemId: String, positionMs: Long)
    fun setSpeed(itemId: String, speed: Float)
}
