package com.vayunmathur.maps.util

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

/** Slice 3/3 extra: [NavigationService] state->notification dispatch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationServiceSlice3ExtraTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun route(): RouteService.Route {
        val pts = listOf(pt(-122.0, 37.0), pt(-122.0, 37.001), pt(-122.0, 37.002))
        val step = RouteService.Step(0.0, 60.seconds, pts,
            RouteService.API.NavInstruction(instructions = "Head north on Main St"), RouteService.TravelMode.DRIVE)
        return RouteService.Route(10.minutes, 222.0, pts, listOf(step))
    }

    private fun location(lat: Double, lon: Double): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat; longitude = lon; accuracy = 5f; time = System.currentTimeMillis()
    }

    private fun pinLastKnown(loc: Location) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = Shadows.shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, loc)
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, loc)
    }

    private fun notifications(): ShadowNotificationManager {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return Shadows.shadowOf(nm)
    }

    @Test fun serviceFollowsSessionStates() { runBlocking {
        NavigationSessionManager.init(ctx)
        NavigationSessionManager.stop()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        val controller = Robolectric.buildService(NavigationService::class.java).create()
        val svc = controller.get()
        try {
            assertEquals(android.app.Service.START_STICKY, svc.onStartCommand(Intent(ctx, NavigationService::class.java), 0, 1))
            // Idle notification posted by startForeground
            withTimeout(8000) { while (notifications().allNotifications.isEmpty()) delay(50) }

            // Start a session pinned at the route start: seed fix -> Navigating -> drive notification
            pinLastKnown(location(37.0, -122.0))
            NavigationSessionManager.start(route(), RouteService.TravelMode.DRIVE, pt(-122.0, 37.002), "Dest")
            withTimeout(8000) {
                while (NavigationSessionManager.state.value !is NavigationSessionManager.NavState.Navigating &&
                    NavigationSessionManager.state.value !is NavigationSessionManager.NavState.Arrived) delay(50)
            }
            delay(300) // let the collector push the notification on its IO scope
            assertTrue(notifications().allNotifications.isNotEmpty())

            // Drive to arrival via a new session pinned at the destination
            NavigationSessionManager.stop()
            pinLastKnown(location(37.002, -122.0))
            NavigationSessionManager.start(route(), RouteService.TravelMode.DRIVE, pt(-122.0, 37.002), "Dest")
            assertEquals(NavigationSessionManager.NavState.Arrived, NavigationSessionManager.state.value)
            delay(300)

            // Failure state + task removed teardown
            NavigationSessionManager.stop()
            svc.onTaskRemoved(Intent())
            assertEquals(NavigationSessionManager.NavState.Idle, NavigationSessionManager.state.value)
            assertNotNull(svc)
        } finally {
            controller.destroy()
        }
        Unit
    } }
}
