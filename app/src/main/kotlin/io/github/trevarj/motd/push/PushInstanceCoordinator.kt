package io.github.trevarj.motd.push

import android.util.Log
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE UnifiedPush registration trigger. v1 never called `UnifiedPush.registerApp`;
 * this @Singleton watches the delivery mode and the connectable-network set and reconciles the
 * set of registered UnifiedPush instances (one per connectable network, `instance = networkId`).
 *
 * `start()` is idempotent and invoked from [io.github.trevarj.motd.MotdApplication.onCreate].
 */
@Singleton
class PushInstanceCoordinator
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val networkDao: NetworkDao,
        private val pushPrefs: PushPrefs,
        private val up: UnifiedPushApi,
        private val healthStore: PushHealthStore = NoopPushHealthStore,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val endpointTimeouts = ConcurrentHashMap<Long, Job>()

        @Volatile private var started = false

        /** Idempotent: begins collecting the mode/connectable stream and reconciling registrations. */
        fun start() {
            if (started) return
            started = true
            scope.launch {
                combine(settingsRepository.settings, networkDao.observeAll()) { s, nets ->
                    s.deliveryMode to nets
                }.distinctUntilChanged().collect { (mode, networks) ->
                    val ids = pushEligibleNetworkIds(networks)
                    runCatching {
                        healthStore.retain(networks.map { it.id }.toSet())
                        reconcile(mode, ids)
                    }.onFailure { Log.w(TAG, "push provider reconciliation failed", it) }
                }
            }
        }

        /**
         * Reconcile registered instances against the desired set:
         *  - desired = connectable networks under UNIFIED_PUSH, else empty.
         *  - auto-select the first distributor when one is desired and none is acked (no-op if none).
         *  - registerApp every desired instance.
         *  - unregisterApp everything we hold an endpoint for, or that is connectable, minus desired
         *    (covers mode-off and network-removed deltas, plus stale endpoint hygiene).
         *
         * Public for direct unit tests with a FakeUnifiedPushApi.
         */
        suspend fun reconcile(
            mode: DeliveryMode,
            connectable: Set<Long>,
        ) {
            if (mode != DeliveryMode.UNIFIED_PUSH) {
                cancelEndpointTimeoutsExcept(emptySet())
                reconcileUnifiedPush(emptySet(), connectable)
                return
            }
            reconcileUnifiedPush(connectable, connectable)
        }

        private suspend fun reconcileUnifiedPush(
            desired: Set<Long>,
            connectable: Set<Long>,
        ) {
            if (desired.isNotEmpty() && up.getAckDistributor() == null) {
                // Auto-select only an unambiguous single distributor. Settings presents a chooser when
                // several are installed instead of silently picking an arbitrary package.
                val installed = up.getDistributors()
                if (installed.size != 1) {
                    cancelEndpointTimeoutsExcept(emptySet())
                    return
                }
                up.saveDistributor(installed.single())
            }
            cancelEndpointTimeoutsExcept(desired)
            for (id in desired) {
                if (pushPrefs.endpointFor(id) == null) {
                    armEndpointTimeout(id)
                } else {
                    endpointTimeouts.remove(id)?.cancel()
                }
                up.registerApp(id.toString())
            }
            for (id in (pushPrefs.endpoints().keys + connectable) - desired) {
                up.unregisterApp(id.toString())
            }
        }

        /** Surface a distributor callback that never arrives instead of remaining "requesting" forever. */
        private suspend fun armEndpointTimeout(networkId: Long) {
            if (endpointTimeouts[networkId]?.isActive == true) return
            healthStore.requestingEndpoint(networkId)
            lateinit var timeout: Job
            timeout =
                scope.launch {
                    try {
                        delay(ENDPOINT_TIMEOUT_MS)
                        if (pushPrefs.endpointFor(networkId) == null && networkDao.byId(networkId) != null) {
                            healthStore.failed(networkId, "ENDPOINT_TIMEOUT")
                        }
                    } finally {
                        endpointTimeouts.remove(networkId, timeout)
                    }
                }
            endpointTimeouts.put(networkId, timeout)?.cancel()
        }

        private fun cancelEndpointTimeoutsExcept(keep: Set<Long>) {
            endpointTimeouts.entries.forEach { (networkId, job) ->
                if (networkId !in keep && endpointTimeouts.remove(networkId, job)) job.cancel()
            }
        }

        private companion object {
            const val TAG = "PushCoordinator"
            const val ENDPOINT_TIMEOUT_MS = 45_000L
        }
    }

internal fun pushEligibleNetworkIds(networks: List<io.github.trevarj.motd.data.db.NetworkEntity>): Set<Long> =
    networks
        .asSequence()
        .filter { it.autoConnect && it.role != NetworkRole.BOUNCER_ROOT }
        .map { it.id }
        .toSet()
