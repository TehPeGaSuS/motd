package io.github.trevarj.motd

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.ui.components.AudioAttachmentPlayers
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Test

class AudioPlayerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun uncached_audio_uses_download_action() {
        val attachment = audio()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.NOT_CACHED),
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                    onSpeed = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Download audio").assertIsDisplayed()
    }

    @Test fun cached_audio_uses_play_action() {
        val attachment = audio()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AudioAttachmentPlayers(
                    attachments = listOf(attachment),
                    playbackState = AudioPlaybackState(),
                    cacheStatuses = mapOf(attachment.playbackId to AudioCacheStatus.CACHED),
                    networkId = null,
                    isSelf = false,
                    onToggle = { _, _ -> },
                    onSeek = { _, _ -> },
                    onSpeed = { _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("Play audio").assertIsDisplayed()
    }

    private fun audio() = AudioAttachment(
        url = "https://files.example/song.ogg",
        title = "song.ogg",
        mimeType = "audio/ogg",
    )
}
