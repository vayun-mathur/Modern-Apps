package com.vayunmathur.findfamily.data

import android.content.Context
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class FindFamilyRepositoryTest {

    private lateinit var db: FFDatabase
    private lateinit var repo: FindFamilyRepository
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ctx, FFDatabase::class.java).allowMainThreadQueries().build()
        repo = FindFamilyRepository(ctx, db)
    }

    @AfterTest fun close() { db.close() }

    private fun user(name: String = "alice", status: RequestStatus = RequestStatus.MUTUAL_CONNECTION) = User(
        name = name, photo = null, locationName = "Home", sendingEnabled = true,
        requestStatus = status, lastLocationChangeTime = Clock.System.now(),
    )

    private fun fix(userid: Long, sentAt: Long) = LocationValue(
        userid = userid, coord = Coord(1.0, 2.0), speed = 0f, acc = 5f,
        timestamp = Instant.fromEpochMilliseconds(sentAt), battery = 50f,
        reportedAt = Instant.fromEpochMilliseconds(sentAt), source = LocationSource.LIVE,
    )

    @Test
    fun userCrudAndFlows() = runTest {
        val id = repo.upsertUser(user())
        assertEquals("alice", repo.getUser(id)?.name)
        assertEquals(1, repo.getAllUsers().size)
        assertEquals(1, repo.users.first().size)
        repo.upsertUser(user(name = "alice2").copy(id = id))
        assertEquals("alice2", repo.getUser(id)?.name)
        repo.insertUsersIgnore(listOf(user(name = "bob")))
        assertEquals(2, repo.getAllUsers().size)
        assertEquals(1, repo.deleteUser(repo.getUser(id)!!))
        assertNull(repo.getUser(id))
    }

    @Test
    fun userMetaAndPairIds() = runTest {
        val id = repo.upsertUser(user())
        repo.updateLocationMeta(id, "Office", 7L, 1234L)
        // Instant column: updateLocationMeta takes epoch millis? assert via locationName only
        assertEquals("Office", repo.getUser(id)?.locationName)
        repo.setPlatform(id, "android")
        assertEquals("android", repo.getUser(id)?.platform)
        repo.setPqcEncryptionKey(id, "pqc-bundle")
        assertEquals("pqc-bundle", repo.getUser(id)?.pqcEncryptionKey)
        repo.pinInboundPairId(id, "in-1")
        repo.pinInboundPairId(id, "in-2")
        assertEquals("in-1", repo.getUser(id)?.inboundPairId)
        repo.setOutboundPairIdIfAbsent(id, "out-1")
        repo.setOutboundPairIdIfAbsent(id, "out-2")
        assertEquals("out-1", repo.getUser(id)?.outboundPairId)
        repo.setOutboundPairId(id, "out-3")
        assertEquals("out-3", repo.getUser(id)?.outboundPairId)
        repo.updateContactNamePhoto(id, "Alice A", "photo://x")
        assertEquals("Alice A", repo.getUser(id)?.name)
        assertEquals("photo://x", repo.getUser(id)?.photo)
    }

    @Test
    fun autoToggleRepositoryPaths() = runTest {
        val id = repo.upsertUser(user())
        repo.setSharingAutoToggleAt(id, 1000L)
        var row = repo.getUser(id)!!
        assertNotNull(row.sharingAutoToggleAt)
        repo.setSharingAutoToggleWaypointId(id, 42L)
        row = repo.getUser(id)!!
        assertEquals(42L, row.sharingAutoToggleWaypointId)
        assertNull(row.sharingAutoToggleAt)
        repo.setSendingEnabledAndClearToggle(id, false)
        row = repo.getUser(id)!!
        assertEquals(false, row.sendingEnabled)
        assertNull(row.sharingAutoToggleWaypointId)
        val id2 = repo.upsertUser(user(name = "due"))
        repo.setSharingAutoToggleAt(id2, 1000L)
        assertEquals(1, repo.applyDueAutoToggles(2000L))
        val id3 = repo.upsertUser(user(name = "arr"))
        repo.setSharingAutoToggleWaypointId(id3, 9L)
        assertEquals(1, repo.applyDueArrivalToggles(listOf(9L)))
        assertEquals(0, repo.applyDueArrivalToggles(listOf(9L)))
    }

    private fun assertNotNull(v: Any?) { kotlin.test.assertNotNull(v) }

    @Test
    fun waypointAndLocationPaths() = runTest {
        val wpId = repo.upsertWaypoint(Waypoint(name = "Home", range = 150.0, coord = Coord(3.0, 4.0)))
        assertEquals("Home", repo.getWaypoint(wpId).name)
        assertEquals(1, repo.getAllWaypoints().size)
        assertEquals(1, repo.waypoints.first().size)
        val uid = repo.upsertUser(user())
        repo.upsertLocation(fix(uid, 1000))
        repo.upsertLocations(listOf(fix(uid, 2000), fix(uid, 3000)))
        assertEquals(3, repo.locationHistory(uid).first().size)
        assertEquals(3000L, repo.latestLocationByUser.first()[uid]?.reportedAt?.toEpochMilliseconds())
        assertEquals(1, repo.latestLocationsOnce().size)
        repo.deleteLocationsOlderThan(2) // timestamps are stored as epoch seconds (1s, 2s, 3s)
        assertEquals(2, repo.locationHistory(uid).first().size)
        assertEquals(1, repo.deleteWaypoint(repo.getWaypoint(wpId)))
    }

    @Test
    fun temporaryLinkAndNoShowStores() = runTest {
        val link = TemporaryLink(name = "Trip", deleteAt = Clock.System.now(), pqcPublicKey = "pub", pqcSeed = "seed")
        val linkId = repo.temporaryLinkStore.upsert(link)
        assertTrue(linkId != 0L)
        assertEquals(1, repo.temporaryLinkStore.getAll().size)
        assertEquals(1, repo.temporaryLinks.first().size)
        assertEquals(1, repo.temporaryLinkStore.delete(repo.temporaryLinkStore.getAll().first()))
        val alert = NoShowAlert(watchedUserId = 5L, waypointId = null, expectedAt = Instant.fromEpochMilliseconds(1000), grace = 5.minutes, oneShot = false)
        val alertId = repo.noShowAlertStore.upsert(alert)
        assertEquals(alertId, repo.noShowAlertStore.get(alertId)?.id)
        assertEquals(1, repo.noShowAlertStore.getDue(10_000L).size)
        assertEquals(1, repo.noShowAlertStore.markFired(alertId))
        assertEquals(0, repo.noShowAlertStore.getDue(10_000L).size)
        assertEquals(1, repo.noShowAlertStore.rearm(alertId))
        assertEquals(1, repo.noShowAlerts.first().size)
        assertEquals(1, repo.noShowAlertStore.delete(repo.noShowAlertStore.get(alertId)!!))
    }
}
