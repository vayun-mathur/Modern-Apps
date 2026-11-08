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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Slice 3/3 pass 3: NavigationSessionManager transit/progress/stop branches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Slice3Pass3NavTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun route(): RouteService.Route {
        val pts = listOf(pt(-122.0, 37.0), pt(-122.0, 37.001), pt(-122.0, 37.002))
        val step = RouteService.Step(0.0, 60.seconds, pts, RouteService.API.NavInstruction(), RouteService.TravelMode.DRIVE)
        return RouteService.Route(10.minutes, 222.0, pts, listOf(step))
    }

    private fun location(lat: Double, lon: Double): Location =
        Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat; longitude = lon; accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = System.nanoTime()
        }

    private fun pinLastKnown(loc: Location?) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = Shadows.shadowOf(lm)
        if (loc == null) {
            runCatching { shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, null) }
            runCatching { shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, null) }
        } else {
            shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, loc)
            shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, loc)
        }
    }

    private fun deliver(loc: Location) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(lm).simulateLocation(loc)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun startGranted(mode: RouteService.TravelMode) {
        NavigationSessionManager.init(ctx)
        NavigationSessionManager.stop()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm0 = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(lm0).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        pinLastKnown(location(37.0, -122.0))
        NavigationSessionManager.start(route(), mode, pt(-122.0, 37.002), "Dest")
    }

    @Test fun midRouteFixReportsProgress() {
        startGranted(RouteService.TravelMode.DRIVE)
        deliver(location(37.001, -122.0))
        val state = NavigationSessionManager.state.value
        assertTrue(state is NavigationSessionManager.NavState.Navigating, "state=$state")
        val progress = (state as NavigationSessionManager.NavState.Navigating).progress
        assertTrue(progress.distanceRemaining < 222.0, "remaining=${progress.distanceRemaining}")
        assertTrue(progress.distanceRemaining > 0.0, "remaining=${progress.distanceRemaining}")
        assertTrue(progress.fractionComplete > 0.0, "fraction=${progress.fractionComplete}")
        NavigationSessionManager.stop()
    }

    @Test fun transitOffRouteFixDoesNotRecalculate() {
        startGranted(RouteService.TravelMode.TRANSIT)
        // Far off the polyline: driving would debounce into a recalc, transit
        // must stay Navigating (rider is genuinely off-polyline most of the trip).
        deliver(location(37.01, -121.98))
        val state = NavigationSessionManager.state.value
        assertTrue(
            state is NavigationSessionManager.NavState.Navigating,
            "transit off-route must keep navigating, state=$state",
        )
        NavigationSessionManager.stop()
        assertEquals(NavigationSessionManager.NavState.Idle, NavigationSessionManager.state.value)
    }

    @Test fun stopDuringNavigationClearsSession() {
        startGranted(RouteService.TravelMode.DRIVE)
        deliver(location(37.001, -122.0))
        assertTrue(NavigationSessionManager.state.value is NavigationSessionManager.NavState.Navigating)
        NavigationSessionManager.stop()
        assertEquals(NavigationSessionManager.NavState.Idle, NavigationSessionManager.state.value)
        @Suppress("DEPRECATION")
        assertEquals(null, NavigationSessionManager.destinationName)
    }

}
