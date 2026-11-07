package com.vayunmathur.findfamily.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for all FindFamily persisted data.
 *
 * Owns the one [FFDatabase] instance (via [RoomRepository]) and is the only place
 * the four DAOs are touched. The app (ViewModel), the background
 * [com.vayunmathur.findfamily.util.LocationTrackingService], networking, the
 * assistant intents, `LocationServiceController`, and the tracker all read/write
 * through here — so the UI and the service share one live, invalidation-backed
 * view of the data.
 *
 * Read access is exposed as cold [Flow]s (consumers apply `stateIn`/`flatMapLatest`
 * as before). Writes are `suspend` wrappers over the existing atomic DAO queries,
 * so no caller ever holds a DAO directly.
 */
class FindFamilyRepository internal constructor(
    context: Context,
    /** Test seam: an externally built database (e.g. Room in-memory) instead of the SQLCipher one. */
    private val databaseOverride: FFDatabase? = null,
) : RoomRepository<FFDatabase>(context, FFDatabase::class) {

    private val daoDb: FFDatabase get() = databaseOverride ?: db

    private val userDao: UserDao get() = daoDb.userDao()
    private val waypointDao: WaypointDao get() = daoDb.waypointDao()
    private val locationValueDao: LocationValueDao get() = daoDb.locationValueDao()
    private val temporaryLinkDao: TemporaryLinkDao get() = daoDb.temporaryLinkDao()
    private val noShowAlertDao: NoShowAlertDao get() = daoDb.noShowAlertDao()

    // ------------------------------------------------------------------
    // Read flows (cold)
    // ------------------------------------------------------------------

    val users: Flow<List<User>> get() = userDao.getAllFlow()
    val waypoints: Flow<List<Waypoint>> get() = waypointDao.getAllFlow()

    /** Share-link rows, owned by [temporaryLinkStore]. */
    val temporaryLinks: Flow<List<TemporaryLink>> get() = temporaryLinkStore.allFlow
    /** No-show watch rows, owned by [noShowAlertStore]. */
    val noShowAlerts: Flow<List<NoShowAlert>> get() = noShowAlertStore.allFlow

    /** Latest [LocationValue] per user id (the "present" position of everyone). */
    val latestLocationByUser: Flow<Map<Long, LocationValue>>
        get() = locationValueDao.getLatest().map { list -> list.associateBy { it.userid } }

    /** Full location history for one user (the history scrubber). */
    fun locationHistory(userId: Long): Flow<List<LocationValue>> =
        locationValueDao.getByUseridFlow(userId)

    // ------------------------------------------------------------------
    // User reads / writes
    // ------------------------------------------------------------------

    suspend fun getUser(id: Long): User? = userDao.getById(id)
    suspend fun getAllUsers(): List<User> = userDao.getAll()
    suspend fun upsertUser(user: User): Long = userDao.upsert(user)
    suspend fun insertUsersIgnore(users: List<User>) = userDao.insertAllIgnore(users)

    /**
     * Delete a person and everything stored about them: their location
     * history and any no-show watches on them. Without the cascade the
     * precise history outlived the removal (until the 7-day sweep) and
     * silently resurrected if the same ID was ever re-added.
     */
    suspend fun deleteUser(user: User): Int {
        val deleted = userDao.delete(user)
        locationValueDao.deleteByUserid(user.id)
        noShowAlertDao.deleteByWatchedUserId(user.id)
        return deleted
    }

    suspend fun updateLocationMeta(
        id: Long,
        locationName: String,
        lastWaypointId: Long?,
        lastLocationChangeTime: Long,
    ) = userDao.updateLocationMeta(id, locationName, lastWaypointId, lastLocationChangeTime)

    suspend fun setPlatform(id: Long, platform: String) = userDao.setPlatform(id, platform)
    suspend fun setPqcEncryptionKey(id: Long, pqcEncryptionKey: String) =
        userDao.setPqcEncryptionKey(id, pqcEncryptionKey)

    /** Pin the inbound pair ID expected from a peer (once — guarded by IS NULL in SQL). */
    suspend fun pinInboundPairId(id: Long, pairId: String) = userDao.pinInboundPairId(id, pairId)

    /** Set our outbound pair ID for a peer (unconditional — our own secret, our choice). */
    suspend fun setOutboundPairId(id: Long, pairId: String) = userDao.setOutboundPairId(id, pairId)

    /** Fill our outbound pair ID only when absent (generate-on-first-send for legacy rows). */
    suspend fun setOutboundPairIdIfAbsent(id: Long, pairId: String) =
        userDao.setOutboundPairIdIfAbsent(id, pairId)

    suspend fun setSharingAutoToggleAt(id: Long, atEpochSeconds: Long?) =
        userDao.setSharingAutoToggleAt(id, atEpochSeconds)

    suspend fun setSharingAutoToggleWaypointId(id: Long, waypointId: Long?) =
        userDao.setSharingAutoToggleWaypointId(id, waypointId)

    suspend fun setSendingEnabledAndClearToggle(id: Long, enabled: Boolean) =
        userDao.setSendingEnabledAndClearToggle(id, enabled)

    suspend fun applyDueAutoToggles(nowEpochSeconds: Long): Int =
        userDao.applyDueAutoToggles(nowEpochSeconds)

    suspend fun applyDueArrivalToggles(insideWaypointIds: List<Long>): Int =
        userDao.applyDueArrivalToggles(insideWaypointIds)

    suspend fun updateContactNamePhoto(id: Long, name: String, photo: String?) =
        userDao.updateContactNamePhoto(id, name, photo)

    // ------------------------------------------------------------------
    // Waypoint reads / writes
    // ------------------------------------------------------------------

    suspend fun getAllWaypoints(): List<Waypoint> = waypointDao.getAll()
    suspend fun getWaypoint(id: Long): Waypoint = waypointDao.get(id)
    suspend fun upsertWaypoint(waypoint: Waypoint): Long = waypointDao.upsert(waypoint)
    suspend fun deleteWaypoint(waypoint: Waypoint): Int = waypointDao.delete(waypoint)

    // ------------------------------------------------------------------
    // LocationValue reads / writes
    // ------------------------------------------------------------------

    suspend fun upsertLocation(value: LocationValue): Long = locationValueDao.upsert(value)
    suspend fun upsertLocations(values: List<LocationValue>) = locationValueDao.upsertAll(values)

    /** One-shot snapshot of the latest location per user (non-Flow). */
    suspend fun latestLocationsOnce(): List<LocationValue> = locationValueDao.getLatest().first()

    suspend fun deleteLocationsOlderThan(cutoffEpochSeconds: Long) =
        locationValueDao.deleteOlderThan(cutoffEpochSeconds)

    /**
     * Share-link rows. Exposed as a store (rather than flat repository methods)
     * so the repository stays under the function cap — call sites use
     * `repository.temporaryLinkStore.*`.
     */
    internal val temporaryLinkStore: TemporaryLinkStore get() = TemporaryLinkStore(temporaryLinkDao)

    /**
     * No-show watch rows. Same arrangement as [temporaryLinkStore]:
     * call sites use `repository.noShowAlertStore.*`.
     */
    internal val noShowAlertStore: NoShowAlertStore get() = NoShowAlertStore(noShowAlertDao)

    companion object {
        @Volatile
        private var instance: FindFamilyRepository? = null

        /** The process-wide singleton repository. */
        fun get(context: Context): FindFamilyRepository =
            instance ?: synchronized(this) {
                instance ?: FindFamilyRepository(context).also { instance = it }
            }
    }
}

/** Thin facade over [TemporaryLinkDao], owned by [FindFamilyRepository]. */
internal class TemporaryLinkStore(private val dao: TemporaryLinkDao) {
    val allFlow: Flow<List<TemporaryLink>> get() = dao.getAllFlow()
    suspend fun getAll(): List<TemporaryLink> = dao.getAll()
    suspend fun upsert(link: TemporaryLink): Long = dao.upsert(link)
    suspend fun delete(link: TemporaryLink): Int = dao.delete(link)
}

/** Thin facade over [NoShowAlertDao], owned by [FindFamilyRepository]. */
internal class NoShowAlertStore(private val dao: NoShowAlertDao) {
    val allFlow: Flow<List<NoShowAlert>> get() = dao.getAllFlow()
    suspend fun get(id: Long): NoShowAlert? = dao.get(id)
    suspend fun getAll(): List<NoShowAlert> = dao.getAll()
    suspend fun getDue(nowEpochSeconds: Long): List<NoShowAlert> = dao.getDue(nowEpochSeconds)
    suspend fun upsert(alert: NoShowAlert): Long = dao.upsert(alert)
    suspend fun delete(alert: NoShowAlert): Int = dao.delete(alert)
    suspend fun markFired(id: Long): Int = dao.markFired(id)
    suspend fun rearm(id: Long): Int = dao.rearm(id)
}
