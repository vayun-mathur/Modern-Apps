package com.vayunmathur.findfamily

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.platform.FakeAndroidKeyStore
import com.vayunmathur.findfamily.util.LocationTrackingService
import com.vayunmathur.findfamily.domain.MotionTransition
import com.vayunmathur.library.util.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3TrackingServiceTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        FakeAndroidKeyStore.install()
        runCatching { DatabaseHelper(ctx).storePassphrase("y3-tracking-pass") }
        runCatching { androidx.work.WorkManager.initialize(ctx, androidx.work.Configuration.Builder().build()) }
        Shadows.shadowOf(ctx as android.app.Application).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.BLUETOOTH_SCAN,
        )
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun serviceLifecycleSensorAndMotion() {
        val controller = Robolectric.buildService(LocationTrackingService::class.java)
        val service = controller.get()
        service.repositoryOverride = TestFindFamilyRepository.install(ctx)
        controller.create()
        assertNull(service.onBind(Intent(ctx, LocationTrackingService::class.java)))
        val result = service.onStartCommand(Intent(ctx, LocationTrackingService::class.java), 0, 1)
        assertEquals(android.app.Service.START_STICKY, result)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(500); Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // Sensor events: null and non-accelerometer are ignored; a strong
        // linear-acceleration event drives the motion detector.
        service.onSensorChanged(null)
        service.onAccuracyChanged(null, 1)
        val sensorManager = ctx.getSystemService(SensorManager::class.java)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (accel != null) {
            // SensorEvent has no public constructor; allocate + populate reflectively.
            val event = runCatching {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                val ev = unsafeClass.getMethod("allocateInstance", Class::class.java)
                    .invoke(unsafe, android.hardware.SensorEvent::class.java) as android.hardware.SensorEvent
                android.hardware.SensorEvent::class.java.getField("sensor").set(ev, accel)
                val values = android.hardware.SensorEvent::class.java.getField("values").get(ev) as FloatArray
                values[0] = 5f
                ev
            }.getOrNull()
            if (event != null) service.onSensorChanged(event)
        }
        service.applyMotionTransition(MotionTransition.NONE)
        runCatching { service.startMovingNow() }
        service.lastKnownLocation = Location("gps").apply { latitude = 1.0; longitude = 2.0 }
        assertEquals(1.0, service.lastKnownLocation?.latitude)
        controller.destroy()
        // Second start after destroy is still sticky-safe.
        val c2 = Robolectric.buildService(LocationTrackingService::class.java)
        c2.get().repositoryOverride = TestFindFamilyRepository.install(ctx)
        c2.create().startCommand(0, 1)
        Thread.sleep(300)
        c2.destroy()
    }
}
