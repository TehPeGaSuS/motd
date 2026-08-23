package io.github.trevarj.motd.gesture.radial

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.gesture.GestureAction
import io.github.trevarj.motd.gesture.GestureActionDispatcher
import io.github.trevarj.motd.gesture.GestureMenuProviders
import io.github.trevarj.motd.gesture.GestureNavRequest
import io.github.trevarj.motd.gesture.GestureNode
import io.github.trevarj.motd.gesture.GesturePrefs
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the orb needs before a finger touches it. */
data class GestureOrbUiState(
    val enabled: Boolean = false,
    val placement: OrbPlacement = OrbPlacement(),
    val appearance: AppearanceConfig = AppearanceConfig(),
)

/**
 * State and side effects for the gesture orb overlay.
 *
 * The menu is resolved on demand rather than kept live: [resolveMenu] takes one snapshot when the
 * hold arms, so the ring a finger commits to cannot be reshuffled underneath it by a chat going
 * unread mid-gesture.
 */
@HiltViewModel
class GestureOrbViewModel internal constructor(
    private val prefs: GesturePrefs,
    private val providers: GestureMenuProviders,
    private val dispatcher: GestureActionDispatcher,
    private val connections: ConnectionManager,
    appearance: AppearancePrefs,
    /** Label a self-away toggle wears while we are already away — it will flip the other way. */
    private val backLabel: String,
) : ViewModel() {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        prefs: GesturePrefs,
        providers: GestureMenuProviders,
        dispatcher: GestureActionDispatcher,
        connections: ConnectionManager,
        appearance: AppearancePrefs,
    ) : this(
        prefs = prefs,
        providers = providers,
        dispatcher = dispatcher,
        connections = connections,
        appearance = appearance,
        backLabel = context.getString(R.string.gesture_away_leaf_back),
    )

    val state: StateFlow<GestureOrbUiState> =
        combine(
            prefs.enabled,
            prefs.orb,
            appearance.config,
        ) { enabled, orb, config -> GestureOrbUiState(enabled, orb, config) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GestureOrbUiState())

    /** Navigation the dispatcher wants performed; the host owns the `NavController` that can do it. */
    val navRequests: SharedFlow<GestureNavRequest> = dispatcher.navRequests

    /** Snapshot of the whole menu with providers fanned out and state-dependent labels resolved. */
    suspend fun resolveMenu(): RadialEntry {
        val config = prefs.menu.first()
        val away = anySelfAway(connections.connectionStates.value, connections.selfAwayStates.value)
        return resolveNode(config.root, away)
    }

    fun execute(action: GestureAction) {
        viewModelScope.launch { dispatcher.execute(action) }
    }

    fun setPlacement(placement: OrbPlacement) {
        viewModelScope.launch { prefs.setOrb(placement) }
    }

    private suspend fun resolveNode(
        node: GestureNode,
        away: Boolean,
    ): RadialEntry =
        when (node) {
            is GestureNode.Submenu -> {
                radialEntryOf(node, node.children.map { resolveNode(it, away) }, away, backLabel)
            }

            // Resolved leaves are throwaway: they never nest, so no further walk is needed.
            is GestureNode.Provider -> {
                radialEntryOf(node, providers.resolveProvider(node).map { radialEntryOf(it, emptyList(), away, backLabel) }, away, backLabel)
            }

            else -> {
                radialEntryOf(node, emptyList(), away, backLabel)
            }
        }
}

/** Flatten one menu node into the slice the machine draws. */
internal fun radialEntryOf(
    node: GestureNode,
    children: List<RadialEntry>,
    away: Boolean,
    backLabel: String,
): RadialEntry =
    RadialEntry(
        id = node.id,
        label = entryLabel(node, away, backLabel),
        icon = node.icon,
        children = children,
        action = (node as? GestureNode.Leaf)?.action,
    )

/**
 * A self-away toggle has to say which way it will flip.
 *
 * The authored label is user data and names the away direction, so it stands while we are present;
 * the return direction is app state, not authorship, and gets the localized string.
 */
internal fun entryLabel(
    node: GestureNode,
    away: Boolean,
    backLabel: String,
): String = if (away && (node as? GestureNode.Leaf)?.action is GestureAction.ToggleAway) backLabel else node.label

/**
 * True when at least one connected network still reports us away.
 *
 * Deliberately the same test `GestureActionDispatcher` applies when it decides which way a toggle
 * goes, so the slice can never promise the opposite of what pressing it does.
 */
internal fun anySelfAway(
    states: Map<Long, IrcClientState>,
    away: Map<Long, String?>,
): Boolean = states.any { (networkId, state) -> state is IrcClientState.Ready && networkId in away }
