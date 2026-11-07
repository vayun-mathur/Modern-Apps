package com.vayunmathur.astronomy.platform

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Location-refresh tests for [AstronomyViewModel]: with a granted
 * permission and a last-known fix in Robolectric's location shadow, the
 * observer moves to the fix; without permission it stays put.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)
@RunWith(AndroidJUnit4::class)
class AstronomyViewModelLocationTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    @Test
    fun `refresh location adopts the last-known fix`() {
        val locationManager = app.getSystemService(LocationManager::class.java)
        val shadow = shadowOf(locationManager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadow.setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 48.85
                longitude = 2.35
                altitude = 35.0
                time = 1_700_000_000_000L
            },
        )
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        val vm = AstronomyViewModel(app)
        vm.refreshLocation()
        await("observer at fix") {
            val observer = vm.observer.value
            observer != null && kotlin.math.abs(observer.latDeg - 48.85) < 1e-6
        }
        val observer = vm.observer.value
        assertNotNull(observer)
        assertEquals(48.85, observer.latDeg, 1e-6)
        assertEquals(2.35, observer.lonDeg, 1e-6)
    }

    @Test
    fun `refresh location without permission keeps the manual observer`() {
        val vm = AstronomyViewModel(app)
        vm.setManualLocation(37.0, -122.0)
        vm.refreshLocation()
        // No permission granted: the refresh must not move the observer.
        Thread.sleep(300)
        assertEquals(37.0, vm.observer.value?.latDeg)
        assertEquals(-122.0, vm.observer.value?.lonDeg)
    }
}
