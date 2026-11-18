package com.vayunmathur.findfamily.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.awaitFamilyCondition
import com.vayunmathur.findfamily.platform.LocationTrackingService
import com.vayunmathur.findfamily.platform.ServiceRestartWorker
import com.vayunmathur.findfamily.platform.ensureSync
import com.vayunmathur.findfamily.platform.fetchAddress
import com.vayunmathur.findfamily.platform.onLocationFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full [LocationTrackingService] lifecycle in the sandbox: startTracking brings up
 * sensors/location requests, a fix flows through intake, and destroy tears down
 * without touching uninitialized lateinit state. No relay is reachable, so the
 * live loop simply retries in the background for the life of the test.
 */
@RunWith(AndroidJUnit4::class)
class TrackingServiceLifecycleTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app
    private lateinit var controller: ServiceController<LocationTrackingService>
    private lateinit var service: LocationTrackingService

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        controller = Robolectric.buildService(LocationTrackingService::class.java)
        service = controller.create().get()
        service.repositoryOverride = TestFindFamilyRepository.install(ctx)
        service.bm = ctx.getSystemService(BatteryManager::class.java)
        service.locationManager = ctx.getSystemService(LocationManager::class.java)
        service.powerManager = ctx.getSystemService(android.os.PowerManager::class.java)
    }

    @Test fun startTrackingRegistersAndDestroysCleanly() = runBlocking {
        service.startTracking()
        assertTrue(
            awaitFamilyCondition(10_000) { service.trackingInitialized },
            "startTracking must finish initialization",
        )
        // Idempotent re-entry: a second start does not re-initialize.
        service.startTracking()
        service.registerSensors()
        service.setupLocationUpdates()
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.0; longitude = -122.0; accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = System.nanoTime()
        }
        service.onLocationFix(fix)
        assertEquals(37.0, service.lastKnownLocation?.latitude)
        controller.destroy()
        Unit
    }

    @Test fun destroyWithoutStartIsClean() {
        // Destroying a service that never started tracking must not throw on the
        // guarded lateinit sensor/location managers.
        controller.destroy()
    }

    @Test fun restartWorkerReconcilesAndEnsureSyncEnqueues() = runBlocking {
        val worker = TestListenableWorkerBuilder<ServiceRestartWorker>(ctx).build()
        val result = async(Dispatchers.IO) { worker.doWork() }
        assertTrue(awaitFamilyCondition(10_000) { result.isCompleted })
        assertEquals(ListenableWorker.Result.success(), result.await())
        // Enqueueing the periodic reconcile must not throw with WorkManager up.
        ensureSync(ctx)
        Unit
    }

    @Test fun fetchAddressWithoutGeocoderBackendIsNull() = runBlocking {
        // Robolectric has no geocode backend: a null result, never a hang or throw.
        assertNull(ctx.fetchAddress(37.0, -122.0))
        Unit
    }
}
