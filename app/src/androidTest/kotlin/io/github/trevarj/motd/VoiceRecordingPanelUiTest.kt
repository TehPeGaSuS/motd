package io.github.trevarj.motd

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.ui.chat.VoiceComposerPanel
import io.github.trevarj.motd.ui.chat.VoiceMessageUiState
import io.github.trevarj.motd.ui.chat.VoiceRecordingUi
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceRecordingPanelUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun locking_keeps_the_recording_panel_height_stable() {
        val locked = mutableStateOf(false)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                VoiceComposerPanel(
                    state = VoiceMessageUiState(
                        recording = VoiceRecordingUi(elapsedMs = 1_000, locked = locked.value),
                    ),
                    playbackState = AudioPlaybackState(),
                    onDelete = {},
                    onCancelRecording = {},
                    onSend = {},
                    onPreview = { _ -> },
                    onPreviewSeek = { _, _ -> },
                    onToggleEncryption = {},
                    onDestinationSelected = { _ -> },
                    onErrorDismissed = {},
                )
            }
        }

        val unlockedHeight = panelHeight()
        compose.runOnIdle { locked.value = true }

        assertEquals(unlockedHeight, panelHeight())
    }

    private fun panelHeight() = compose.onNodeWithTag("voice_recording_panel")
        .getUnclippedBoundsInRoot()
        .let { it.bottom - it.top }
}
