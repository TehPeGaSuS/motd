package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Durable one-shot state for opt-in behavior attached to a newly created network preset. */
interface PresetEnrollmentPrefs {
    /** Mark a newly inserted direct Libera preset as awaiting its first successful registration. */
    suspend fun markLiberaEligible(networkId: Long)

    /**
     * Atomically consume eligibility and record the attempt before any JOIN is written.
     * A true result can be returned at most once for a network id, including across process death.
     */
    suspend fun claimLiberaMotdJoin(networkId: Long): Boolean

    /** Remove unclaimed state when the provisional row is deleted or its endpoint changes. */
    suspend fun revokeLiberaEligibility(networkId: Long)
}

private val Context.presetEnrollmentDataStore by preferencesDataStore("preset_enrollment")
private val LIBERA_ELIGIBLE_IDS = stringSetPreferencesKey("libera_eligible_ids")
private val LIBERA_ATTEMPTED_IDS = stringSetPreferencesKey("libera_attempted_ids")

@Singleton
class PresetEnrollmentPrefsImpl @Inject constructor(
    @ApplicationContext context: Context,
) : PresetEnrollmentPrefs {
    private val store = context.presetEnrollmentDataStore

    override suspend fun markLiberaEligible(networkId: Long) {
        val id = networkId.toString()
        store.edit { prefs ->
            if (id !in prefs[LIBERA_ATTEMPTED_IDS].orEmpty()) {
                prefs[LIBERA_ELIGIBLE_IDS] = prefs[LIBERA_ELIGIBLE_IDS].orEmpty() + id
            }
        }
    }

    override suspend fun claimLiberaMotdJoin(networkId: Long): Boolean {
        val id = networkId.toString()
        var claimed = false
        store.edit { prefs ->
            val eligible = prefs[LIBERA_ELIGIBLE_IDS].orEmpty()
            val attempted = prefs[LIBERA_ATTEMPTED_IDS].orEmpty()
            claimed = id in eligible && id !in attempted
            if (id in eligible) prefs[LIBERA_ELIGIBLE_IDS] = eligible - id
            if (claimed) prefs[LIBERA_ATTEMPTED_IDS] = attempted + id
        }
        return claimed
    }

    override suspend fun revokeLiberaEligibility(networkId: Long) {
        val id = networkId.toString()
        store.edit { prefs ->
            val eligible = prefs[LIBERA_ELIGIBLE_IDS].orEmpty()
            if (id in eligible) prefs[LIBERA_ELIGIBLE_IDS] = eligible - id
        }
    }
}
