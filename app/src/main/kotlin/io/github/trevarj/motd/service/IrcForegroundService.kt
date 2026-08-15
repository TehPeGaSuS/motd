package io.github.trevarj.motd.service

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.trevarj.motd.irc.event.IrcClientState
import javax.inject.Inject
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

    override fun onCreate() {
        super.onCreate()
        // Reflect live connection state in the status notification.
        lifecycleScope.launch {
            (connectionManager as? ConnectionManagerImpl)?.connectionStates?.collect { states ->
                updateStatus(states)
            }
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
        val notification = statusNotification(connectionManager.connectionStates.value)
        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is an API 34 constant; only pass the type on 34+.
        // On 29-33 use the 2-arg overload (the manifest still declares foregroundServiceType).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(STATUS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(STATUS_ID, notification)
        }
    }

    /** The one place connection state becomes a status notification, shared by both entry points. */
    private fun statusNotification(states: Map<Long, IrcClientState>): Notification {
        val shape = statusNotificationShape(states)
        return notifications.statusNotification(
            connectedCount = shape.connectedCount,
            reconnecting = shape.reconnecting,
            starting = shape.starting,
        )
    }

    private fun updateStatus(states: Map<Long, IrcClientState>) {
        val notification = statusNotification(states)
        // POST_NOTIFICATIONS is only a runtime permission on API 33+; guard so lint's flow
        // analysis is satisfied and we don't attempt to post the status update without it.
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canPost) {
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(this).notify(STATUS_ID, notification)
            }
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
