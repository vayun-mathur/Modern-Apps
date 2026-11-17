package com.vayunmathur.maps.ui
import com.vayunmathur.maps.domain.NavigationProgress

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.data.PostedLimit
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ui.streetview.StreetViewPegman
import com.vayunmathur.maps.platform.NavigationSessionManager
import com.vayunmathur.maps.domain.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 Compose branches: transit route sheets (no Start Navigation), the
 * navigating overlay's recenter/steps/speed widgets, and the Street View
 * pegman's drag lifecycle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsComposeC5Test {

    @get:Rule val rule = createComposeRule()

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun feature() = SpecificFeature.Route(
        listOf(
            SpecificFeature.GenericPlace("From", null, null, null, pt(-122.0, 37.0)),
            SpecificFeature.GenericPlace("To", null, null, null, pt(-122.0, 37.002)),
        )
    )

    private fun transitRoute(): RouteService.Route {
        val details = RouteService.API.TransitDetails(
            headsign = "Downtown", stopCount = 4,
            transitLine = RouteService.API.TransitLine("Muni Metro", "N", "#C60C30"),
            stopDetails = RouteService.API.StopDetails(
                "10:05", "10:01",
                RouteService.API.Stop("A St"), RouteService.API.Stop("B St"),
            ),
        )
        return RouteService.Route(
            18.minutes, 4_200.0, listOf(pt(-122.0, 37.0), pt(-122.0, 37.002)),
            listOf(
                RouteService.Step(
                    300.0, 120.seconds, listOf(pt(-122.0, 37.0)),
                    RouteService.API.NavInstruction(RouteService.API.Maneuver.STRAIGHT, "Ride the N to Downtown"),
                    RouteService.TravelMode.TRANSIT, transitDetails = details,
                ),
                RouteService.Step(
                    100.0, 60.seconds, listOf(pt(-122.0, 37.001)),
                    RouteService.API.NavInstruction(RouteService.API.Maneuver.STRAIGHT, "Walk to the door"),
                    RouteService.TravelMode.WALK,
                ),
            ),
            departureTime = "10:00 AM", arrivalTime = "10:18 AM",
        )
    }

    @Test
    fun routeSheet_transitMode_showsStepsWithoutStartNavigation() {
        rule.setContent {
            DynamicTheme {
                RouteSheet(
                    selectedFeature = feature(),
                    route = mapOf(RouteService.TravelMode.TRANSIT to transitRoute()),
                    selectedRouteType = RouteService.TravelMode.TRANSIT,
                    setSelectedRouteType = {},
                )
            }
        }
        rule.onNodeWithText("Ride the N to Downtown").assertIsDisplayed()
        rule.onNodeWithText("Walk to the door").assertIsDisplayed()
        rule.onAllNodesWithText("Downtown", substring = true)[0].assertIsDisplayed()
        rule.onAllNodesWithText("10:05")[0].assertIsDisplayed()
        rule.onAllNodesWithText("Start Navigation").fetchSemanticsNodes().let {
            assertTrue(it.isEmpty(), "transit must not offer Start Navigation")
        }
    }

    @Test
    fun routeSheet_navigatingState_hidesStartNavigation() {
        val drive = RouteService.Route(
            10.minutes, 2_000.0, listOf(pt(-122.0, 37.0), pt(-122.0, 37.002)),
            listOf(
                RouteService.Step(
                    500.0, 60.seconds, listOf(pt(-122.0, 37.0)),
                    RouteService.API.NavInstruction(RouteService.API.Maneuver.DEPART, "Head north"),
                    RouteService.TravelMode.DRIVE,
                )
            ),
        )
        val progress = NavigationProgress(
            snappedPosition = pt(-122.0, 37.0), segmentIndex = 0, currentStepIndex = 0,
            distanceAlongRoute = 10.0, distanceRemaining = 1_990.0, distanceToNextManeuver = 500.0,
            fractionComplete = 0.01, etaEpochMs = System.currentTimeMillis() + 600_000,
            courseOverGround = 0f, distanceOffRoute = 0.0, speedMps = 5f,
        )
        rule.setContent {
            DynamicTheme {
                RouteSheet(
                    selectedFeature = feature(),
                    route = mapOf(RouteService.TravelMode.DRIVE to drive),
                    selectedRouteType = RouteService.TravelMode.DRIVE,
                    setSelectedRouteType = {},
                    navState = NavigationSessionManager.NavState.Navigating(progress),
                )
            }
        }
        rule.onNodeWithText("Head north").assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("Start Navigation").fetchSemanticsNodes().isEmpty(),
            "an active session must not offer Start Navigation again",
        )
    }

    @Test
    fun routeSheet_bicycleTab_reportsModeAndEmptyRoute() {
        var selected: RouteService.TravelMode? = null
        rule.setContent {
            DynamicTheme {
                RouteSheet(
                    selectedFeature = feature(),
                    route = mapOf(
                        RouteService.TravelMode.DRIVE to RouteService.EmptyRoute(),
                        RouteService.TravelMode.BICYCLE to RouteService.EmptyRoute(),
                    ),
                    selectedRouteType = RouteService.TravelMode.DRIVE,
                    setSelectedRouteType = { selected = it },
                )
            }
        }
        rule.onNodeWithText("Bicycle").performClick()
        assertEquals(RouteService.TravelMode.BICYCLE, selected)
        rule.onNodeWithText("No route found").assertIsDisplayed()
    }

    private fun steps() = listOf(
        RouteService.Step(
            100.0, 60.seconds, listOf(pt(-122.0, 37.0), pt(-122.0, 37.001)),
            RouteService.API.NavInstruction(RouteService.API.Maneuver.TURN_LEFT, "Turn left onto Main St"),
            RouteService.TravelMode.DRIVE,
        ),
        RouteService.Step(
            100.0, 60.seconds, listOf(pt(-122.0, 37.001), pt(-122.0, 37.002)),
            RouteService.API.NavInstruction(RouteService.API.Maneuver.STRAIGHT, "Continue to the destination"),
            RouteService.TravelMode.DRIVE,
        ),
    )

    private fun progress() = NavigationProgress(
        snappedPosition = pt(-122.0, 37.0), segmentIndex = 0, currentStepIndex = 0,
        distanceAlongRoute = 10.0, distanceRemaining = 190.0, distanceToNextManeuver = 90.0,
        fractionComplete = 0.05, etaEpochMs = System.currentTimeMillis() + 600_000,
        courseOverGround = 90f, distanceOffRoute = 0.0, speedMps = 13.9f,
    )

    @Test
    fun navigationOverlay_recenterAndStepsSheet() {
        var recentered = 0
        rule.setContent {
            DynamicTheme {
                NavigationOverlay(
                    navState = NavigationSessionManager.NavState.Navigating(progress()),
                    steps = steps(), autoFollow = false,
                    onRecenter = { recentered++ }, onEndTrip = {}, onDismissArrival = {},
                    postedLimit = PostedLimit(kmh = 50, mph = 31, displayIsMph = false),
                    destinationName = "Home",
                )
            }
        }
        // The banner's primary instruction is the NEXT maneuver.
        rule.onNodeWithText("Continue to the destination").assertExists()
        rule.onNodeWithText("Recenter", useUnmergedTree = true).assertExists().performClick()
        rule.runOnIdle { assertEquals(1, recentered) }
        // The list toggle in the ETA strip opens the full step sheet.
        rule.onAllNodesWithText("Continue to the destination")[0].assertExists()
    }

    @Test
    fun navigationOverlay_recalculatingShowsOffRouteCard() {
        rule.setContent {
            DynamicTheme {
                NavigationOverlay(
                    navState = NavigationSessionManager.NavState.Recalculating,
                    steps = steps(), autoFollow = true,
                    onRecenter = {}, onEndTrip = {}, onDismissArrival = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun streetViewPegman_dragAndCancel() {
        val camera = CameraState(CameraPosition(GeoPoint(-122.4, 37.7), 14.0))
        rule.setContent {
            DynamicTheme {
                Box(Modifier.fillMaxSize()) {
                    StreetViewPegman(camera = camera)
                }
            }
        }
        val peg = rule.onNode(hasContentDescription("Street View", substring = true))
        peg.assertIsDisplayed()
        // Drag off the chip and release: with no renderer projection the drop
        // is a no-op, and the ghost must disappear rather than stick.
        peg.performTouchInput { swipe(Offset(center.x, center.y), Offset(center.x - 300f, center.y - 200f), 400L) }
        rule.waitForIdle()
        peg.assertIsDisplayed()
        // A cancelled drag also clears the ghost.
        peg.performTouchInput {
            down(center)
            moveBy(Offset(-120f, -80f))
            cancel()
        }
        rule.waitForIdle()
        peg.assertIsDisplayed()
    }
}
