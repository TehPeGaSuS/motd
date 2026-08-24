package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkBufferToolRow
import io.github.trevarj.motd.data.db.NetworkIgnoreDao
import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class NetworkIgnoreRepositoryImpl
    @Inject
    constructor(
        private val ignoreDao: NetworkIgnoreDao,
        private val bufferDao: BufferDao,
        private val cache: NetworkIgnoreCache,
    ) : NetworkIgnoreRepository {
        override fun observeIgnores(networkId: Long): Flow<List<NetworkIgnoreEntity>> = ignoreDao.observeForNetwork(networkId)

        override fun observeBuffers(networkId: Long): Flow<List<NetworkBufferToolRow>> = bufferDao.observeNetworkBufferTools(networkId)

        override suspend fun addIgnore(
            networkId: Long,
            pattern: String,
        ): Result<Unit> =
            normalizeIgnorePattern(pattern).mapCatching { normalized ->
                ignoreDao
                    .upsert(
                        NetworkIgnoreEntity(
                            networkId = networkId,
                            pattern = normalized,
                            enabled = true,
                            createdAt = System.currentTimeMillis(),
                        ),
                    ).let { cache.invalidate(networkId) }
            }

        override suspend fun setIgnoreEnabled(
            id: Long,
            enabled: Boolean,
        ) {
            val networkId = ignoreDao.networkIdFor(id)
            ignoreDao.setEnabled(id, enabled)
            networkId?.let(cache::invalidate)
        }

        override suspend fun deleteIgnore(id: Long) {
            val networkId = ignoreDao.networkIdFor(id)
            ignoreDao.delete(id)
            networkId?.let(cache::invalidate)
        }

        override suspend fun setMuted(
            bufferId: Long,
            muted: Boolean,
        ): MuteBacklogSuppression? = bufferDao.setMuted(bufferDao.canonicalId(bufferId) ?: bufferId, muted)

        override suspend fun restoreMuteBacklog(suppression: MuteBacklogSuppression) {
            bufferDao.restoreLocalUnreadFloor(suppression.bufferId, suppression.previousFloorTime)
        }
    }
