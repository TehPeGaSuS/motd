package io.github.trevarj.motd.audio

import android.content.Context
import androidx.datastore.preferences.core.edit
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

    private fun encode(config: VoiceConfig): String = json.encodeToString(config)
    private fun decode(raw: String): Result<VoiceConfig> = runCatching { json.decodeFromString<VoiceConfig>(raw) }
}
