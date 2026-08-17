package io.github.trevarj.motd.gesture

import io.github.trevarj.motd.gesture.radial.OrbPlacement
import kotlinx.coroutines.flow.Flow

/**
 * Preferences for the experimental gesture lab (radial orb menu).
 *
 * The enabled flag lives in its own store and is deliberately kept out of configuration backups, so
 * restoring normal settings on another device can never switch the lab on (same rule as
 * `AgentwirePrefs`). The [menu] is the opposite case: it is authored work, it does nothing while the
 * lab is off, and it *is* carried in backups.
 */
interface GesturePrefs {
    /** Whether the gesture orb lab is switched on. Defaults to false. */
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)

    /** The user's menu graph, or the built-in default when nothing readable is stored. */
    val menu: Flow<GestureMenuConfig>

    /**
     * Store [config]. A config equal to the default clears the stored menu instead of pinning it, so
     * a user who never edited (or who reset) keeps following the built-in tree as it evolves.
     */
    suspend fun setMenu(config: GestureMenuConfig)

    /** Read-modify-write of the stored menu in one edit, so concurrent saves cannot lose each other. */
    suspend fun replaceMenu(transform: (GestureMenuConfig) -> GestureMenuConfig)

    /** Where the resting orb tab sits. Device-local layout, so it stays out of backups too. */
    val orb: Flow<OrbPlacement>

    suspend fun setOrb(placement: OrbPlacement)
}
