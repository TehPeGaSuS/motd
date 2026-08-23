package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.onboardingDataStore by preferencesDataStore("onboarding")
private val COMPLETED = booleanPreferencesKey("completed_v1")

interface OnboardingPrefs {
    val completed: Flow<Boolean>

    suspend fun markCompleted()
}

@Singleton
class OnboardingPrefsImpl
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : OnboardingPrefs {
        private val store = context.onboardingDataStore

        override val completed: Flow<Boolean> =
            store.data.map { prefs ->
                prefs[COMPLETED] ?: false
            }

        override suspend fun markCompleted() {
            store.edit { prefs -> prefs[COMPLETED] = true }
        }
    }
