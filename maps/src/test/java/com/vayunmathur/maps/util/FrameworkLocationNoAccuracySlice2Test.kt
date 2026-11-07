package com.vayunmathur.maps.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Fixes with no accuracy and seeds with a bearing: the fallback branches of
 * the acceptance conversion in the framework location feed.
 * Slice-2 coverage for maps (basename F, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class FrameworkLocationNoAccuracySlice2Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun bareFix(
        provider: String = LocationManager.GPS_PROVIDER,
        lat: Double = 37.8,
        lon: Double = -122.4,
        bearing: Float? = null,
        timeNanos: Long = 1_000_000_000L,
    ): Location = Location(provider).apply {
        latitude = lat
        longitude = lon
        if (bearing != null) this.bearing = bearing
        elapsedRealtimeNanos = timeNanos
        // Deliberately no accuracy: hasAccuracy() stays false.
    }

    @Test fun `a last-known seed with a bearing uses it and needs no accuracy`() {
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            bareFix(bearing = 45f),
        )
        val manager = FrameworkLocationManager(context)
        val updates = mutableListOf<Pair<GeoPoint, Float?>>()
        manager.startUpdates(onUpdateReceived = { point, heading -> updates.add(point to heading) })
        manager.stop()
        assertEquals(1, updates.size)
        assertEquals(45f, updates[0].second)
    }

    @Test fun `live fixes without accuracy still emit and gate by provider`() {
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val manager = FrameworkLocationManager(context)
        val points = mutableListOf<GeoPoint>()
        manager.startUpdates(onUpdateReceived = { point, _ -> points.add(point) })
        val live = shadowOf(lm).requestLocationUpdateListeners
        live.forEach { it.onLocationChanged(bareFix(timeNanos = 1_000_000_000L)) }
        // A network fix with no accuracy stated cannot displace fresh GPS:
        // "provider declined to say" is not evidence of being better.
        live.forEach {
            it.onLocationChanged(
                bareFix(
                    provider = LocationManager.NETWORK_PROVIDER,
                    lat = 37.9,
                    timeNanos = 2_000_000_000L,
                ),
            )
        }
        assertEquals(1, points.size)
        manager.stop()
    }
}
