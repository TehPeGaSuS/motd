package io.github.trevarj.motd.data.prefs

import io.github.trevarj.motd.data.db.MotdDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-network watermark for a completed reconnect CHATHISTORY pass. The implementation persists
 * it beside the canonical room cursors so a v10 reset cannot inherit stale DataStore timestamps.
 */
interface HistorySyncPrefs {
    suspend fun lastSuccessfulSync(networkId: Long): Long?
    suspend fun setLastSuccessfulSync(networkId: Long, timestamp: Long)
    suspend fun clear(networkId: Long)
}

object NoopHistorySyncPrefs : HistorySyncPrefs {
    override suspend fun lastSuccessfulSync(networkId: Long): Long? = null
    override suspend fun setLastSuccessfulSync(networkId: Long, timestamp: Long) = Unit
    override suspend fun clear(networkId: Long) = Unit
}

@Singleton
class HistorySyncPrefsImpl @Inject constructor(
    db: MotdDatabase,
) : HistorySyncPrefs {
    private val cursors = db.historyCursorDao()

    override suspend fun lastSuccessfulSync(networkId: Long): Long? =
        cursors.networkLastSuccessfulSync(networkId)

    override suspend fun setLastSuccessfulSync(networkId: Long, timestamp: Long) {
        cursors.setNetworkLastSuccessfulSync(networkId, timestamp)
    }

    override suspend fun clear(networkId: Long) {
        cursors.clearNetwork(networkId)
    }
}
