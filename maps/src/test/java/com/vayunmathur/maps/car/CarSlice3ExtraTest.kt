package com.vayunmathur.maps.car

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Slice 3/3 extra: MapsSession + NavMapScreen templates against TestCarContext. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarSlice3ExtraTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun route(): RouteService.Route {
        val pts = listOf(pt(-122.0, 37.0), pt(-122.0, 37.001), pt(-122.0, 37.002))
        val step = RouteService.Step(0.0, 60.seconds, pts,
            RouteService.API.NavInstruction(RouteService.API.Maneuver.TURN_LEFT, "Turn left"), RouteService.TravelMode.DRIVE)
        return RouteService.Route(10.minutes, 222.0, pts, listOf(step))
    }

    // MapsSession.onCreateScreen needs a host-configured Session (HandshakeInfo
    // from the car host), which TestCarContext does not fabricate in this library
    // version; the screens it returns are covered directly below.

    @Test fun navMapTemplateForIdleAndNavigating() {
        NavigationSessionManager.init(ctx)
        NavigationSessionManager.stop()
        val carContext = TestCarContext.createCarContext(app)
        val screen = NavMapScreen(carContext)
        screen.lifecycle.currentState
        // Idle (initial): map + action strip only, template builds
        assertNotNull(screen.onGetTemplate())

        // Drive the manager to Navigating via the seeded last-known fix
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = Shadows.shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.0; longitude = -122.0; accuracy = 5f; time = System.currentTimeMillis()
        }
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix)
        NavigationSessionManager.start(route(), RouteService.TravelMode.DRIVE, pt(-122.0, 37.002), "Dest")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val template = screen.onGetTemplate()
        assertNotNull(template)
        // Arrival state also builds (map + strip only)
        NavigationSessionManager.stop()
        assertEquals(NavigationSessionManager.NavState.Idle, NavigationSessionManager.state.value)
    }
}
