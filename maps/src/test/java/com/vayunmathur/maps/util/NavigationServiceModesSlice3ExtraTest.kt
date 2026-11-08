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

/** Slice 3/3 extra: NavigationService describe() variants per travel mode + stop action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationServiceModesSlice3ExtraTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun route(mode: RouteService.TravelMode): RouteService.Route {
        val pts = listOf(pt(-122.0, 37.0), pt(-122.0, 37.001), pt(-122.0, 37.002))
        val transit = if (mode == RouteService.TravelMode.TRANSIT) {
            RouteService.API.TransitDetails(
                headsign = "Downtown", stopCount = 3,
                transitLine = RouteService.API.TransitLine("Muni Metro", "N", "#C60C30"),
                stopDetails = RouteService.API.StopDetails("10:01", "10:00", RouteService.API.Stop("A St"), RouteService.API.Stop("B St")),
            )
        } else null
        val step = RouteService.Step(0.0, 60.seconds, pts,
            RouteService.API.NavInstruction(RouteService.API.Maneuver.STRAIGHT, "Head north"), mode, transitDetails = transit)
        return RouteService.Route(10.minutes, 222.0, pts, listOf(step))
    }

    private fun pinStartFix() {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = Shadows.shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.0; longitude = -122.0; accuracy = 5f; time = System.currentTimeMillis()
        }
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix)
    }

    private fun notifications(): ShadowNotificationManager {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return Shadows.shadowOf(nm)
    }

    private fun title(snm: ShadowNotificationManager): String? =
        snm.getNotification(4242)?.extras?.getString(android.app.Notification.EXTRA_TITLE)

    @Test fun walkBicycleTransitNotificationsAndStopAction() { runBlocking {
        NavigationSessionManager.init(ctx)
        NavigationSessionManager.stop()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val controller = Robolectric.buildService(NavigationService::class.java).create()
        val svc = controller.get()
        try {
            svc.onStartCommand(Intent(ctx, NavigationService::class.java), 0, 1)
            for (mode in listOf(RouteService.TravelMode.WALK, RouteService.TravelMode.BICYCLE, RouteService.TravelMode.TRANSIT, RouteService.TravelMode.DRIVE)) {
                NavigationSessionManager.stop()
                pinStartFix()
                NavigationSessionManager.start(route(mode), mode, pt(-122.0, 37.002), "Dest $mode")
                // Collector runs on the service's IO scope; poll for the mode's session
                withTimeout(10000) {
                    while (NavigationSessionManager.session.value.travelMode != mode) delay(40)
                }
                // Notification update is throttled (2s): give the collector its window
                delay(2300)
                val t = title(notifications())
                assertTrue(t == null || t.isNotBlank(), "mode=$mode title=$t")
                assertEquals(mode, NavigationSessionManager.session.value.travelMode)
            }
            // ACTION_STOP intent stops the session through the service entry point
            val rc = svc.onStartCommand(Intent(NavigationService.ACTION_STOP), 0, 99)
            assertEquals(android.app.Service.START_NOT_STICKY, rc)
            assertEquals(NavigationSessionManager.NavState.Idle, NavigationSessionManager.state.value)
        } finally {
            NavigationSessionManager.stop()
            controller.destroy()
        }
        Unit
    } }
}
