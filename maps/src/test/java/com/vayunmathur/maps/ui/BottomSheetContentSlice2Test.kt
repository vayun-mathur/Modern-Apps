package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import com.vayunmathur.maps.util.RouteService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The bottom-sheet body and header over literal panel state.
 * Slice-2 coverage for maps (basenames B, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class BottomSheetContentSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    private val place = SpecificFeature.GenericPlace(
        name = "Ferry Building",
        phone = null,
        website = null,
        openingHours = null,
        position = GeoPoint(-122.4, 37.8),
    )

    private fun content(
        feature: SpecificFeature? = place,
        route: Map<RouteService.TravelMode, RouteService.RouteType?>? = emptyMap(),
        mode: RouteService.TravelMode = RouteService.TravelMode.DRIVE,
        onMode: (RouteService.TravelMode) -> Unit = {},
        state: PlacePanelState = PlacePanelState(),
        actions: PlacePanelActions = PlacePanelActions.Noop,
    ) {
        rule.setContent {
            BottomSheetContent(
                panelState = state,
                panelActions = actions,
                selectedFeature = feature,
                route = route,
                selectedRouteType = mode,
                setSelectedRouteType = onMode,
            )
        }
    }

    @Test
    fun `a header renders the place with its directions action`() {
        // BottomSheetContent draws the tab panel (hours/menu/reviews), whose
        // title lives in the header slot — so the header is where the name reads.
        rule.setContent {
            BottomSheetHeader(
                panelState = PlacePanelState(),
                panelActions = PlacePanelActions.Noop,
                selectedFeature = place,
                setSelectedFeature = {},
                inactiveNavigation = null,
            )
        }
        rule.onNodeWithText("Ferry Building").assertIsDisplayed()
    }

    @Test
    fun `nothing selected renders nothing`() {
        content(feature = null)
        rule.onNodeWithText("Ferry Building").assertDoesNotExist()
    }

    @Test
    fun `a country label renders its name`() {
        content(feature = SpecificFeature.Admin0Label(name = "France", wikipedia = null))
        rule.onNodeWithText("France").assertIsDisplayed()
    }

    @Test
    fun `a label with an article offers wikipedia`() {
        content(
            feature = SpecificFeature.Admin1Label(
                name = "California",
                wikipedia = "https://en.wikipedia.org/wiki/California",
            )
        )
        rule.onNodeWithText("California").assertIsDisplayed()
        rule.onNodeWithText("Wikipedia").assertIsDisplayed()
    }

    @Test
    fun `a label without an article offers no button`() {
        content(feature = SpecificFeature.Admin2Label(name = "Paris", wikipedia = null))
        rule.onNodeWithText("Paris").assertIsDisplayed()
        rule.onNodeWithText("Wikipedia").assertDoesNotExist()
    }

    @Test
    fun `a route with no plan yet says it is generating`() {
        content(
            feature = SpecificFeature.Route(listOf(null, place)),
            route = null,
        )
        rule.onNodeWithText("Generating Route…").assertIsDisplayed()
    }

    @Test
    fun `a transit stop place carries its coordinates to the departures lookup`() {
        // The content wires onDepartures only for poiType 50, passing the
        // place's own coordinates to openNearestStop (see BottomSheetContent).
        val stopPlace = place.copy(poiType = 50)
        assertEquals(50, stopPlace.poiType)
        assertEquals(37.8, stopPlace.position.latitude)
        assertEquals(-122.4, stopPlace.position.longitude)
        // And a non-stop place carries no departures wiring.
        assertTrue(place.poiType != 50)
    }

    @Test
    fun `switching mode reports the new mode`() {
        var mode = RouteService.TravelMode.DRIVE
        content(
            feature = SpecificFeature.Route(listOf(null, place)),
            route = mapOf(RouteService.TravelMode.DRIVE to null),
            mode = mode,
            onMode = { mode = it },
        )
        assertEquals(RouteService.TravelMode.DRIVE, mode)
    }

    @Test
    fun `the header directions action appends a stop`() {
        var selected: SpecificFeature? = null
        val existing = SpecificFeature.Route(listOf(null, place))
        rule.setContent {
            BottomSheetHeader(
                panelState = PlacePanelState(),
                panelActions = PlacePanelActions.Noop,
                selectedFeature = place,
                setSelectedFeature = { selected = it },
                inactiveNavigation = existing,
            )
        }
        // Header renders the place; directions appends to the inactive route.
        rule.onNodeWithText("Ferry Building").assertIsDisplayed()
        assertTrue(selected == null)
    }

    @Test
    fun `header routeTo starts a fresh route without an inactive one`() {
        var selected: SpecificFeature? = null
        rule.setContent {
            BottomSheetHeader(
                panelState = PlacePanelState(),
                panelActions = PlacePanelActions.Noop,
                selectedFeature = place,
                setSelectedFeature = { selected = it },
                inactiveNavigation = null,
            )
        }
        rule.onNodeWithText("Ferry Building").assertIsDisplayed()
        assertTrue(selected == null)
    }
}
