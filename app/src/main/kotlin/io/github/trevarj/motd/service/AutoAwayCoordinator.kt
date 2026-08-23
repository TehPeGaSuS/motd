package io.github.trevarj.motd.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** The three preferences that drive auto-away, isolated so unrelated settings edits cannot rearm it. */
internal data class AutoAwayConfig(
    val enabled: Boolean,
    val minutes: Int,
    val message: String,
)

internal fun autoAwayConfig(settings: Settings): AutoAwayConfig = AutoAwayConfig(settings.autoAwayEnabled, settings.autoAwayMinutes, settings.autoAwayMessage)

/**
 * Marks the user away on every connected network once the app has been backgrounded long enough,
 * and brings them back when the app returns.
 *
 * Two rules keep the feature from stepping on a deliberate away state:
 *
 * - auto-away never overwrites an existing away, so an away set by hand, by another bouncer client,
 *   or replayed from soju's stored away message is left alone;
 * - auto-back only sends "back" to networks this coordinator marked itself and that the server still
 *   reports as away.
 *
 * Markers live in memory only. Losing them to process death fails safe: the user stays away until a
 * manual `/back`, which is the harmless direction of the error.
 *
 * A network that connects (or reconnects) while the app is still backgrounded is marked away too --
 * that is the whole point of a bouncer session that outlives the foreground -- unless it comes back
 * already away, in which case [autoAwayTargets] excludes it and it is never auto-backed.
 */
@Singleton
class AutoAwayCoordinator private constructor(
    private val connections: ConnectionManager,
    private val settingsRepository: SettingsRepository,
    private val visibility: AppVisibility,
    private val diagnostics: DiagnosticLogger,
    private val scope: CoroutineScope,
    private val defaultMessage: () -> String,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        connections: ConnectionManager,
        settingsRepository: SettingsRepository,
        visibility: AppVisibility,
        diagnostics: DiagnosticLogger,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        connections = connections,
        settingsRepository = settingsRepository,
        visibility = visibility,
        diagnostics = diagnostics,
        scope = scope,
        defaultMessage = { context.getString(R.string.auto_away_default_message) },
    )

    private val started = AtomicBoolean(false)

    /**
     * Networks whose away state this coordinator caused, and the auto-away writes still awaiting a
     * server confirmation. Both are only ever touched from the single collector coroutine below.
     */
    private var markers: Set<Long> = emptySet()
    private var requested: Set<Long> = emptySet()

    /** Start observing visibility for the process lifetime. Safe to call repeatedly. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { observe() }
    }

    internal suspend fun observe() {
        combine(
            settingsRepository.settings.map(::autoAwayConfig).distinctUntilChanged(),
            visibility.onScreen,
            ::Pair,
        ).collectLatest { (config, onScreen) ->
            // collectLatest is the timer: any visibility or auto-away preference change cancels the
            // pending countdown and re-decides from the new state.
            if (onScreen) {
                returnFromAway()
            } else if (config.enabled) {
                markAwayWhileBackgrounded(config)
            }
        }
    }

    /** Foreground: drop the timer's bookkeeping and clear only the away states we caused. */
    private suspend fun returnFromAway() {
        requested = emptySet()
        val targets = autoBackTargets(markers, connections.selfAwayStates.value.keys)
        markers = emptySet()
        if (targets.isEmpty()) return
        diagnostics.record("away", "auto_back") { mapOf("networks" to targets.size) }
        targets.forEach { writeAway(it, null) }
    }

    private suspend fun markAwayWhileBackgrounded(config: AutoAwayConfig) {
        delay(autoAwayDelayMillis(config.minutes))
        val text = autoAwayText(config.message, defaultMessage())
        diagnostics.record("away", "auto_away_armed") { mapOf("minutes" to config.minutes) }
        combine(connections.connectionStates, connections.selfAwayStates, ::Pair).collect { (states, away) ->
            val ready = states.filterValues { it is IrcClientState.Ready }.keys
            val awayNetworks = away.keys
            // A confirmed request becomes a marker; a network that stopped being away stops being
            // ours, so a manual /back (here or on another client) is never undone by auto-back.
            markers = retainedMarkers(markers, awayNetworks) + (requested intersect awayNetworks)
            requested = retainedAwayRequests(requested, ready)
            val targets = autoAwayTargets(ready, awayNetworks) - requested
            if (targets.isEmpty()) return@collect
            requested = requested + targets
            diagnostics.record("away", "auto_away_sent") { mapOf("networks" to targets.size) }
            targets.forEach { writeAway(it, text) }
        }
    }

    /**
     * A write that never reaches the wire simply leaves the network un-marked: [selfAwayStates]
     * moves on server confirmation only, so a failure cannot desync auto-back.
     */
    private suspend fun writeAway(
        networkId: Long,
        message: String?,
    ) {
        try {
            connections.setAway(networkId, message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            diagnostics.record("away", "auto_away_write_failed") { mapOf("back" to (message == null)) }
        }
    }

    internal companion object {
        fun forTest(
            connections: ConnectionManager,
            settingsRepository: SettingsRepository,
            visibility: AppVisibility,
            scope: CoroutineScope,
            defaultMessage: () -> String,
            diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
        ): AutoAwayCoordinator =
            AutoAwayCoordinator(
                connections = connections,
                settingsRepository = settingsRepository,
                visibility = visibility,
                diagnostics = diagnostics,
                scope = scope,
                defaultMessage = defaultMessage,
            )
    }
}
