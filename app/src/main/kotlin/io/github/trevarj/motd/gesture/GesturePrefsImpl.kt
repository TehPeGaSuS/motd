package io.github.trevarj.motd.gesture

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Own store, isolated from Settings exports so restoring configuration cannot enable this lab.
private val Context.gestureDataStore by preferencesDataStore("gesture_labs")
private val ENABLED = booleanPreferencesKey("enabled_v1")

@Singleton
class GesturePrefsImpl @Inject constructor(
    @ApplicationContext context: Context,
) : GesturePrefs {
    private val store = context.gestureDataStore

    override val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }
}
