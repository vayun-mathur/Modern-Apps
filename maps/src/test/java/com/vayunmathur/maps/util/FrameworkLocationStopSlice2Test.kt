package com.vayunmathur.maps.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

/**
 * Lifecycle edges of the framework location feed: stopping before/without
 * starting, the deprecated status callback, and sensor samples that cannot
 * produce a heading.
 * Slice-2 coverage for maps (basename F, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class FrameworkLocationStopSlice2Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun fix(): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 37.8
        longitude = -122.4
        accuracy = 5f
        elapsedRealtimeNanos = 1_000_000_000L
    }

    @Test fun `stop before start and double stop are silent`() {
        val manager = FrameworkLocationManager(context)
        manager.stop()
        manager.stop()
        // Sensor callbacks with nothing registered are also silent.
        manager.onAccuracyChanged(null, 1)
        manager.onSensorChanged(
            SensorEventBuilder.newBuilder()
                .setSensor(ShadowSensor.newInstance(Sensor.TYPE_ROTATION_VECTOR))
                .setValues(floatArrayOf(0f, 0f, 0f, 1f))
                .build(),
        )
    }

    @Test fun `the status callback is a no-op and updates resume after it`() {
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val manager = FrameworkLocationManager(context)
        val points = mutableListOf<GeoPoint>()
        val listener = manager.startUpdates(onUpdateReceived = { point, _ -> points.add(point) })
        @Suppress("DEPRECATION")
        listener.onStatusChanged(LocationManager.GPS_PROVIDER, 2, Bundle())
        listener.onLocationChanged(fix())
        assertEquals(1, points.size)
        manager.stop()
        // Fixes after stop go nowhere: the listener list is unregistered, and
        // a stale direct call still reaches the (now null) callback quietly.
        listener.onLocationChanged(fix().apply { latitude = 38.0; elapsedRealtimeNanos = 5_000_000_000L })
        assertEquals(1, points.size)
    }

    @Test fun `zero readings produce no heading emit`() {
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val manager = FrameworkLocationManager(context)
        val updates = mutableListOf<Pair<GeoPoint, Float?>>()
        manager.startUpdates(onUpdateReceived = { point, heading -> updates.add(point to heading) })
        shadowOf(lm).requestLocationUpdateListeners.forEach { it.onLocationChanged(fix()) }
        assertEquals(1, updates.size)
        // All-zero accel/mag readings cannot form a rotation matrix.
        manager.onSensorChanged(
            SensorEventBuilder.newBuilder()
                .setSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
                .setValues(floatArrayOf(0f, 0f, 0f))
                .build(),
        )
        manager.onSensorChanged(
            SensorEventBuilder.newBuilder()
                .setSensor(ShadowSensor.newInstance(Sensor.TYPE_MAGNETIC_FIELD))
                .setValues(floatArrayOf(0f, 0f, 0f))
                .build(),
        )
        assertEquals(1, updates.size)
        manager.stop()
    }
}
