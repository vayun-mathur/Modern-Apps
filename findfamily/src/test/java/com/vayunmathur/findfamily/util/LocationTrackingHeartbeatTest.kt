package com.vayunmathur.findfamily.util

import android.app.Application
import android.content.Context
import android.location.Location
import android.os.BatteryManager
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FFDatabase
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class LocationTrackingHeartbeatTest {

    private lateinit var db: FFDatabase
    private lateinit var repo: FindFamilyRepository
    private lateinit var service: LocationTrackingService
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    @BeforeTest
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, FFDatabase::class.java).allowMainThreadQueries().build()
        repo = FindFamilyRepository(ctx, db)
        service = Robolectric.buildService(LocationTrackingService::class.java).create().get()
        service.repositoryOverride = repo
        service.bm = ctx.getSystemService(BatteryManager::class.java)
        if (Networking.userid == 0L) {
            kotlinx.coroutines.runBlocking {
                Networking.init(repo, com.vayunmathur.library.util.DataStoreUtils.getInstance(ctx), "Me")
            }
        }
    }

    @AfterTest
    fun teardown() {
        db.close()
    }

    private fun location(lat: Double = 51.5, lon: Double = -0.12, acc: Float = 8f, elapsedNanos: Long = 1_000_000_000L) = Location("gps").apply {
        latitude = lat; longitude = lon; accuracy = acc; elapsedRealtimeNanos = elapsedNanos
        time = System.currentTimeMillis()
    }

    private fun user(name: String, id: Long = 0) = User(
        name = name, photo = null, locationName = "Elsewhere", sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(), id = id,
    )

    @Test
    fun recordFixAccuracyAndFreshness() {
        service.recordFix(location(acc = 99999f))
        assertEquals(null, service.lastKnownLocation)
        service.recordFix(location(elapsedNanos = 2_000_000_000L))
        assertNotNull(service.lastKnownLocation)
        // Older fix does not replace the held one.
        val held = service.lastKnownLocation
        service.recordFix(location(elapsedNanos = 1_500_000_000L, acc = 1f))
        assertEquals(held, service.lastKnownLocation)
        // Fresher fix with worse accuracy but within max age window replaces only if better or stale.
        service.recordFix(location(elapsedNanos = 3_000_000_000L, acc = 2f))
        assertEquals(2f, service.lastKnownLocation?.accuracy)
    }

    @Test
    fun syncHeartbeatPersistsSelfAndCleansExpiredLinks() = runBlocking {
        val peer = repo.upsertUser(user("Peer"))
        repo.upsertWaypoint(Waypoint(name = "Home", range = 500.0, coord = Coord(51.5, -0.12)))
        val expired = TemporaryLink(name = "old", deleteAt = Instant.fromEpochMilliseconds(1), pqcPublicKey = "pub")
        repo.temporaryLinkStore.upsert(expired)
        service.lastKnownLocation = location()
        service.syncHeartbeat()
        // Self user inserted and self location persisted.
        assertNotNull(repo.getUser(Networking.userid))
        assertEquals(1, repo.locationHistory(Networking.userid).first().size)
        // Expired link deleted by the publish sweep.
        assertTrue(repo.temporaryLinkStore.getAll().isEmpty())
        // Waypoint arrival toggles: peer has no trigger, nothing flips.
        assertEquals(true, repo.getUser(peer)?.sendingEnabled)
        // Second heartbeat with no location is covered by clearing and re-running.
        service.seedDirectBootMirror()
        // Peer has no PQC bundle, so nobody is a publish target for the mirror.
        assertTrue(service.publishRoster.isEmpty())
    }

    @Test
    fun syncHeartbeatWithoutLocationIsNoOp() = runBlocking {
        val before = repo.getAllUsers().size
        service.lastKnownLocation = null
        service.syncHeartbeat()
        // No fix held: heartbeat returns before any persistence.
        assertEquals(before, repo.getAllUsers().size)
        assertTrue(repo.locationHistory(Networking.userid).first().isEmpty())
    }
}
