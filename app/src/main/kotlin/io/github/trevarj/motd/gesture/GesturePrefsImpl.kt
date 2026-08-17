package io.github.trevarj.motd.gesture

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Own store, isolated from Settings exports so restoring configuration cannot enable this lab.
private val Context.gestureDataStore by preferencesDataStore("gesture_labs")
private val ENABLED = booleanPreferencesKey("enabled_v1")
private val MENU = stringPreferencesKey("menu_v1")

@Singleton
class GesturePrefsImpl @Inject constructor(
    @ApplicationContext context: Context,
) : GesturePrefs {
    private val store = context.gestureDataStore

    override val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    // A menu that cannot be decoded falls back to the default rather than leaving the orb dead.
    override val menu: Flow<GestureMenuConfig> = store.data.map { decodeGestureMenu(it[MENU]) }

    override suspend fun setMenu(config: GestureMenuConfig) {
        store.edit { prefs ->
            if (config == GestureMenuConfig()) prefs.remove(MENU) else prefs[MENU] = encodeGestureMenu(config)
        }
    }

    override suspend fun replaceMenu(transform: (GestureMenuConfig) -> GestureMenuConfig) {
        store.edit { prefs ->
            val next = transform(decodeGestureMenu(prefs[MENU]))
            if (next == GestureMenuConfig()) prefs.remove(MENU) else prefs[MENU] = encodeGestureMenu(next)
        }
    }
}
