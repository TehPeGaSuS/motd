package io.github.trevarj.motd.audio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.normalizedConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.voiceDataStore by preferencesDataStore("voice")
private val CONFIG = stringPreferencesKey("config_v1")
private val NOISE_FALLBACK_NOTICED = booleanPreferencesKey("noise_fallback_noticed")

@Singleton
class VoicePrefsImpl @Inject constructor(
    @ApplicationContext context: Context,
) : VoicePrefs {
    private val store = context.voiceDataStore
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override val config: Flow<VoiceConfig> = store.data.map { prefs ->
        prefs[CONFIG]?.let(::decode)?.getOrNull() ?: VoiceConfig()
    }

    override suspend fun setEncryptionDefault(enabled: Boolean) {
        store.edit { prefs ->
            val current = prefs[CONFIG]?.let(::decode)?.getOrNull() ?: VoiceConfig()
            prefs[CONFIG] = encode(current.copy(encryptionDefault = enabled))
        }
    }

    override suspend fun setRememberedDestination(config: PasteBackendConfig?) {
        store.edit { prefs ->
            val current = prefs[CONFIG]?.let(::decode)?.getOrNull() ?: VoiceConfig()
            prefs[CONFIG] = encode(current.copy(rememberedDestination = config?.let(::normalizedConfig)))
        }
    }

    override suspend fun setQuality(quality: VoiceRecordingQuality) {
        update { it.copy(quality = quality) }
    }

    override suspend fun setNoiseReduction(enabled: Boolean) {
        update { it.copy(noiseReduction = enabled) }
    }

    override suspend fun replace(config: VoiceConfig) {
        store.edit { prefs -> prefs[CONFIG] = encode(config) }
    }

    override suspend fun takeNoiseFallbackNotice(): Boolean {
        var show = false
        store.edit { prefs ->
            if (prefs[NOISE_FALLBACK_NOTICED] != true) {
                prefs[NOISE_FALLBACK_NOTICED] = true
                show = true
            }
        }
        return show
    }

    private suspend fun update(transform: (VoiceConfig) -> VoiceConfig) {
        store.edit { prefs ->
            val current = prefs[CONFIG]?.let(::decode)?.getOrNull() ?: VoiceConfig()
            prefs[CONFIG] = encode(transform(current))
        }
    }

    private fun encode(config: VoiceConfig): String = json.encodeToString(config)
    private fun decode(raw: String): Result<VoiceConfig> = runCatching { json.decodeFromString<VoiceConfig>(raw) }
}
