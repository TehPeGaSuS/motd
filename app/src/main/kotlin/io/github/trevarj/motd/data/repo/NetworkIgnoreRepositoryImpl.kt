package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkBufferToolRow
import io.github.trevarj.motd.data.db.NetworkIgnoreDao
import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class NetworkIgnoreRepositoryImpl @Inject constructor(
    private val ignoreDao: NetworkIgnoreDao,
    private val bufferDao: BufferDao,
) : NetworkIgnoreRepository {
    override fun observeIgnores(networkId: Long): Flow<List<NetworkIgnoreEntity>> =
        ignoreDao.observeForNetwork(networkId)

    override fun observeBuffers(networkId: Long): Flow<List<NetworkBufferToolRow>> =
        bufferDao.observeNetworkBufferTools(networkId)

    override suspend fun addIgnore(networkId: Long, pattern: String): Result<Unit> =
        normalizeIgnorePattern(pattern).mapCatching { normalized ->
            ignoreDao.upsert(
                NetworkIgnoreEntity(
                    networkId = networkId,
                    pattern = normalized,
                    enabled = true,
                    createdAt = System.currentTimeMillis(),
                ),
            ).let { }
        }

    override suspend fun setIgnoreEnabled(id: Long, enabled: Boolean) {
        ignoreDao.setEnabled(id, enabled)
    }

    override suspend fun deleteIgnore(id: Long) {
        ignoreDao.delete(id)
    }

    override suspend fun setMuted(bufferId: Long, muted: Boolean): MuteBacklogSuppression? =
        bufferDao.setMuted(bufferDao.canonicalId(bufferId) ?: bufferId, muted)

    override suspend fun restoreMuteBacklog(suppression: MuteBacklogSuppression) {
        bufferDao.restoreLocalUnreadFloor(suppression.bufferId, suppression.previousFloorTime)
    }
}
