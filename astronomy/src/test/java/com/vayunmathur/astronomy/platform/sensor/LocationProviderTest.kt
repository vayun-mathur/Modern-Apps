package com.vayunmathur.astronomy.platform.sensor

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [LocationProvider] against Robolectric's location shadow:
 * permission gating, last-known selection by recency, and the live
 * listener path when no last-known fix exists.
 */
@RunWith(AndroidJUnit4::class)
class LocationProviderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val locationManager: LocationManager
        get() = context.getSystemService(LocationManager::class.java)

    private fun grantLocation() {
        shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    private fun location(provider: String, lat: Double, time: Long) = Location(provider).apply {
        latitude = lat
        longitude = -0.12
        this.time = time
    }

    @Test
    fun `no permission means no location`() {
        runBlocking {
        assertFalse(LocationProvider.hasPermission(context))
        assertNull(LocationProvider.currentLocation(context))
        }
    }

    @Test
    fun `permission without enabled providers yields null`() {
        runBlocking {
        grantLocation()
        assertTrue(LocationProvider.hasPermission(context))
        val shadow = shadowOf(locationManager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        assertNull(LocationProvider.currentLocation(context))
        }
    }

    @Test
    fun `newest last-known fix across providers wins`() {
        runBlocking {
        grantLocation()
        val shadow = shadowOf(locationManager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val gps = location(LocationManager.GPS_PROVIDER, 51.5, 1_000L)
        val network = location(LocationManager.NETWORK_PROVIDER, 40.0, 2_000L)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, gps)
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, network)

        val found = LocationProvider.currentLocation(context)
        assertSame(network, found)
        assertEquals(40.0, found?.latitude)
        }
    }

    @Test
    fun `gps-only last-known fix is returned`() {
        runBlocking {
        grantLocation()
        val shadow = shadowOf(locationManager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        val gps = location(LocationManager.GPS_PROVIDER, 35.0, 500L)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, gps)
        assertSame(gps, LocationProvider.currentLocation(context))
        }
    }

    @Test
    fun `live listener delivers the first simulated fix`() {
        runBlocking {
        grantLocation()
        val shadow = shadowOf(locationManager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        // No last-known location: the provider must wait for a live fix.
        val deferred = async { LocationProvider.currentLocation(context) }
        // Let the listeners register, then deliver a fix.
        var delivered: Location? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (shadow.requestLocationUpdateListeners.isNotEmpty()) {
                delivered = location(LocationManager.GPS_PROVIDER, 48.85, 3_000L)
                // Deliver through the registered listeners, as the platform would.
                shadow.getLocationUpdateListeners(LocationManager.GPS_PROVIDER)
                    .forEach { it.onLocationChanged(delivered) }
                shadow.simulateLocation(LocationManager.GPS_PROVIDER, delivered)
                break
            }
            yield()
            Thread.sleep(10)
        }
        assertNotNull(delivered)
        val found = deferred.await()
        assertSame(delivered, found)
        assertEquals(48.85, found?.latitude)
        }
    }
}
