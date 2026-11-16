package com.vayunmathur.findfamily.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Repository stores (links, no-show alerts, auto-toggles, history sweep) over real Room. */
@RunWith(AndroidJUnit4::class)
class FindFamilyRepositoryStoresTest {

    private lateinit var repo: FindFamilyRepository
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest fun open() { repo = TestFindFamilyRepository.install(ctx) }

    private fun user(name: String, enabled: Boolean = true) = User(
        name = name, photo = null, locationName = "Home", sendingEnabled = enabled,
        requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
    )

    private fun fix(userid: Long, at: Long, source: LocationSource = LocationSource.LIVE) = LocationValue(
        userid = userid, coord = Coord(1.0, 2.0), speed = 0f, acc = 5f,
        timestamp = Instant.fromEpochMilliseconds(at), battery = 50f,
        reportedAt = Instant.fromEpochMilliseconds(at), source = source,
    )

    @Test fun singletonServesInstalledRepository() {
        assertEquals(repo, FindFamilyRepository.get(ctx))
    }

    @Test fun temporaryLinkStoreCrudAndFlow() = runTest {
        val link = TemporaryLink(name = "Trip", deleteAt = Clock.System.now(), pqcPublicKey = "bundle", id = 77L)
        assertEquals(77L, repo.temporaryLinkStore.upsert(link))
        assertEquals(listOf("Trip"), repo.temporaryLinkStore.getAll().map { it.name })
        assertEquals(listOf(77L), repo.temporaryLinks.first().map { it.id })
        assertEquals(1, repo.temporaryLinkStore.delete(link))
        assertTrue(repo.temporaryLinkStore.getAll().isEmpty())
        assertEquals(0, repo.temporaryLinkStore.delete(link))
    }

    @Test fun noShowStoreDueMarkFiredAndRearm() = runTest {
        val uid = repo.upsertUser(user("Alice"))
        val now = Clock.System.now()
        val dueId = repo.noShowAlertStore.upsert(
            NoShowAlert(watchedUserId = uid, waypointId = null, expectedAt = now, grace = 1.minutes, oneShot = false)
        )
        repo.noShowAlertStore.upsert(
            NoShowAlert(watchedUserId = uid, waypointId = null, expectedAt = now + 60.minutes, grace = 1.minutes)
        )
        assertEquals(2, repo.noShowAlertStore.getAll().size)
        assertEquals(listOf(dueId), repo.noShowAlertStore.getDue(now.epochSeconds + 120).map { it.id })
        assertEquals(dueId, repo.noShowAlertStore.get(dueId)?.id)
        assertEquals(1, repo.noShowAlertStore.markFired(dueId))
        assertTrue(repo.noShowAlertStore.get(dueId)!!.fired)
        // A fired row is no longer due until re-armed.
        assertTrue(repo.noShowAlertStore.getDue(now.epochSeconds + 120).isEmpty())
        assertEquals(1, repo.noShowAlertStore.rearm(dueId))
        assertFalse(repo.noShowAlertStore.get(dueId)!!.fired)
        assertEquals(listOf(dueId), repo.noShowAlertStore.getDue(now.epochSeconds + 120).map { it.id })
        assertEquals(1, repo.noShowAlertStore.delete(repo.noShowAlertStore.get(dueId)!!))
        assertNull(repo.noShowAlertStore.get(dueId))
    }

    @Test fun timeAutoToggleFlipsOnlyDueRows() = runTest {
        val due = repo.upsertUser(user("Due", enabled = true))
        val future = repo.upsertUser(user("Future", enabled = true))
        repo.setSharingAutoToggleAt(due, 1_000L)
        repo.setSharingAutoToggleAt(future, 9_999_999L)
        assertEquals(1, repo.applyDueAutoToggles(2_000L))
        assertFalse(repo.getUser(due)!!.sendingEnabled)
        assertNull(repo.getUser(due)!!.sharingAutoToggleAt)
        assertTrue(repo.getUser(future)!!.sendingEnabled)
        assertEquals(9_999_999L, repo.getUser(future)!!.sharingAutoToggleAt?.epochSeconds)
        // Nothing due on a second sweep.
        assertEquals(0, repo.applyDueAutoToggles(2_000L))
    }

    @Test fun arrivalAutoToggleFlipsOnlyInsideRowsAndModesAreExclusive() = runTest {
        val wp = repo.waypointStore.upsert(Waypoint(name = "Home", range = 100.0, coord = Coord(1.0, 2.0)))
        val otherWp = repo.waypointStore.upsert(Waypoint(name = "Work", range = 100.0, coord = Coord(3.0, 4.0)))
        val inside = repo.upsertUser(user("Inside", enabled = false))
        val outside = repo.upsertUser(user("Outside", enabled = false))
        repo.setSharingAutoToggleWaypointId(inside, wp)
        repo.setSharingAutoToggleWaypointId(outside, otherWp)
        assertEquals(1, repo.applyDueArrivalToggles(listOf(wp)))
        assertTrue(repo.getUser(inside)!!.sendingEnabled)
        assertNull(repo.getUser(inside)!!.sharingAutoToggleWaypointId)
        assertFalse(repo.getUser(outside)!!.sendingEnabled)
        // Setting a time toggle clears the arrival trigger, and vice versa.
        repo.setSharingAutoToggleAt(outside, 5_000L)
        assertNull(repo.getUser(outside)!!.sharingAutoToggleWaypointId)
        repo.setSharingAutoToggleWaypointId(outside, wp)
        assertNull(repo.getUser(outside)!!.sharingAutoToggleAt)
        assertEquals(0, repo.applyDueArrivalToggles(emptyList()))
    }

    @Test fun manualSharingToggleClearsBothAutoToggles() = runTest {
        val id = repo.upsertUser(user("Manual", enabled = true))
        repo.setSharingAutoToggleAt(id, 5_000L)
        repo.setSendingEnabledAndClearToggle(id, false)
        val row = repo.getUser(id)!!
        assertFalse(row.sendingEnabled)
        assertNull(row.sharingAutoToggleAt)
        assertNull(row.sharingAutoToggleWaypointId)
    }

    @Test fun locationHistoryLatestAndOldSweep() = runTest {
        val uid = repo.upsertUser(user("Alice"))
        val other = repo.upsertUser(user("Bob"))
        repo.upsertLocations(listOf(fix(uid, 1_000), fix(uid, 3_000), fix(uid, 2_000), fix(other, 9_000)))
        assertEquals(listOf(3_000L, 2_000L, 1_000L),
            repo.locationHistory(uid).first().map { it.timestamp.toEpochMilliseconds() }.sortedDescending())
        val latest = repo.latestLocationsOnce().associateBy { it.userid }
        assertEquals(3_000L, latest[uid]?.timestamp?.toEpochMilliseconds())
        assertEquals(9_000L, latest[other]?.timestamp?.toEpochMilliseconds())
        assertEquals(setOf(uid, other), repo.latestLocationByUser.first().keys)
        repo.deleteLocationsOlderThan(2)
        assertEquals(listOf(3_000L, 2_000L),
            repo.locationHistory(uid).first().map { it.timestamp.toEpochMilliseconds() }.sortedDescending())
    }

    @Test fun waypointStoreGetAndFlow() = runTest {
        val id = repo.waypointStore.upsert(Waypoint(name = "Home", range = 150.0, coord = Coord(3.0, 4.0)))
        assertEquals("Home", repo.waypointStore.get(id).name)
        assertEquals(listOf(id), repo.waypointStore.getAll().map { it.id })
        assertEquals(listOf("Home"), repo.waypoints.first().map { it.name })
    }
}
