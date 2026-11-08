package com.vayunmathur.maps.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import android.os.Looper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Slice 3/3 extra: off-route recalculation trigger and MapsSearchViewModel flows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecalcAndSearchSlice3ExtraTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun route(): RouteService.Route {
        val pts = listOf(pt(-122.0, 37.0), pt(-122.0, 37.001), pt(-122.0, 37.002))
        val step = RouteService.Step(0.0, 60.seconds, pts,
            RouteService.API.NavInstruction(RouteService.API.Maneuver.STRAIGHT, "Head north"), RouteService.TravelMode.DRIVE)
        return RouteService.Route(10.minutes, 222.0, pts, listOf(step))
    }

    private fun location(lat: Double, lon: Double): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat; longitude = lon; accuracy = 5f; time = System.currentTimeMillis()
    }

    private fun shadowLm(): org.robolectric.shadows.ShadowLocationManager {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return Shadows.shadowOf(lm)
    }

    @Test fun offRouteRecalculationTriggersAfterDebounce() { runBlocking {
        NavigationSessionManager.init(ctx)
        NavigationSessionManager.stop()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val shadow = shadowLm()
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        // Seed fix is 1km off-route: start() seeds it, opening the off-route window
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, location(37.001, -121.99)) // beside the route midpoint: off-route but not past the end (a fix beyond the end snaps to the endpoint and reads as arrival)
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, location(37.001, -121.99))
        NavigationSessionManager.start(route(), RouteService.TravelMode.DRIVE, pt(-122.0, 37.002), "Dest")
        // Process the seed fix NOW (its off-route window opens at this wall time);
        // idleFor also advances the paused looper's virtual clock.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        // Wait out the production 5s debounce (wall clock), then deliver a second
        // off-route fix - the first fix for this listener, so the shadow passes it.
        delay(5600)
        shadow.simulateLocation(location(37.001, -121.989))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // The trigger flips to Recalculating and launches the (failing, no-graph) recalc;
        // the manager scope is Main.immediate, so pump the paused looper while polling.
        withTimeout(15000) {
            while (NavigationSessionManager.state.value !is NavigationSessionManager.NavState.Recalculating &&
                NavigationSessionManager.state.value !is NavigationSessionManager.NavState.Failed) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(100)
            }
        }
        // After the failed recalc the machine is still alive (not Idle, not crashed)
        assertTrue(
            NavigationSessionManager.state.value !is NavigationSessionManager.NavState.Idle,
            "state=${NavigationSessionManager.state.value}",
        )
        NavigationSessionManager.stop()
        assertEquals(NavigationSessionManager.NavState.Idle, NavigationSessionManager.state.value)
        Unit
    } }

    @Test fun resolveAndSelectOfflineFindsNothing() { runBlocking {
        val vm = MapsSearchViewModel(app)
        var callback: com.vayunmathur.maps.data.SpecificFeature? = null
        var called = false
        vm.resolveAndSelect("221B Baker Street", 37.8, -122.4) { found -> called = true; callback = found }
        // Offline search runs against the (absent) POI archive on IO; poll for the callback
        withTimeout(20000) { while (!called) { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(50) } }
        assertNull(callback) // nothing on-device matches a street address in the sandbox
        Unit
    } }

    @Test fun setQueryDebouncesToOfflineResults() { runBlocking {
        val vm = MapsSearchViewModel(app)
        vm.setQuery("x", 37.8, -122.4) // below threshold: clears
        assertTrue(vm.results.value.isEmpty())
        vm.setQuery("coffee", 37.8, -122.4)
        assertEquals("coffee", vm.query.value)
        // Debounced offline search runs and settles (empty results, not searching)
        withTimeout(20000) { while (vm.searching.value) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, java.util.concurrent.TimeUnit.MILLISECONDS); delay(30) } }
        assertTrue(vm.results.value.isEmpty())
        assertTrue(vm.results.value.isEmpty())
        Unit
    } }
}
