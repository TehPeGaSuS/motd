package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.prefs.InviteEnrollmentCleanup
import io.github.trevarj.motd.data.prefs.NoopBouncerKindPrefs
import io.github.trevarj.motd.data.prefs.NoopInviteEnrollmentCleanup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

// Thin pass-through over NetworkDao. Delete treats a bouncer root and its local child mirrors as
// one local tree; a missing row is a no-op. addNetwork additionally dedups against existing rows
// so re-running onboarding / "Add network" for a server the user already has does not create a
// duplicate NetworkEntity (which would spawn a second actor + socket for the same server).
class NetworkRepositoryImpl
    @Inject
    constructor(
        private val networkDao: NetworkDao,
        private val bouncerKindPrefs: BouncerKindPrefs = NoopBouncerKindPrefs,
        private val inviteEnrollmentCleanup: InviteEnrollmentCleanup = NoopInviteEnrollmentCleanup,
    ) : NetworkRepository {
        private val addMutex = Mutex()

        override fun observeNetworks(): Flow<List<NetworkEntity>> = networkDao.observeAll()

        /**
         * Insert [n], or return the id of an existing equivalent network instead of creating a
         * duplicate. Two rows are "the same server" when [networkIdentityKey] matches (see there for
         * the per-role key). The dedup is at the data layer so every add path (onboarding, Add
         * network, soju child import) is covered transparently and callers keep the "returns the row
         * id" contract — they just get the pre-existing id on a duplicate.
         */
        override suspend fun addNetwork(n: NetworkEntity): Long =
            addMutex.withLock {
                val key = networkIdentityKey(n)
                networkDao.allNow().firstOrNull { networkIdentityKey(it) == key }?.let { return it.id }
                // Appended, never inserted at an arbitrary position: a new network belongs at the end of
                // whatever manual drawer order the user has already arranged.
                networkDao.insertLast(n)
            }

        /**
         * Update every field except the manual drawer position, which only [reorderNetworks] owns. The
         * settings form rebuilds a [NetworkEntity] from scratch (see `buildNetworkEntity`), so a saved
         * edit carries the default `ordering = 0` and would otherwise jump that network to the top of
         * the drawer.
         */
        override suspend fun updateNetwork(n: NetworkEntity) {
            val stored = networkDao.byId(n.id)?.ordering ?: n.ordering
            networkDao.update(n.copy(ordering = stored))
        }

        override suspend fun reorderNetworks(orderedIds: List<Long>) = networkDao.applyOrder(orderedIds)

        override suspend fun deleteNetwork(id: Long) {
            networkDao.deleteLocalTree(id).forEach { deletedId ->
                bouncerKindPrefs.clear(deletedId)
                inviteEnrollmentCleanup.clearNetwork(deletedId)
            }
        }

        override suspend fun networkById(id: Long): NetworkEntity? = networkDao.byId(id)

        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = networkDao.childrenOf(rootId)
    }

/** Normalize a host for identity comparison: trim, drop a trailing dot, lowercase (DNS is
 *  case-insensitive). Hostnames are ASCII so [lowercase] with the default locale is safe. */
internal fun normalizeHost(host: String): String = host.trim().trimEnd('.').lowercase()

/** Normalize a network display name for identity comparison: trim and lowercase. */
internal fun normalizeNetworkName(name: String): String = name.trim().lowercase()

/**
 * True when [name] collides (case-insensitively) with an existing DIRECT/BOUNCER_ROOT network.
 * Lets a caller (the "Add network" form) surface a clear error before [NetworkRepositoryImpl.addNetwork]
 * would otherwise silently reuse that row instead of creating a new one.
 */
fun networkNameTaken(
    name: String,
    existing: List<NetworkEntity>,
): Boolean {
    val normalized = normalizeNetworkName(name)
    return existing.any { it.role != NetworkRole.BOUNCER_CHILD && normalizeNetworkName(it.name) == normalized }
}

/**
 * Stable identity key deciding whether two [NetworkEntity] rows are the same server, used by
 * [NetworkRepositoryImpl.addNetwork] to reject duplicates. Keyed per role:
 *
 * - **BOUNCER_CHILD**: `(parentId, bouncerNetId)` — a child is one bouncer-side network under one
 *   root, regardless of host (the mirror may not know the host yet). Guards both the onboarding
 *   import loop and the notify-mirror racing to insert the same child. This is an internal sync
 *   mechanism (soju LISTNETWORKS import), not a user-facing add path, so it is untouched by the
 *   name-based scheme below.
 * - **BOUNCER_ROOT** and **DIRECT**: `name` alone. Users are responsible for filling in
 *   host/port/nick/credentials correctly; matching on those fields silently merged distinct rows
 *   that legitimately share an endpoint (e.g. two accounts on the same relay), which read as a
 *   broken "Add network" with no feedback. A duplicate *name* is still rejected so re-running
 *   onboarding for a server the user already added (e.g. picking the Libera preset twice) reuses
 *   the existing row instead of creating a same-named duplicate.
 */
internal fun networkIdentityKey(n: NetworkEntity): String =
    when (n.role) {
        NetworkRole.BOUNCER_CHILD -> {
            "child|${n.parentId}|${n.bouncerNetId.orEmpty()}"
        }

        NetworkRole.BOUNCER_ROOT, NetworkRole.DIRECT -> {
            "name|${normalizeNetworkName(n.name)}"
        }
    }
