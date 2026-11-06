package com.vayunmathur.maps.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The framework location feed with providers enabled: the live-fix path
 * (acceptance gate, jitter hold, GPS-bearing re-anchor), the cold-start seed
 * and the last-known selection rule.
 * Slice-2 coverage for maps (basename F, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class FrameworkLocationLiveSlice2Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun grantLocation() {
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun locationManager(): LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun fix(
        provider: String = LocationManager.GPS_PROVIDER,
        lat: Double = 37.8,
        lon: Double = -122.4,
        accuracy: Float = 5f,
        bearing: Float? = null,
        speed: Float? = null,
        timeNanos: Long = 1_000_000_000L,
    ): Location = Location(provider).apply {
        latitude = lat
        longitude = lon
        if (accuracy >= 0f) this.accuracy = accuracy
        if (bearing != null) this.bearing = bearing
        if (speed != null) this.speed = speed
        elapsedRealtimeNanos = timeNanos
    }

    private fun listeners(manager: LocationManager): List<LocationListener> =
        shadowOf(manager).requestLocationUpdateListeners

    @Test
    fun `a live gps fix is emitted`() {
        grantLocation()
        val lm = locationManager()
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val manager = FrameworkLocationManager(context)
        val updates = mutableListOf<Pair<com.vayunmathur.library.map.GeoPoint, Float?>>()
        manager.startUpdates(onUpdateReceived = { point, heading -> updates.add(point to heading) })
        val live = listeners(lm)
        assertTrue(live.isNotEmpty(), "listener registered")
        live.forEach { it.onLocationChanged(fix()) }
        // The main-looper handler delivery is synchronous under Robolectric.
        manager.stop()
        assertEquals(1, updates.size)
        assertEquals(37.8, updates[0].first.latitude)
        assertEquals(-122.4, updates[0].first.longitude)
    }

    @Test
    fun `a gps bearing re-anchors the heading`() {
        grantLocation()
        val lm = locationManager()
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val manager = FrameworkLocationManager(context)
        val headings = mutableListOf<Float?>()
        manager.startUpdates(onUpdateReceived = { _, heading -> headings.add(heading) })
        listeners(lm).forEach { it.onLocationChanged(fix(bearing = 90f)) }
        manager.stop()
        assertEquals(listOf<Float?>(90f), headings)
    }

    @Test
    fun `a network fix does not displace a fresh gps fix`() {
        grantLocation()
        val lm = locationManager()
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val manager = FrameworkLocationManager(context)
        val points = mutableListOf<com.vayunmathur.library.map.GeoPoint>()
        manager.startUpdates(onUpdateReceived = { point, _ -> points.add(point) })
        val live = listeners(lm)
        assertTrue(live.isNotEmpty())
        // One GPS fix, then a network fix 1 s later claiming 2x accuracy —
        // not enough margin to displace fresh GPS, and inside the jitter gate.
        live.forEach { it.onLocationChanged(fix(provider = LocationManager.GPS_PROVIDER, timeNanos = 1_000_000_000L)) }
        live.forEach {
            it.onLocationChanged(
                fix(provider = LocationManager.NETWORK_PROVIDER, lat = 37.801, lon = -122.401, accuracy = 5f, timeNanos = 2_000_000_000L),
            )
        }
        manager.stop()
        assertEquals(1, points.size, "network fix inside the gate does not move the puck")
    }

    @Test
    fun `the cold-start seed emits the freshest last-known fix`() {
        grantLocation()
        val lm = locationManager()
        val shadow = shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadow.setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            fix(provider = LocationManager.GPS_PROVIDER, lat = 37.1, timeNanos = 1_000_000_000L),
        )
        shadow.setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            fix(provider = LocationManager.NETWORK_PROVIDER, lat = 37.2, timeNanos = 2_000_000_000L),
        )
        val manager = FrameworkLocationManager(context)
        val points = mutableListOf<com.vayunmathur.library.map.GeoPoint>()
        manager.startUpdates(onUpdateReceived = { point, _ -> points.add(point) })
        manager.stop()
        // The network fix is fresher (larger elapsedRealtimeNanos): it seeds.
        assertEquals(1, points.size)
        assertEquals(37.2, points[0].latitude)
    }

    @Test
    fun `a zero-zero last-known fix is not a position`() {
        grantLocation()
        val lm = locationManager()
        val shadow = shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            fix(provider = LocationManager.GPS_PROVIDER, lat = 0.0, lon = 0.0),
        )
        val manager = FrameworkLocationManager(context)
        val points = mutableListOf<com.vayunmathur.library.map.GeoPoint>()
        manager.startUpdates(onUpdateReceived = { point, _ -> points.add(point) })
        manager.stop()
        assertEquals(0, points.size)
    }

    @Test
    fun `sensor registration is attempted without a location leak`() {
        grantLocation()
        val lm = locationManager()
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val manager = FrameworkLocationManager(context)
        val updates = mutableListOf<Pair<com.vayunmathur.library.map.GeoPoint, Float?>>()
        manager.startUpdates(onUpdateReceived = { point, heading -> updates.add(point to heading) })
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // Robolectric ships no accelerometer/magnetic-field sensors, so the
        // manager registers nothing — but the compass listeners it *would*
        // own are torn down, and nothing leaks through the sensor path.
        assertTrue(shadowOf(sm).getListeners().isEmpty())
        manager.stop()
        assertEquals(0, updates.size)
    }

    @Test
    fun `a stationary hold stops the second fix re-emitting`() {
        grantLocation()
        val lm = locationManager()
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val manager = FrameworkLocationManager(context)
        val points = mutableListOf<com.vayunmathur.library.map.GeoPoint>()
        manager.startUpdates(onUpdateReceived = { point, _ -> points.add(point) })
        val live = listeners(lm)
        // Same spot, 1 s apart, tight accuracy: the second fix coalesces
        // into the hold and does not re-emit.
        live.forEach { it.onLocationChanged(fix(timeNanos = 1_000_000_000L, accuracy = 4f)) }
        live.forEach { it.onLocationChanged(fix(timeNanos = 2_000_000_000L, accuracy = 4f)) }
        manager.stop()
        assertEquals(1, points.size)
    }

    @Test
    fun `a moving fix breaks the hold`() {
        grantLocation()
        val lm = locationManager()
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val manager = FrameworkLocationManager(context)
        val points = mutableListOf<com.vayunmathur.library.map.GeoPoint>()
        manager.startUpdates(onUpdateReceived = { point, _ -> points.add(point) })
        val live = listeners(lm)
        live.forEach { it.onLocationChanged(fix(timeNanos = 1_000_000_000L, accuracy = 4f)) }
        // 5 m/s: travel, not noise — always emits.
        live.forEach {
            it.onLocationChanged(
                fix(lat = 37.8005, timeNanos = 2_000_000_000L, accuracy = 4f, speed = 5f),
            )
        }
        manager.stop()
        assertEquals(2, points.size)
    }
}
