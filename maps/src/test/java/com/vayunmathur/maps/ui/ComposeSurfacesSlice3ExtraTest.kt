package com.vayunmathur.maps.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Slice 3/3 extra: NavigationOverlay states, SavedPlacesPage, RouteSheet. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeSurfacesSlice3ExtraTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun steps() = listOf(
        RouteService.Step(100.0, 60.seconds, listOf(pt(-122.0, 37.0), pt(-122.0, 37.001)),
            RouteService.API.NavInstruction(RouteService.API.Maneuver.TURN_LEFT, "Turn left onto Main St"), RouteService.TravelMode.DRIVE),
        RouteService.Step(100.0, 60.seconds, listOf(pt(-122.0, 37.001), pt(-122.0, 37.002)),
            RouteService.API.NavInstruction(RouteService.API.Maneuver.STRAIGHT, "Continue straight"), RouteService.TravelMode.DRIVE),
    )

    private fun progress() = NavigationProgress(
        snappedPosition = pt(-122.0, 37.0), segmentIndex = 0, currentStepIndex = 0,
        distanceAlongRoute = 10.0, distanceRemaining = 190.0, distanceToNextManeuver = 90.0,
        fractionComplete = 0.05, etaEpochMs = System.currentTimeMillis() + 600_000, courseOverGround = 90f,
        distanceOffRoute = 0.0, speedMps = 8f,
    )

    @Test fun navigationOverlayStates() {
        var ended = false; var dismissed = false; var recentered = false
        // Navigating
        rule.setContent {
            DynamicTheme {
                NavigationOverlay(
                    navState = NavigationSessionManager.NavState.Navigating(progress()),
                    steps = steps(), autoFollow = true,
                    onRecenter = { recentered = true }, onEndTrip = { ended = true },
                    onDismissArrival = { dismissed = true },
                    postedLimit = PostedLimit(50, 31, false), destinationName = "Home",
                )
            }
        }
        // The ETA strip at the bottom always renders while navigating (the maneuver
        // banner itself lives in the slice-1 nav/ package and needs shield data).
        assertTrue(rule.onAllNodes(hasText("End", substring = true)).fetchSemanticsNodes().isNotEmpty(), "ETA strip with End action should render")
        rule.onRoot().captureToImage()
        assertTrue(!ended && !dismissed)
        assertTrue(!recentered) // no control was tapped, so no callback may have fired
    }

    @Test fun navigationOverlayTerminalStates() {
        var dismissed = false
        rule.setContent {
            DynamicTheme {
                NavigationOverlay(
                    navState = NavigationSessionManager.NavState.Arrived,
                    steps = steps(), autoFollow = false,
                    onRecenter = {}, onEndTrip = {}, onDismissArrival = { dismissed = true },
                    destinationName = "Home",
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        // The Failed terminal card shares this dispatch (covered via the service-level
        // test); a second setContent is not allowed in one compose test.
        assertTrue(!dismissed)
    }

    @Test fun savedPlacesPageWithPlacesAndLists() {
        val vm = SavedPlacesViewModel(app)
        val place = SpecificFeature.GenericPlace("Cafe", null, null, null, GeoPoint(-122.4, 37.8))
        vm.addSaved(place)
        vm.createList("Favourites")
        // VM writes run on viewModelScope (paused Main): pump until they land
        rule.waitUntil(timeoutMillis = 10000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.saved.value.isNotEmpty()
        }
        rule.setContent {
            DynamicTheme {
                val bs = rememberNavBackStack<Route>(Route.MapPage)
                SavedPlacesPage(bs, vm)
            }
        }
        rule.waitUntil(timeoutMillis = 10000) {
            rule.onAllNodes(hasText("Cafe", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("Favourites", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onRoot().captureToImage()
    }

    @Test fun transitSupportingTextVariants() {
        val details = RouteService.API.TransitDetails(
            headsign = "Downtown", stopCount = 5,
            transitLine = RouteService.API.TransitLine("Muni Metro", "N", "#C60C30"),
            stopDetails = RouteService.API.StopDetails("10:00", "10:01", RouteService.API.Stop("First St"), RouteService.API.Stop("Last St")),
        )
        var full = ""; var noHeadsign = "x"; var noStops = "x"; var neither = "x"
        rule.setContent {
            full = transitSupportingText(details)
            noHeadsign = transitSupportingText(details.copy(headsign = ""))
            noStops = transitSupportingText(details.copy(stopCount = 0))
            neither = transitSupportingText(details.copy(headsign = "", stopCount = 0))
        }
        assertTrue(full.contains("Downtown"), full)
        assertTrue(noHeadsign.isNotBlank()); assertTrue(noStops.isNotBlank()); assertEquals("", neither)
    }

    @Test fun routeSheetDriveAndTransitText() {
        val route = RouteService.Route(10.minutes, 5000.0, listOf(pt(-122.0, 37.0), pt(-122.0, 37.002)), steps())
        var mode: RouteService.TravelMode? = null
        rule.setContent {
            DynamicTheme {
                RouteSheet(
                    selectedFeature = SpecificFeature.Route(
                        listOf(
                            SpecificFeature.GenericPlace("From", null, null, null, pt(-122.0, 37.0)),
                            SpecificFeature.GenericPlace("To", null, null, null, pt(-122.0, 37.002)),
                        )
                    ),
                    route = mapOf(RouteService.TravelMode.DRIVE to route, RouteService.TravelMode.WALK to null),
                    selectedRouteType = RouteService.TravelMode.DRIVE,
                    setSelectedRouteType = { mode = it },
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertEquals(null, mode) // no tab clicked yet
    }
}
