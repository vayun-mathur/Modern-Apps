package com.vayunmathur.findfamily.util
import com.vayunmathur.findfamily.platform.LocationTrackingService

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.platform.onFixCeilingAlarm
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [LocationTrackingService] lifecycle, sensor intake and ceiling-alarm entry. */
@RunWith(AndroidJUnit4::class)
class LocationTrackingLifecycleRealTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build())
        }
    }

    @Test fun createStartDestroyLifecycle() {
        val repo = TestFindFamilyRepository.install(ctx)
        val controller = Robolectric.buildService(LocationTrackingService::class.java)
        val service = controller.create().get()
        service.repositoryOverride = repo
        // No fine permission in the sandbox: the start command must satisfy
        // the foreground contract and stop, not crash.
        assertEquals(
            android.app.Service.START_NOT_STICKY,
            service.onStartCommand(Intent(ctx, LocationTrackingService::class.java), 0, 1),
        )
        assertNotNull(service)
        controller.destroy()
    }

    @Test fun sensorEventsFeedMovementDetector() {
        val repo = TestFindFamilyRepository.install(ctx)
        val controller = Robolectric.buildService(LocationTrackingService::class.java)
        val service = controller.create().get()
        service.repositoryOverride = repo
        service.bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager
        // Null and non-accelerometer events are ignored.
        service.onSensorChanged(null)
        val sensorEvent = SensorEvent::class.java
            .getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(3)
        sensorEvent.values[0] = 1f
        sensorEvent.values[1] = 2f
        sensorEvent.values[2] = 3f
        // sensor is null on a hand-built event: the type gate must ignore it.
        service.onSensorChanged(sensorEvent)
        service.onAccuracyChanged(null, 1)
        controller.destroy()
    }

    @Test fun fixCeilingAlarmEntryRuns() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        val controller = Robolectric.buildService(LocationTrackingService::class.java)
        val service = controller.create().get()
        service.repositoryOverride = repo
        service.bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager
        // No tracking session and no fix: the ceiling tick must settle quietly.
        service.onFixCeilingAlarm()
        delay(200)
        // Granted permission changes the start-command branch taken.
        Shadows.shadowOf(ctx as android.app.Application).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val intent = Intent(ctx, LocationTrackingService::class.java)
            .setAction(com.vayunmathur.findfamily.platform.FixCeilingAlarm.ACTION_CHECK)
        val result = service.onStartCommand(intent, 0, 3)
        assertTrue(
            result == android.app.Service.START_STICKY ||
                result == android.app.Service.START_NOT_STICKY
        )
        controller.destroy()
        Unit
    }
}
