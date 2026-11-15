package com.vayunmathur.maps.car

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.RouteService
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * C5 Android Auto navigation screen: routing info on the final step,
 * the destination travel estimate, and the end-navigation action strip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsCarNavScreenC5Test {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    @AfterTest
    fun tearDown() {
        NavigationSessionManager.stop()
    }

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun route() = RouteService.Route(
        10.minutes, 2_000.0,
        listOf(pt(-122.0, 37.0), pt(-122.0, 37.002)),
        listOf(
            RouteService.Step(
                500.0, 60.seconds, listOf(pt(-122.0, 37.0)),
                RouteService.API.NavInstruction(RouteService.API.Maneuver.DEPART, "Head north"),
                RouteService.TravelMode.DRIVE,
            ),
            RouteService.Step(
                1_500.0, 9.minutes, listOf(pt(-122.0, 37.001)),
                RouteService.API.NavInstruction(RouteService.API.Maneuver.TURN_LEFT, "Turn left"),
                RouteService.TravelMode.DRIVE,
            ),
        ),
    )

    @Test
    fun navTemplate_finalStepPointsAtDestinationWithEstimate() {
        NavigationSessionManager.init(ctx)
        NavigationSessionManager.stop()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = Shadows.shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.0; longitude = -122.0; accuracy = 5f; time = System.currentTimeMillis()
        }
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix)
        NavigationSessionManager.start(route(), RouteService.TravelMode.DRIVE, pt(-122.0, 37.002), "C5 Dest")
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val screen = NavMapScreen(TestCarContext.createCarContext(app))
        screen.lifecycle.currentState
        // Force the session to the final step so routing info names the
        // destination rather than an upcoming maneuver.
        val progressField = NavigationSessionManager::class.java
            .getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = progressField.get(NavigationSessionManager)
            as kotlinx.coroutines.flow.MutableStateFlow<NavigationSessionManager.NavState>
        val progress = NavigationProgress(
            snappedPosition = pt(-122.0, 37.0015),
            segmentIndex = 1,
            currentStepIndex = 1,
            distanceAlongRoute = 1_750.0,
            distanceRemaining = 250.0,
            fractionComplete = 0.875,
            courseOverGround = 90f,
            distanceOffRoute = 0.0,
            distanceToNextManeuver = 250.0,
            etaEpochMs = System.currentTimeMillis() + 5 * 60_000,
        )
        flow.value = NavigationSessionManager.NavState.Navigating(progress)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val template = screen.onGetTemplate()
        assertNotNull(template)
        val navTemplate = template as androidx.car.app.navigation.model.NavigationTemplate
        assertNotNull(navTemplate.navigationInfo, "final-step routing info present")
        assertNotNull(navTemplate.destinationTravelEstimate, "travel estimate present")
        assertNotNull(navTemplate.actionStrip, "action strip present")
    }
}
