package io.github.trevarj.motd.data.sync

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared per-network wire gate for reconnect discovery in [io.github.trevarj.motd.service.HistoryResyncCoordinator].
 *
 * Scroll-driven Paging no longer routes through this object: [HistoryPageLoader] owns the mediator's
 * fetch concurrency directly. The coordinator remains its sole caller until Phase 3 collapses that
 * path onto the loader as well.
 */
object CanonicalHistorySingleFlight {
    private val networkLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withNetwork(networkId: Long, block: suspend () -> T): T =
        networkLocks.getOrPut(networkId, ::Mutex).withLock { block() }
}
