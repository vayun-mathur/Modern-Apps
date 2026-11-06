package com.vayunmathur.findfamily.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Robolectric in-memory tests for the findfamily Room DAOs: user auto-toggle
 * atomics, [LocationValueDao.getLatest] ranking on `reportedAt` (not
 * measurement time), [NoShowAlertDao] due/fired/rearm, and waypoint +
 * temporary-link CRUD.
 */
@RunWith(AndroidJUnit4::class)
class W5FindFamilyDatabaseTest {

    private lateinit var db: FFDatabase

    private fun user(id: Long = 0L, name: String = "u") = User(
        name = name,
        photo = null,
        locationName = "loc",
        sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION,
        lastLocationChangeTime = Clock.System.now(),
        id = id,
    )

    private fun fix(
        userid: Long,
        sentAt: Long,
        measuredAt: Long = sentAt,
        source: LocationSource = LocationSource.LIVE,
    ) = LocationValue(
        userid = userid,
        coord = Coord(1.0, 2.0),
        speed = 0f,
        acc = 5f,
        timestamp = Instant.fromEpochMilliseconds(measuredAt),
        battery = 50f,
        reportedAt = Instant.fromEpochMilliseconds(sentAt),
        source = source,
    )

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FFDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @AfterTest
    fun close() {
        db.close()
    }

    @Test
    fun userUpsertGetDelete() = runTest {
        val id = db.userDao().upsert(user(name = "alice"))
        assertEquals("alice", db.userDao().getById(id)?.name)
        assertEquals(1, db.userDao().delete(db.userDao().getById(id)!!))
        assertNull(db.userDao().getById(id))
    }

    @Test
    fun manualToggleClearsPendingAutoToggle() = runTest {
        val dao = db.userDao()
        val id = dao.upsert(user())
        dao.setSharingAutoToggleAt(id, 1_000L)
        dao.setSendingEnabledAndClearToggle(id, false)
        val row = dao.getById(id)!!
        assertEquals(false, row.sendingEnabled)
        assertNull(row.sharingAutoToggleAt)
        assertNull(row.sharingAutoToggleWaypointId)
    }

    @Test
    fun dueTimerToggleFlipsOnlyDueRows() = runTest {
        val dao = db.userDao()
        val due = dao.upsert(user(name = "due"))
        val future = dao.upsert(user(name = "future"))
        val never = dao.upsert(user(name = "never"))
        dao.setSharingAutoToggleAt(due, 1_000L)
        dao.setSharingAutoToggleAt(future, 9_999_999L)
        assertEquals(1, dao.applyDueAutoToggles(2_000L))
        assertEquals(false, dao.getById(due)?.sendingEnabled)
        assertNull(dao.getById(due)?.sharingAutoToggleAt)
        assertEquals(true, dao.getById(future)?.sendingEnabled)
        assertEquals(true, dao.getById(never)?.sendingEnabled)
    }

    @Test
    fun arrivalToggleFiresOncePerArrival() = runTest {
        val dao = db.userDao()
        val id = dao.upsert(user())
        dao.setSharingAutoToggleWaypointId(id, 42L)
        assertEquals(1, dao.applyDueArrivalToggles(listOf(42L)))
        assertEquals(false, dao.getById(id)?.sendingEnabled)
        // Trigger cleared on flip, so a second pass does nothing.
        assertEquals(0, dao.applyDueArrivalToggles(listOf(42L)))
        // Rows pointing elsewhere are untouched.
        val other = dao.upsert(user(name = "other"))
        dao.setSharingAutoToggleWaypointId(other, 43L)
        assertEquals(0, dao.applyDueArrivalToggles(listOf(42L)))
        assertEquals(true, dao.getById(other)?.sendingEnabled)
    }

    @Test
    fun timeAndArrivalTogglesAreMutuallyExclusive() = runTest {
        val dao = db.userDao()
        val id = dao.upsert(user())
        dao.setSharingAutoToggleAt(id, 5_000L)
        dao.setSharingAutoToggleWaypointId(id, 7L)
        val row = dao.getById(id)!!
        assertEquals(7L, row.sharingAutoToggleWaypointId)
        assertNull(row.sharingAutoToggleAt)
    }

    @Test
    fun pairIdPinningIsTrustOnFirstUse() = runTest {
        val dao = db.userDao()
        val id = dao.upsert(user())
        dao.pinInboundPairId(id, "first")
        dao.pinInboundPairId(id, "second")
        assertEquals("first", dao.getById(id)?.inboundPairId)
        // Outbound is ours to choose: unconditional set wins.
        dao.setOutboundPairIdIfAbsent(id, "out-1")
        dao.setOutboundPairIdIfAbsent(id, "out-2")
        assertEquals("out-1", dao.getById(id)?.outboundPairId)
        dao.setOutboundPairId(id, "out-3")
        assertEquals("out-3", dao.getById(id)?.outboundPairId)
    }

    @Test
    fun getLatestRanksBySentTimeNotMeasuredTime() = runTest {
        val dao = db.locationValueDao()
        // Live heartbeat measured at t=2000, then a shutdown report carrying a
        // stale fix (measured t=1000) sent at t=3000. The shutdown must win.
        dao.upsert(fix(userid = 1L, sentAt = 2_000_000L, measuredAt = 2_000_000L))
        dao.upsert(
            fix(userid = 1L, sentAt = 3_000_000L, measuredAt = 1_000_000L, source = LocationSource.SHUTDOWN),
        )
        val latest = dao.getLatest().first()
        assertEquals(1, latest.size)
        assertEquals(LocationSource.SHUTDOWN, latest[0].source)
    }

    @Test
    fun getLatestIsPerUser() = runTest {
        val dao = db.locationValueDao()
        dao.upsert(fix(userid = 1L, sentAt = 1_000_000L))
        dao.upsert(fix(userid = 1L, sentAt = 2_000_000L))
        dao.upsert(fix(userid = 2L, sentAt = 1_500_000L))
        val latest = dao.getLatest().first().associateBy { it.userid }
        assertEquals(2, latest.size)
        assertEquals(2_000_000L, latest[1L]?.reportedAt?.toEpochMilliseconds())
        assertEquals(1_500_000L, latest[2L]?.reportedAt?.toEpochMilliseconds())
    }

    @Test
    fun deleteOlderThanUsesMeasurementTime() = runTest {
        val dao = db.locationValueDao()
        dao.upsert(fix(userid = 1L, sentAt = 5_000_000L, measuredAt = 1_000_000L))
        dao.upsert(fix(userid = 1L, sentAt = 5_000_000L, measuredAt = 9_000_000L))
        dao.deleteOlderThan(2_000L)
        val remaining = dao.getByUseridFlow(1L).first()
        assertEquals(1, remaining.size)
        assertEquals(9_000_000L, remaining[0].timestamp.toEpochMilliseconds())
    }

    @Test
    fun noShowAlertDueFiredRearm() = runTest {
        val dao = db.noShowAlertDao()
        val base = Instant.fromEpochSeconds(1_786_570_000L)
        val dueId = dao.upsert(
            NoShowAlert(watchedUserId = 1L, waypointId = null, expectedAt = base, grace = 15.minutes),
        )
        val futureId = dao.upsert(
            NoShowAlert(
                watchedUserId = 1L,
                waypointId = 3L,
                expectedAt = base + 365.days,
                grace = 15.minutes,
            ),
        )
        val due = dao.getDue(base.epochSeconds + 15 * 60 + 1)
        assertEquals(listOf(dueId), due.map { it.id })
        // Concurrent double-claim: only one wins.
        assertEquals(1, dao.markFired(dueId))
        assertEquals(0, dao.markFired(dueId))
        assertTrue(dao.getDue(base.epochSeconds + 15 * 60 + 1).none { it.id == dueId })
        // Rearm makes it due again; future row never fires early.
        assertEquals(1, dao.rearm(dueId))
        assertEquals(1, dao.getDue(base.epochSeconds + 15 * 60 + 1).size)
        assertTrue(dao.get(futureId) != null)
        assertEquals(1, dao.delete(dao.get(futureId)!!))
        assertNull(dao.get(futureId))
    }

    @Test
    fun waypointAndLinkCrud() = runTest {
        val wId = db.waypointDao().upsert(Waypoint("home", 100.0, Coord(1.0, 2.0)))
        assertEquals("home", db.waypointDao().get(wId).name)
        assertEquals(1, db.waypointDao().delete(db.waypointDao().get(wId)))

        val link = TemporaryLink(
            name = "party",
            deleteAt = Clock.System.now(),
            pqcPublicKey = "pub",
            pqcSeed = "seed",
        )
        db.temporaryLinkDao().upsert(link)
        assertEquals(1, db.temporaryLinkDao().getAll().size)
        assertEquals(1, db.temporaryLinkDao().delete(link))
        assertTrue(db.temporaryLinkDao().getAll().isEmpty())
    }

    @Test
    fun userFlowsEmit() = runTest {
        val dao = db.userDao()
        dao.upsert(user(name = "flow"))
        assertEquals(1, dao.getAllFlow().first().size)
        assertEquals("flow", dao.getByIdFlow(dao.getAll().first().id).first()?.name)
    }
}
