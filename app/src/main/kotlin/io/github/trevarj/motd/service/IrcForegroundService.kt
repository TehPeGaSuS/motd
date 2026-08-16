package io.github.trevarj.motd.service

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.event.IrcClientState
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground-service keeper for the connection subsystem (plans/05). Thin [LifecycleService]:
 * onStartCommand → startForeground(status) + connectionManager.startAll(); explicit [ACTION_STOP] →
 * stopAll() + stopSelf(). Service removal itself never stops the manager (see the note above the
 * companion). START_STICKY so Android restarts it after a kill while PERSISTENT_SOCKET is in
 * effect; a user-initiated stop returns START_NOT_STICKY instead.
 */
@AndroidEntryPoint
class IrcForegroundService : LifecycleService() {

    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var notifications: MotdNotifications
    @Inject lateinit var diagnostics: DiagnosticLogger

    /**
     * The shape currently on screen, whichever entry point put it there. Confined to the main
     * thread: [onStartCommand] is a main-thread callback and [lifecycleScope] dispatches there.
     */
    private var postedShape: StatusNotificationShape? = null

    override fun onCreate() {
        super.onCreate()
        // Reflect live connection state in the status notification, conflated on the wording rather
        // than on the raw state map — see [statusNotificationShapes].
        lifecycleScope.launch {
            val states = (connectionManager as? ConnectionManagerImpl)?.connectionStates
                ?: return@launch
            statusNotificationShapes(states).collect { shape -> updateStatus(shape) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            lifecycleScope.launch {
                connectionManager.stopAll()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        startAsForeground()
        lifecycleScope.launch { connectionManager.startAll() }
        return START_STICKY
    }

    // The merged manifest declares specialUse; AGP lint loses that declaration when analyzing the
    // service in isolation, so the check is suppressed here rather than disabled project-wide.
    @SuppressLint("ForegroundServiceType")
    private fun startAsForeground() {
        // Seeded from the CURRENT connection state rather than from a fixed "starting" shape.
        // onStartCommand is re-entered on an already-running session — every app foreground re-arms
        // the keeper under PERSISTENT_SOCKET, and START_STICKY redelivers after a kill — while
        // connectionStates is a conflated StateFlow that emits nothing further on a stable session.
        // A generic notification posted here therefore had nothing to repaint it: "Connected to 3
        // networks" reverted to "Keeping chats connected" on every foreground and stayed there.
        // Still correct for the cold start this was written for: an empty state map is "starting".
        val shape = statusNotificationShape(connectionManager.connectionStates.value)
        val notification = statusNotification(shape)
        val started = startForegroundSafely(diagnostics, source = "service") {
            // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is an API 34 constant; only pass the type on 34+.
            // On 29-33 use the 2-arg overload (the manifest still declares foregroundServiceType).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(STATUS_ID, notification)
            }
        }
        // startForeground posts STATUS_ID itself, so seed the shared dedupe state with what is now
        // on screen. A refused start posts nothing and must leave the collector free to repost.
        if (started) postedShape = shape
    }

    /** The one place connection state becomes a status notification, shared by both entry points. */
    private fun statusNotification(shape: StatusNotificationShape): Notification =
        notifications.statusNotification(
            connectedCount = shape.connectedCount,
            reconnecting = shape.reconnecting,
            starting = shape.starting,
        )

    private fun updateStatus(shape: StatusNotificationShape) {
        // [statusNotificationShapes] already drops the collector's own repeats; this second check is
        // what makes both entry points share one dedupe state, since startAsForeground posts out of
        // band from that flow.
        if (shape == postedShape) return
        val notification = statusNotification(shape)
        // POST_NOTIFICATIONS is only a runtime permission on API 33+; guard so lint's flow
        // analysis is satisfied and we don't attempt to post the status update without it.
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canPost) {
            val posted = runCatching {
                androidx.core.app.NotificationManagerCompat.from(this).notify(STATUS_ID, notification)
            }.isSuccess
            // Only a notification that actually reached the shade may suppress the next repost.
            if (posted) postedShape = shape
        }
    }

    // Service removal during a fully verified push hand-off must not disable the singleton
    // connection subsystem. Explicit ACTION_STOP performs stopAll above; process death naturally
    // tears down both service and manager together.

    companion object {
        const val STATUS_ID = 1
        const val ACTION_STOP = "io.github.trevarj.motd.service.STOP"
    }
}

private const val FOREGROUND_START_TAG = "ForegroundStart"

/**
 * Run a foreground-service start that the platform is allowed to refuse, and survive the refusal.
 *
 * Android 12+ throws `ForegroundServiceStartNotAllowedException` (and 14+ adds
 * `MissingForegroundServiceTypeException`/`SecurityException`) whenever the caller is no longer
 * eligible to hold a foreground service — a service entered from a background start, or an activity
 * that was backgrounded while its launch coroutine suspended. Losing the socket keeper is a
 * degradation; crashing the process over it is not, so the refusal is contained the same way
 * `IrcForegroundService.updateStatus` already contains its `notify`. Recorded as well as logged
 * because the diagnostic journal is where a keeper that never armed becomes explainable.
 *
 * Returns whether the start was accepted.
 */
internal fun startForegroundSafely(
    diagnostics: DiagnosticLogger,
    source: String,
    start: () -> Unit,
): Boolean = try {
    start()
    true
} catch (cancelled: CancellationException) {
    // Callers run inside coroutines; swallowing cancellation here would break their teardown.
    throw cancelled
} catch (error: Exception) {
    Log.w(FOREGROUND_START_TAG, "foreground service start refused (source=$source)", error)
    diagnostics.record("lifecycle", "foreground_start_refused") {
        mapOf("source" to source, "error" to error::class.simpleName)
    }
    false
}

/** The three arguments [MotdNotifications.statusNotification] takes, derived from live state. */
internal data class StatusNotificationShape(
    val connectedCount: Int,
    val reconnecting: Boolean,
    val starting: Boolean,
)

/**
 * Connection state → status notification wording.
 *
 * "Starting" is the absence of any actor at all, not the act of (re-)starting the service: the
 * service is entered again on a session that is already connected, and reporting that as starting
 * is what reverted a truthful "Connected to N networks" to the generic text. Reconnecting is only
 * reported while nothing is connected, so one flapping network cannot hide the others.
 */
internal fun statusNotificationShape(states: Map<Long, IrcClientState>): StatusNotificationShape {
    val connected = states.values.count { it is IrcClientState.Ready }
    val reconnecting = states.values.any {
        it is IrcClientState.Connecting || it is IrcClientState.Registering
    }
    return StatusNotificationShape(
        connectedCount = connected,
        reconnecting = reconnecting && connected == 0,
        starting = states.isEmpty(),
    )
}

/**
 * Connection state → one emission per distinct piece of status wording.
 *
 * `connectionStates` republishes on every per-network transition and on every lag reading, while the
 * notification reads exactly the three fields of [StatusNotificationShape]. Collecting it raw meant
 * rebuilding and re-posting an identical notification for each of those, which is pure churn: the
 * shade re-animates and the NotificationManager rate limiter is spent on nothing.
 */
internal fun statusNotificationShapes(
    states: Flow<Map<Long, IrcClientState>>,
): Flow<StatusNotificationShape> = states.map(::statusNotificationShape).distinctUntilChanged()
