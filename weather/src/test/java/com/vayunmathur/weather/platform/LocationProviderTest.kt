package com.vayunmathur.weather.platform

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LocationProviderTest {

    private lateinit var app: Application
    private lateinit var lm: LocationManager

    @BeforeTest fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        lm = app.getSystemService(LocationManager::class.java)
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
    }

    private fun fix(lat: Double, provider: String = LocationManager.GPS_PROVIDER) = Location(provider).apply {
        latitude = lat; longitude = 13.0; time = System.currentTimeMillis()
    }

    @Test fun permissionGranted() {
        assertTrue(LocationProvider.hasPermission(app))
    }

    @Test fun lastKnownLocationReturnedImmediately() = runBlocking {
        Shadows.shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(52.0))
        Shadows.shadowOf(lm).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(40.0, LocationManager.NETWORK_PROVIDER))
        val got = LocationProvider.currentLocation(app)
        assertNotNull(got)
        // Freshest of the providers wins (GPS fix was stamped first but both
        // use "now"; either way a real fix must come back, not a listener).
        assertTrue(got.latitude in listOf(52.0, 40.0))
    }

    @Test fun oneShotListenerDeliversFix() = runBlocking {
        val deferred = async { LocationProvider.currentLocation(app) }
        // Let the call register its one-shot listeners, then push a fix.
        var guard = 0
        while (guard++ < 50) {
            delay(20)
            Shadows.shadowOf(lm).simulateLocation(fix(48.0))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (deferred.isCompleted) break
        }
        val got = withTimeout(5_000) { deferred.await() }
        assertEquals(48.0, assertNotNull(got).latitude)
    }
}
