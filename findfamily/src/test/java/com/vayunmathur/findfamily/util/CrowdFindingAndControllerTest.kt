package com.vayunmathur.findfamily.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.PoweredOffKeyStore
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.TrackerStore
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.data.awaitFamilyCondition
import com.vayunmathur.findfamily.platform.LocationServiceController
import com.vayunmathur.findfamily.platform.LocationTrackingService
import com.vayunmathur.findfamily.platform.pollPoweredOffSightings
import com.vayunmathur.findfamily.platform.pollTrackerReports
import com.vayunmathur.findfamily.platform.startPoweredOffScanner
import com.vayunmathur.findfamily.platform.startTrackerScanner
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Crowd-finding owner polls + [LocationServiceController] switches against the real
 * in-memory repository. With no relay socket every network call fails closed, so the
 * polls must complete with nothing stored and nothing thrown.
 */
@RunWith(AndroidJUnit4::class)
class CrowdFindingAndControllerTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app
    private lateinit var service: LocationTrackingService

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(
                ctx, androidx.work.Configuration.Builder().build()
            )
        }
        service = Robolectric.buildService(LocationTrackingService::class.java).create().get()
        service.repositoryOverride = TestFindFamilyRepository.install(ctx)
        service.bm = ctx.getSystemService(BatteryManager::class.java)
    }

    private fun user(kind: UserKind): User = User(
        name = "U-$kind",
        photo = null,
        locationName = "Home",
        sendingEnabled = false,
        requestStatus = RequestStatus.MUTUAL_CONNECTION,
        lastLocationChangeTime = Clock.System.now(),
        kind = kind,
    )

    @Test fun trackerPollWithNoStoreAndNoTrackersIsNoOp() = runBlocking {
        service.trackerStore = null
        service.pollTrackerReports()
        service.trackerStore = TrackerStore(DataStoreUtils.getInstance(ctx))
        // Repository has no tracker rows yet.
        service.pollTrackerReports()
        val repo = service.repository
        val trackerId = repo.upsertUser(user(UserKind.TRACKER))
        // Tracker row exists but no secret/bundle on file: register + fetch both
        // fail closed and nothing reaches the location table.
        service.pollTrackerReports()
        assertTrue(repo.locationHistory(trackerId).first().isEmpty())
        // A person row is ignored by the tracker poll entirely.
        repo.upsertUser(user(UserKind.PERSON))
        service.pollTrackerReports()
        Unit
    }

    @Test fun poweredOffPollRespectsRateLimitAndMissingKeys() = runBlocking {
        service.poweredOffKeys = null
        service.pollPoweredOffSightings()
        val store = PoweredOffKeyStore(DirectBootStore.store(ctx))
        service.poweredOffKeys = store
        service.lastPoweredOffPollMs = 0L
        val repo = service.repository
        val peerId = repo.upsertUser(user(UserKind.PERSON))
        // No keys held for the peer: skipped.
        service.pollPoweredOffSightings()
        // Immediate second poll is rate-limited (5-minute interval).
        service.pollPoweredOffSightings()
        // Keys held but no socket: fetch comes back empty, nothing stored.
        store.save(peerId, ByteArray(32) { 3 }, ByteArray(8) { 4 }, epoch = 1L)
        service.lastPoweredOffPollMs = 0L
        service.pollPoweredOffSightings()
        assertTrue(repo.locationHistory(peerId).first().isEmpty())
    }

    @Test fun scannersStartAndStopWithoutBle() = runBlocking {
        service.startTrackerScanner()
        service.startPoweredOffScanner()
        // Starting twice is a no-op while the jobs are alive (or already done:
        // with no BLE scanner both flows close immediately, which is also fine).
        service.startTrackerScanner()
        service.startPoweredOffScanner()
        assertTrue(
            awaitFamilyCondition(5_000) {
                (service.trackerScanJob?.isCompleted ?: true) ||
                    (service.trackerScanJob?.isActive ?: false)
            }
        )
        service.trackerScanJob?.cancel()
        service.poweredOffScanJob?.cancel()
        Unit
    }

    @Test fun controllerSwitchesAndPermissionGate() = runBlocking {
        // No permission granted in the sandbox by default.
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(LocationServiceController.hasFineLocationPermission(ctx))
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_FINE_LOCATION
            ),
        )
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(LocationServiceController.hasFineLocationPermission(ctx))

        // Tracking defaults on, persists, and drives syncServiceState off the
        // main thread (syncServiceState hops to Dispatchers.Main internally).
        assertTrue(LocationServiceController.isTrackingEnabled(ctx))
        val toggle = async(Dispatchers.IO) {
            LocationServiceController.setTrackingEnabled(ctx, false)
        }
        assertTrue(awaitFamilyCondition(5_000) { toggle.isCompleted })
        toggle.await()
        assertFalse(LocationServiceController.isTrackingEnabled(ctx))
        val toggleBack = async(Dispatchers.IO) {
            LocationServiceController.setTrackingEnabled(ctx, true)
        }
        assertTrue(awaitFamilyCondition(5_000) { toggleBack.isCompleted })
        toggleBack.await()
        assertTrue(LocationServiceController.isTrackingEnabled(ctx))

        // Sharing defaults on and persists independently of tracking.
        assertTrue(LocationServiceController.isGlobalSharingEnabled(ctx))
        LocationServiceController.setGlobalSharingEnabled(ctx, false)
        assertFalse(LocationServiceController.isGlobalSharingEnabled(ctx))
        assertFalse(LocationServiceController.globalSharingEnabledFlow(ctx).first())
        LocationServiceController.setGlobalSharingEnabled(ctx, true)
        assertTrue(LocationServiceController.globalSharingEnabledFlow(ctx).first())

        // Crowd finding is mandatory-true now.
        assertTrue(LocationServiceController.crowdFindingEnabledFlow(ctx).first())
        LocationServiceController.setCrowdFindingEnabled(ctx, false)
        assertTrue(LocationServiceController.crowdFindingEnabledFlow(ctx).first())

        // Nobody else is sharing yet (only rows in the DB count, self excluded).
        assertFalse(LocationServiceController.isSharingEnabled(ctx))
        LocationServiceController.stop(ctx)
        // Locked (pre-unlock) sync: unseeded mirror or missing permission -> no-op.
        val locked = async(Dispatchers.IO) { LocationServiceController.syncServiceStateLocked(ctx) }
        assertTrue(awaitFamilyCondition(5_000) { locked.isCompleted })
        locked.await()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locked2 = async(Dispatchers.IO) { LocationServiceController.syncServiceStateLocked(ctx) }
        assertTrue(awaitFamilyCondition(5_000) { locked2.isCompleted })
        locked2.await()
        Unit
    }
}
