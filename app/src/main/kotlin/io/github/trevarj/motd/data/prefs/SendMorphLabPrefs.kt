package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sendMorphLabDataStore by preferencesDataStore("send_morph_lab")
private val ENABLED = booleanPreferencesKey("enabled_v1")

/**
 * The morph send-animation lab switch. Isolated from Settings exports like every lab store, so
 * restoring normal configuration cannot enable this lab; off means the shipped flight animation.
 */
@Singleton
class SendMorphLabPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.sendMorphLabDataStore
    val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }
}
