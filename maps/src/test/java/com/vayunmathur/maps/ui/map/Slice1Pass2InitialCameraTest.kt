package com.vayunmathur.maps.ui.map

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1/3 second pass (maps): [rememberInitialCamera]'s cold-start seed —
 * newest cached device fix wins, then the persisted last fix, then the
 * world view — and the one-shot centering on the first live fix.
 */
@RunWith(RobolectricTestRunner::class)
class Slice1Pass2InitialCameraTest {

    @get:Rule val rule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    private fun location(provider: String, lat: Double, lon: Double, time: Long) =
        Location(provider).apply {
            latitude = lat
            longitude = lon
            this.time = time
        }

    private fun setLastFix(lat: Double?, lon: Double?) = runBlocking {
        val ds = DataStoreUtils.getInstance(app)
        if (lat == null || lon == null) {
            ds.setDouble(MapPreferences.KEY_LAST_LAT, 0.0)
            ds.setDouble(MapPreferences.KEY_LAST_LON, 0.0)
        } else {
            ds.setDouble(MapPreferences.KEY_LAST_LAT, lat)
            ds.setDouble(MapPreferences.KEY_LAST_LON, lon)
        }
    }

    private fun composeCamera(vm: SelectedFeatureViewModel): CameraState {
        var camera: CameraState? = null
        rule.setContent { camera = rememberInitialCamera(vm) }
        rule.waitForIdle()
        return checkNotNull(camera)
    }

    @Test
    fun `seed prefers the cached device fix over the persisted one`() {
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        val lm = ctx.getSystemService(LocationManager::class.java)
        val shadowLm = Shadows.shadowOf(lm)
        shadowLm.setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            location(LocationManager.GPS_PROVIDER, 37.1, -122.1, 1_000),
        )
        shadowLm.setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            location(LocationManager.NETWORK_PROVIDER, 48.2, 2.3, 2_000),
        )
        setLastFix(10.0, 10.0)
        val camera = composeCamera(SelectedFeatureViewModel(app))
        // the device cache (here the GPS fix the shadow serves) wins over the
        // stored fix; the shadow does not serve a network-provider cache
        assertEquals(37.1, camera.position.target.latitude, 1e-9)
        assertEquals(-122.1, camera.position.target.longitude, 1e-9)
        assertEquals(14.0, camera.position.zoom, 1e-9)
    }

    @Test
    fun `seed falls back to persisted fix without permission and ignores zero-zero`() {
        // no location permission granted in this sandbox: the device cache is
        // closed, so the persisted fix seeds the camera
        setLastFix(35.6, 139.7)
        val camera = composeCamera(SelectedFeatureViewModel(app))
        assertEquals(35.6, camera.position.target.latitude, 1e-9)
        assertEquals(139.7, camera.position.target.longitude, 1e-9)

        // a stored (0,0) is "no fix ever", not the Gulf of Guinea
        setLastFix(null, null)
    }

    @Test
    fun `zero-zero cached device fix is not a seed`() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = ctx.getSystemService(LocationManager::class.java)
        Shadows.shadowOf(lm).setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            location(LocationManager.GPS_PROVIDER, 0.0, 0.0, 5_000),
        )
        setLastFix(35.6, 139.7)
        val camera = composeCamera(SelectedFeatureViewModel(app))
        assertEquals(35.6, camera.position.target.latitude, 1e-9)
        setLastFix(null, null)
    }

    @Test
    fun `first live fix centres once at first-fix zoom`() {
        setLastFix(null, null)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val vm = SelectedFeatureViewModel(app)
        val camera = composeCamera(vm)
        // deliver a fix through the framework pipeline the VM subscribed to
        val lm = ctx.getSystemService(LocationManager::class.java)
        Shadows.shadowOf(lm).simulateLocation(
            location(LocationManager.GPS_PROVIDER, 40.7, -74.0, 3_000),
        )
        rule.mainClock.advanceTimeBy(1_200)
        rule.waitForIdle()
        assertEquals(40.7, camera.position.target.latitude, 1e-9)
        assertEquals(-74.0, camera.position.target.longitude, 1e-9)
        assertTrue(camera.position.zoom >= 14.0)
        // a second fix does not re-centre: the latch is one-shot
        Shadows.shadowOf(lm).simulateLocation(
            location(LocationManager.GPS_PROVIDER, 41.0, -73.0, 4_000),
        )
        rule.mainClock.advanceTimeBy(1_200)
        rule.waitForIdle()
        assertEquals(40.7, camera.position.target.latitude, 1e-9)
    }
}
