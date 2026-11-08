package com.vayunmathur.maps.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Slice 3/3 extra: RouteSheet mode tabs, step list variants, start button. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteSheetSlice3ExtraTest {

    @get:Rule val rule = createComposeRule()

    private fun pt(lon: Double, lat: Double) = GeoPoint(lon, lat)

    private fun feature() = SpecificFeature.Route(listOf(
        SpecificFeature.GenericPlace("From", null, null, null, pt(-122.0, 37.0)),
        SpecificFeature.GenericPlace("To", null, null, null, pt(-122.0, 37.002)),
    ))

    private fun driveRoute(): RouteService.Route {
        val transit = RouteService.API.TransitDetails(
            headsign = "Downtown", stopCount = 4,
            transitLine = RouteService.API.TransitLine("Muni Metro", "N", "#C60C30"),
            stopDetails = RouteService.API.StopDetails("10:05", "10:01", RouteService.API.Stop("A St"), RouteService.API.Stop("B St")),
        )
        return RouteService.Route(
            12.minutes, 3200.0, listOf(pt(-122.0, 37.0), pt(-122.0, 37.002)),
            listOf(
                RouteService.Step(100.0, 60.seconds, listOf(pt(-122.0, 37.0)),
                    RouteService.API.NavInstruction(RouteService.API.Maneuver.DEPART, "Head north on Main St"), RouteService.TravelMode.DRIVE),
                RouteService.Step(200.0, 90.seconds, listOf(pt(-122.0, 37.001)),
                    RouteService.API.NavInstruction(RouteService.API.Maneuver.STRAIGHT, "Ride the N"), RouteService.TravelMode.TRANSIT, transitDetails = transit),
            ),
            departureTime = "10:00 AM", arrivalTime = "10:12 AM",
        )
    }

    @Test fun tabsSwitchModesAndStepsRender() {
        var mode by mutableStateOf(RouteService.TravelMode.DRIVE)
        var selected: RouteService.TravelMode? = null
        rule.setContent {
            DynamicTheme {
                RouteSheet(
                    selectedFeature = feature(),
                    route = mapOf(
                        RouteService.TravelMode.DRIVE to driveRoute(),
                        RouteService.TravelMode.WALK to RouteService.EmptyRoute(),
                        RouteService.TravelMode.TRANSIT to null,
                    ),
                    selectedRouteType = mode,
                    setSelectedRouteType = { selected = it; mode = it },
                )
            }
        }
        rule.onNodeWithText("Head north on Main St").assertIsDisplayed()
        rule.onNodeWithText("Leave at", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Arrive at", substring = true).assertIsDisplayed()
        // Start Navigation is offered for the drive route while Idle
        rule.onNodeWithText("Start Navigation").assertIsDisplayed()
        // Walk tab -> EmptyRoute message
        rule.onNodeWithText("Walk").performClick()
        assertEquals(RouteService.TravelMode.WALK, selected)
        rule.onNodeWithText("No route found").assertIsDisplayed()
        // Transit tab -> still generating (null route)
        rule.onNodeWithText("Transit").performClick()
        assertEquals(RouteService.TravelMode.TRANSIT, selected)
        rule.onNodeWithText("Generating Route…").assertIsDisplayed()
        rule.onRoot().captureToImage()
    }

    @Test fun navigatingStateHidesStartButton() {
        rule.setContent {
            DynamicTheme {
                RouteSheet(
                    selectedFeature = feature(),
                    route = mapOf(RouteService.TravelMode.DRIVE to driveRoute()),
                    selectedRouteType = RouteService.TravelMode.DRIVE,
                    setSelectedRouteType = {},
                    navState = NavigationSessionManager.NavState.Recalculating,
                )
            }
        }
        // Not Idle: no Start Navigation button, steps still list
        rule.onAllNodes(androidx.compose.ui.test.hasText("Start Navigation")).fetchSemanticsNodes().let {
            assertEquals(0, it.size)
        }
        rule.onNodeWithText("Head north on Main St").assertIsDisplayed()
    }

    @Test fun transitSelectedModeStillListsStepsWithoutStart() {
        rule.setContent {
            DynamicTheme {
                RouteSheet(
                    selectedFeature = feature(),
                    route = mapOf(RouteService.TravelMode.TRANSIT to driveRoute()),
                    selectedRouteType = RouteService.TravelMode.TRANSIT,
                    setSelectedRouteType = {},
                )
            }
        }
        rule.onNodeWithText("Ride the N").assertIsDisplayed()
        rule.onAllNodes(androidx.compose.ui.test.hasText("Start Navigation")).fetchSemanticsNodes().let {
            assertEquals(0, it.size) // TRANSIT never offers Start Navigation
        }
    }
}
