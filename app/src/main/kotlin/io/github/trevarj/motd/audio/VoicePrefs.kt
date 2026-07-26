package io.github.trevarj.motd.audio

import io.github.trevarj.motd.attachment.PasteBackendConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class VoiceRecordingQuality {
    DATA_SAVER,
    BALANCED,
    HIGH,
}

@Serializable
data class VoiceConfig(
    val encryptionDefault: Boolean = false,
    val rememberedDestination: PasteBackendConfig? = null,
    val quality: VoiceRecordingQuality = VoiceRecordingQuality.BALANCED,
    val noiseReduction: Boolean = true,
)

interface VoicePrefs {
    val config: Flow<VoiceConfig>
    suspend fun setEncryptionDefault(enabled: Boolean)
    suspend fun setRememberedDestination(config: PasteBackendConfig?)
    suspend fun setQuality(quality: VoiceRecordingQuality)
    suspend fun setNoiseReduction(enabled: Boolean)
    suspend fun replace(config: VoiceConfig)
    suspend fun takeNoiseFallbackNotice(): Boolean
}
