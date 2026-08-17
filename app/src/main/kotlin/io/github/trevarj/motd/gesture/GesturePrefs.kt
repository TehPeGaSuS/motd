package io.github.trevarj.motd.gesture

import kotlinx.coroutines.flow.Flow

/**
 * Preferences for the experimental gesture lab (radial orb menu).
 *
 * The enabled flag lives in its own store and is deliberately kept out of configuration backups, so
 * restoring normal settings on another device can never switch the lab on (same rule as
 * `AgentwirePrefs`).
 */
interface GesturePrefs {
    /** Whether the gesture orb lab is switched on. Defaults to false. */
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
}
