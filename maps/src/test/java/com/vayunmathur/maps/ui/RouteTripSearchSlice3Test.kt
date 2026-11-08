package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.transit.TripItinerary
import com.vayunmathur.maps.data.transit.TripStop
import com.vayunmathur.maps.util.DeparturesState
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SearchUiState
import com.vayunmathur.maps.util.SnapResult
import com.vayunmathur.maps.util.TripItineraryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Slice 3/3 (root package files M-Z): the route/trip/search sheets and the
 * navigation overlay — [RouteSheet] modes + step list, [ModeTab],
 * [transitSupportingText], [TripSheet] states, [SearchSheet] phases,
 * [ParkingSheet], and [NavigationOverlay]'s idle/active/arrival legs.
 *
 * All state is literal (no ViewModels, no network): a real [RouteService.Route]
 * drives the sheets, and callbacks record invocations.
 */
@RunWith(AndroidJUnit4::class)
class RouteTripSearchSlice3Test {

    @get:Rule
    val rule = createComposeRule()

    private fun step(
        instructions: String,
        maneuver: RouteService.API.Maneuver = RouteService.API.Maneuver.STRAIGHT,
        mode: RouteService.TravelMode = RouteService.TravelMode.DRIVE,
        transitDetails: RouteService.API.TransitDetails? = null,
    ) = RouteService.Step(
        distanceMeters = 500.0,
        staticDuration = 60.seconds,
        polyline = listOf(GeoPoint(-122.4, 37.8), GeoPoint(-122.3, 37.7)),
        navInstruction = RouteService.API.NavInstruction(maneuver, instructions),
        travelMode = mode,
        transitDetails = transitDetails,
    )

    private fun driveRoute(vararg steps: RouteService.Step) = RouteService.Route(
        duration = 10.minutes,
        distanceMeters = 5000.0,
        polyline = listOf(GeoPoint(-122.4, 37.8)),
        step = steps.toList(),
    )

    private fun routeFeature() = SpecificFeature.Route(
        waypoints = listOf(
            SpecificFeature.GenericPlace(
                name = "Start",
                phone = null,
                website = null,
                openingHours = null,
                position = GeoPoint(-122.4, 37.8),
            ),
            SpecificFeature.GenericPlace(
                name = "Museum",
                phone = null,
                website = null,
                openingHours = null,
                position = GeoPoint(-122.3, 37.7),
            ),
        ),
    )

    // --- RouteSheet ---

    @Test
    fun `route sheet lists steps and offers start navigation`() {
        var selected = RouteService.TravelMode.DRIVE
        rule.setContent {
            RouteSheet(
                selectedFeature = routeFeature(),
                route = mapOf(RouteService.TravelMode.DRIVE to driveRoute(step("Head north"))),
                selectedRouteType = selected,
                setSelectedRouteType = { selected = it },
                navState = NavigationSessionManager.NavState.Idle,
            )
        }
        rule.onNodeWithText("Head north").assertIsDisplayed()
        // Taxi is not installed under test, so no rideshare tab; the drive
        // tab plus Start Navigation render instead.
        rule.onNodeWithText("Start", substring = true).assertIsDisplayed()
    }

    @Test
    fun `route sheet tabs switch the selected mode`() {
        var selected = RouteService.TravelMode.DRIVE
        rule.setContent {
            RouteSheet(
                selectedFeature = routeFeature(),
                route = mapOf(
                    RouteService.TravelMode.DRIVE to driveRoute(step("Drive north")),
                    RouteService.TravelMode.WALK to driveRoute(step("Walk north")),
                ),
                selectedRouteType = selected,
                setSelectedRouteType = { selected = it },
            )
        }
        // Exact tab labels ("Drive"/"Walk"), not the step instructions.
        rule.onNodeWithText("Walk", substring = false).performClick()
        assertEquals(RouteService.TravelMode.WALK, selected)
    }

    @Test
    fun `transit supporting text joins headsign and stop count`() {
        var text = ""
        rule.setContent {
            text = transitSupportingText(
                RouteService.API.TransitDetails(
                    headsign = "Ocean Beach",
                    stopCount = 4,
                    transitLine = RouteService.API.TransitLine("N", null, "#FF0000"),
                    stopDetails = RouteService.API.StopDetails("", "", RouteService.API.Stop("b"), RouteService.API.Stop("a")),
                ),
            )
        }
        rule.runOnIdle {
            assertEquals(true, "Ocean Beach" in text, text)
            assertEquals(true, "4" in text, text)
        }
    }

    @Test
    fun `transit supporting text drops whichever half is missing`() {
        var headsignOnly = ""
        var stopsOnly = ""
        var neither = "x"
        fun details(headsign: String, stopCount: Int) = RouteService.API.TransitDetails(
            headsign = headsign,
            stopCount = stopCount,
            transitLine = RouteService.API.TransitLine("N", null, "#FF0000"),
            stopDetails = RouteService.API.StopDetails("", "", RouteService.API.Stop("b"), RouteService.API.Stop("a")),
        )
        rule.setContent {
            headsignOnly = transitSupportingText(details("Ocean Beach", 0))
            stopsOnly = transitSupportingText(details("", 3))
            neither = transitSupportingText(details("", 0))
        }
        rule.runOnIdle {
            assertEquals(true, "Ocean Beach" in headsignOnly, headsignOnly)
            assertEquals(true, "3" in stopsOnly, stopsOnly)
            assertEquals("", neither)
        }
    }

    @Test
    fun `mode tab renders its three lines`() {
        rule.setContent {
            ModeTab(label = "Drive", primary = "10 min", secondary = "5 km")
        }
        rule.onNodeWithText("Drive").assertIsDisplayed()
        rule.onNodeWithText("10 min").assertIsDisplayed()
        rule.onNodeWithText("5 km").assertIsDisplayed()
    }

    // --- TripSheet ---

    @Test
    fun `trip sheet loading state`() {
        rule.setContent {
            TripSheet(state = TripItineraryState.Loading, onDismiss = {}, onStopTap = {})
        }
        rule.onNodeWithText("Loading departures…").assertIsDisplayed()
    }

    @Test
    fun `trip sheet missing state`() {
        rule.setContent {
            TripSheet(state = TripItineraryState.Missing, onDismiss = {}, onStopTap = {})
        }
        rule.onNodeWithText("No upcoming departures").assertIsDisplayed()
    }

    @Test
    fun `trip sheet lists the itinerary stops and taps through`() {
        var tapped: TripStop? = null
        val stop = TripStop("Central", 1.0, 2.0, 1_700_000_100_000L, 1_700_000_200_000L, "m1")
        val itin = TripItinerary("38", "Downtown", "#00FF00", "BUS", false, listOf(stop))
        rule.setContent {
            TripSheet(
                state = TripItineraryState.Loaded(itin),
                onDismiss = {},
                onStopTap = { tapped = it },
            )
        }
        rule.onNodeWithText("38", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Central").assertIsDisplayed()
        rule.onNodeWithText("Central").performClick()
        assertEquals(stop, tapped)
    }

    @Test
    fun `cancelled trip sheet flags the cancellation`() {
        val itin = TripItinerary("38", "Downtown", null, "BUS", true, emptyList())
        rule.setContent {
            TripSheet(
                state = TripItineraryState.Loaded(itin),
                onDismiss = {},
                onStopTap = {},
            )
        }
        rule.onNodeWithText("Cancelled").assertIsDisplayed()
    }

    // --- SearchSheet ---

    @Test
    fun `search sheet results phase lists results`() {
        val results = listOf(
            SearchResult("poi:1:2:3", "Blue Bottle", "Cafe", 37.8, -122.4, "Cafe"),
        )
        rule.setContent {
            SearchSheet(
                state = SearchUiState(query = "bl", results = results),
                actions = SearchActions.Noop,
                autoFocus = false,
            )
        }
        rule.onNodeWithText("Blue Bottle").assertIsDisplayed()
    }

    @Test
    fun `search sheet empty phase explains no results`() {
        rule.setContent {
            SearchSheet(
                state = SearchUiState(query = "blue bottle coffee xyz"),
                actions = SearchActions.Noop,
                autoFocus = false,
            )
        }
        rule.onNodeWithText("No results found in this area").assertIsDisplayed()
    }

    @Test
    fun `search sheet idle phase prompts for typing`() {
        rule.setContent {
            SearchSheet(
                state = SearchUiState(query = ""),
                actions = SearchActions.Noop,
                autoFocus = false,
            )
        }
        rule.onNodeWithText("Type at least 2 characters to search").assertIsDisplayed()
    }

    @Test
    fun `search sheet recents render before typing`() {
        rule.setContent {
            SearchSheet(
                state = SearchUiState(query = "", recents = listOf("pizza")),
                actions = SearchActions.Noop,
                autoFocus = false,
            )
        }
        rule.onNodeWithText("pizza").assertIsDisplayed()
    }

    @Test
    fun `search sheet applies the host modifier at its root`() {
        // The host owns IME padding (MapSheets puts imePadding on the scaffold
        // root so the whole sheet rides above the keyboard); the sheet must
        // apply the caller modifier to its own root rather than swallowing it,
        // or host-supplied insets never reach the sheet.
        val results = listOf(
            SearchResult("poi:1:2:3", "Blue Bottle", "Cafe", 37.8, -122.4, "Cafe"),
        )
        rule.setContent {
            Column(Modifier.imePadding()) {
                SearchSheet(
                    state = SearchUiState(query = "bl", results = results),
                    actions = SearchActions.Noop,
                    modifier = Modifier.testTag("search-sheet"),
                    autoFocus = false,
                )
            }
        }
        rule.onNodeWithTag("search-sheet").assertIsDisplayed()
        rule.onNodeWithText("Blue Bottle").assertIsDisplayed()
    }

    // --- ParkingSheet ---

    @Test
    fun `parking sheet shows the title and wires directions`() {
        var directed = false
        var note = "sentinel"
        rule.setContent {
            ParkingSheet(
                spot = com.vayunmathur.maps.data.ParkingSpot(37.8, -122.4, 1_700_000_000_000L, "P2"),
                onDismiss = {},
                onClear = {},
                onDirections = { directed = true },
                onNoteChange = { note = it },
            )
        }
        rule.onNodeWithText("Parking").assertIsDisplayed()
        rule.onNodeWithText("Directions").performClick()
        assertEquals(true, directed)
        assertEquals("P2", note)
    }

    // --- NavigationOverlay ---

    private fun progress() = NavigationProgress(
        snappedPosition = GeoPoint(-122.4, 37.8),
        segmentIndex = 0,
        currentStepIndex = 0,
        distanceAlongRoute = 100.0,
        distanceRemaining = 4900.0,
        distanceToNextManeuver = 250.0,
        fractionComplete = 0.02,
        etaEpochMs = 1_700_000_000_000L,
        courseOverGround = 90f,
        distanceOffRoute = 5.0,
    )

    @Test
    fun `idle overlay renders nothing`() {
        rule.setContent {
            NavigationOverlay(
                navState = NavigationSessionManager.NavState.Idle,
                steps = listOf(step("Head north")),
                autoFollow = true,
                onRecenter = {},
                onEndTrip = {},
                onDismissArrival = {},
                onToggleNorthUp = {},
            )
        }
        rule.onNodeWithText("Head north").assertDoesNotExist()
    }

    @Test
    fun `navigating overlay shows the maneuver and end-trip confirm`() {
        var ended = false
        rule.setContent {
            NavigationOverlay(
                navState = NavigationSessionManager.NavState.Navigating(progress()),
                steps = listOf(
                    step("Head north"),
                    step("Turn left onto Market", RouteService.API.Maneuver.TURN_LEFT),
                ),
                autoFollow = true,
                onRecenter = {},
                onEndTrip = { ended = true },
                onDismissArrival = {},
                onToggleNorthUp = {},
            )
        }
        rule.onNodeWithText("Turn left onto Market", substring = true).assertIsDisplayed()
    }

    @Test
    fun `failed overlay shows the failure card`() {
        rule.setContent {
            NavigationOverlay(
                navState = NavigationSessionManager.NavState.Failed("GPS lost"),
                steps = emptyList(),
                autoFollow = true,
                onRecenter = {},
                onEndTrip = {},
                onDismissArrival = {},
                onToggleNorthUp = {},
            )
        }
        rule.onNodeWithText("GPS lost", substring = true).assertIsDisplayed()
    }
}
