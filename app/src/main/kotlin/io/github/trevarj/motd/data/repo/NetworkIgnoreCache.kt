package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.NetworkIgnoreDao
import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Process-local snapshot invalidated by every production ignore mutation. */
@Singleton
class NetworkIgnoreCache
    @Inject
    constructor(
        private val dao: NetworkIgnoreDao,
    ) {
        private val entries = ConcurrentHashMap<Long, List<NetworkIgnoreEntity>>()

        suspend fun enabledForNetwork(networkId: Long): List<NetworkIgnoreEntity> {
            entries[networkId]?.let { return it }
            val loaded = dao.enabledForNetwork(networkId)
            return entries.putIfAbsent(networkId, loaded) ?: loaded
        }

        fun invalidate(networkId: Long) {
            entries.remove(networkId)
        }
    }
