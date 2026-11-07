package com.vayunmathur.networklocation.platform

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LocationFeedsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun grant() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun fix(provider: String, lat: Double = 37.4215): Location = Location(provider).apply {
        latitude = lat
        longitude = -122.084
        accuracy = 5f
        elapsedRealtimeNanos = 1_000_000_000L
    }

    @Test
    fun `hasFineLocation reflects the grant`() {
        assertFalse(hasFineLocation(context))
        grant()
        assertTrue(hasFineLocation(context))
    }

    @Test
    fun `location updates emits nothing without permission`() = runBlocking {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val got = mutableListOf<Location>()
        val job = launch { locationUpdates(context, LocationManager.GPS_PROVIDER).toList(got) }
        yield()
        shadowOf(context as Application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        job.cancel()
        assertTrue(got.isEmpty())
    }

    @Test
    fun `location updates emits nothing for an unknown provider`() = runBlocking {
        grant()
        val got = mutableListOf<Location>()
        val job = launch { locationUpdates(context, "no-such-provider").toList(got) }
        yield()
        job.cancel()
        assertTrue(got.isEmpty())
    }

    @Test
    fun `location updates seeds with the last known fix and follows live fixes`() = runBlocking {
        grant()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(LocationManager.GPS_PROVIDER, 37.0))

        val flow = locationUpdates(context, LocationManager.GPS_PROVIDER)
        val first = flow.first()
        assertEquals(37.0, first.latitude, 1e-9)

        val got = mutableListOf<Location>()
        val job = launch { flow.toList(got) }
        // Let the collector register before delivering a live fix.
        var tries = 0
        while (shadow.requestLocationUpdateListeners.isEmpty() && tries++ < 50) yield()
        assertTrue(shadow.requestLocationUpdateListeners.isNotEmpty())
        shadow.requestLocationUpdateListeners.forEach {
            it.onLocationChanged(fix(LocationManager.GPS_PROVIDER, 38.0))
        }
        tries = 0
        while (got.size < 2 && tries++ < 50) yield()
        job.cancel()
        assertEquals(listOf(37.0, 38.0), got.map { it.latitude })
    }

    @Test
    fun `reverse geocode with no shadow match succeeds with null`() = runBlocking {
        // The internally-built Geocoder's shadow has no canned answer, so the listener
        // reports an empty result set, which the production code maps to success(null).
        val result = reverseGeocode(context, 37.4215, -122.084)
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }
}
