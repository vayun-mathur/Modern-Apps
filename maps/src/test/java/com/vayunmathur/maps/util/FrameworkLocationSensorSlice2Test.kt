package com.vayunmathur.maps.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor

/**
 * The compass half of the framework location feed, plus provider-off and
 * restart behaviour the live-fix suite does not reach.
 * Slice-2 coverage for maps (basename F, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class FrameworkLocationSensorSlice2Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun grantLocation() {
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun fix(
        provider: String = LocationManager.GPS_PROVIDER,
        lat: Double = 37.8,
        lon: Double = -122.4,
        timeNanos: Long = 1_000_000_000L,
    ): Location = Location(provider).apply {
        latitude = lat
        longitude = lon
        accuracy = 5f
        elapsedRealtimeNanos = timeNanos
    }

    private fun sensorEvent(type: Int, values: FloatArray) =
        SensorEventBuilder.newBuilder()
            .setSensor(ShadowSensor.newInstance(type))
            .setValues(values)
            .build()

    @Test fun `compass emits once a location exists and only on real turns`() {
        grantLocation()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadowOf(sm).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        shadowOf(sm).addSensor(ShadowSensor.newInstance(Sensor.TYPE_MAGNETIC_FIELD))

        val manager = FrameworkLocationManager(context)
        val updates = mutableListOf<Pair<GeoPoint, Float?>>()
        val accuracies = mutableListOf<Int>()
        manager.startUpdates(
            onUpdateReceived = { point, heading, _ -> updates.add(point to heading) },
            onAccuracyReceived = { accuracies.add(it) },
        )
        assertTrue(shadowOf(sm).hasListener(manager), "compass listeners registered")

        // No location yet: sensor samples alone never emit a position.
        manager.onSensorChanged(sensorEvent(Sensor.TYPE_ACCELEROMETER, floatArrayOf(0f, 0f, 9.81f)))
        manager.onSensorChanged(sensorEvent(Sensor.TYPE_MAGNETIC_FIELD, floatArrayOf(0f, 25f, -40f)))
        assertEquals(0, updates.size)

        // A fix without bearing opens the gate for the compass path.
        shadowOf(lm).requestLocationUpdateListeners.forEach { it.onLocationChanged(fix()) }
        assertEquals(1, updates.size)

        // First compass sample after the fix always emits (no prior heading).
        manager.onSensorChanged(sensorEvent(Sensor.TYPE_MAGNETIC_FIELD, floatArrayOf(0f, 25f, -40f)))
        assertTrue(updates.size >= 2, "first heading emits: $updates")
        val settled = updates.size
        // The same heading again is sub-degree movement: gated out.
        manager.onSensorChanged(sensorEvent(Sensor.TYPE_MAGNETIC_FIELD, floatArrayOf(0f, 25f, -40f)))
        manager.onSensorChanged(sensorEvent(Sensor.TYPE_ACCELEROMETER, floatArrayOf(0f, 0f, 9.81f)))
        assertEquals(settled, updates.size)
        // A real turn (field rotated ~90 degrees) emits again.
        manager.onSensorChanged(sensorEvent(Sensor.TYPE_MAGNETIC_FIELD, floatArrayOf(-25f, 0f, -40f)))
        assertTrue(updates.size > settled, "turn emits: $updates")
        assertTrue(updates.last().second != null)

        // Only magnetometer accuracy is a calibration signal.
        manager.onAccuracyChanged(ShadowSensor.newInstance(Sensor.TYPE_MAGNETIC_FIELD), 1)
        manager.onAccuracyChanged(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER), 3)
        manager.onAccuracyChanged(null, 2)
        assertEquals(listOf(1), accuracies)
        manager.stop()
        assertTrue(!shadowOf(sm).hasListener(manager))
    }

    @Test fun `with every provider off nothing registers and nothing emits`() {
        grantLocation()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix())
        val manager = FrameworkLocationManager(context)
        val updates = mutableListOf<Pair<GeoPoint, Float?>>()
        manager.startUpdates(onUpdateReceived = { point, heading, _ -> updates.add(point to heading) })
        assertTrue(shadowOf(lm).requestLocationUpdateListeners.isEmpty())
        assertEquals(0, updates.size)
        manager.stop()
    }

    @Test fun `a network last-known seeds when gps is off`() {
        grantLocation()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(lm).setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            fix(provider = LocationManager.NETWORK_PROVIDER, lat = 37.25),
        )
        val manager = FrameworkLocationManager(context)
        val points = mutableListOf<GeoPoint>()
        manager.startUpdates(onUpdateReceived = { point, _, _ -> points.add(point) })
        manager.stop()
        assertEquals(1, points.size)
        assertEquals(37.25, points[0].latitude)
    }

    @Test fun `a restart forgets the previous fix and heading gate`() {
        grantLocation()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val manager = FrameworkLocationManager(context)
        val points = mutableListOf<GeoPoint>()
        manager.startUpdates(onUpdateReceived = { point, _, _ -> points.add(point) })
        shadowOf(lm).requestLocationUpdateListeners.forEach { it.onLocationChanged(fix()) }
        manager.stop()
        // Same fix again in a fresh session: it must emit, not be rejected as stale.
        manager.startUpdates(onUpdateReceived = { point, _, _ -> points.add(point) })
        shadowOf(lm).requestLocationUpdateListeners.forEach { it.onLocationChanged(fix()) }
        manager.stop()
        assertEquals(2, points.size)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `a stale network fix can displace gps and a much tighter one can too`() {
        grantLocation()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val manager = FrameworkLocationManager(context)
        val points = mutableListOf<GeoPoint>()
        manager.startUpdates(onUpdateReceived = { point, _, _ -> points.add(point) })
        val live = shadowOf(lm).requestLocationUpdateListeners
        // GPS at t=1s, then a network fix 11 s later (GPS stale) far away: taken.
        live.forEach { it.onLocationChanged(fix(timeNanos = 1_000_000_000L)) }
        live.forEach {
            it.onLocationChanged(
                Location(LocationManager.NETWORK_PROVIDER).apply {
                    latitude = 37.9; longitude = -122.5; accuracy = 5f
                    elapsedRealtimeNanos = 12_000_000_000L
                },
            )
        }
        assertEquals(2, points.size)
        assertEquals(37.9, points.last().latitude)
        manager.stop()
    }
}
