package io.github.trevarj.motd.ui.components

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.audio.AudioPlaybackController
import javax.inject.Inject

@HiltViewModel
class AudioPlaybackViewModel
    @Inject
    constructor(
        private val controller: AudioPlaybackController,
    ) : ViewModel() {
        val state = controller.state

        fun toggle() = controller.toggleActive()

        fun cancelLoading() = controller.cancelLoading()

        fun retry() = controller.retryActive()

        fun dismiss() = state.value.activeId?.let(controller::dismiss)

        fun seek(positionMs: Long) = state.value.activeId?.let { controller.seekTo(it, positionMs) }

        fun setSpeed(speed: Float) = state.value.activeId?.let { controller.setSpeed(it, speed) }
    }
