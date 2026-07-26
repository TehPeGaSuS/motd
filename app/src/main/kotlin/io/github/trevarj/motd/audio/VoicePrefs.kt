package io.github.trevarj.motd.audio

import io.github.trevarj.motd.attachment.PasteBackendConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class VoiceConfig(
    val encryptionDefault: Boolean = false,
    val rememberedDestination: PasteBackendConfig? = null,
)

interface VoicePrefs {
    val config: Flow<VoiceConfig>
    suspend fun setEncryptionDefault(enabled: Boolean)
    suspend fun setRememberedDestination(config: PasteBackendConfig?)
}
