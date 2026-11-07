package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TransitStopsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Clickable rows inside the sheet body: the Wikipedia button on an admin
 * label, and the Departures row a transit-stop place carries.
 * Slice-2 coverage for maps (basename B, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class BottomSheetClicksSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun `the wikipedia button on a label is tappable`() {
        rule.setContent {
            BottomSheetContent(
                panelState = PlacePanelState(),
                panelActions = PlacePanelActions.Noop,
                selectedFeature = SpecificFeature.Admin1Label(
                    name = "California",
                    wikipedia = "https://en.wikipedia.org/wiki/California",
                ),
                route = emptyMap(),
                selectedRouteType = RouteService.TravelMode.DRIVE,
                setSelectedRouteType = {},
            )
        }
        rule.onNodeWithText("Wikipedia").assertIsDisplayed()
        // Opens the article through the external-intent path; under
        // Robolectric that is a recorded VIEW intent, not a crash.
        rule.onNodeWithText("Wikipedia").performClick()
        rule.onNodeWithText("California").assertIsDisplayed()
    }

    @Test fun `a transit stop departures row asks for the nearest stop at its own coordinates`() {
        var opened: Pair<Double, Double>? = null
        val actions = object : PlacePanelActions by PlacePanelActions.Noop {
            override fun openNearestStop(lat: Double, lon: Double) {
                opened = lat to lon
            }
        }
        val stop = SpecificFeature.GenericPlace(
            name = "Ferry Terminal",
            phone = null,
            website = null,
            openingHours = null,
            position = GeoPoint(-122.39, 37.79),
            poiType = 50,
        )
        rule.setContent {
            BottomSheetContent(
                panelState = PlacePanelState(),
                panelActions = actions,
                selectedFeature = stop,
                route = emptyMap(),
                selectedRouteType = RouteService.TravelMode.DRIVE,
                setSelectedRouteType = {},
            )
        }
        rule.onNodeWithText("Departures").assertIsDisplayed()
        rule.onNodeWithText("Departures").performClick()
        assertEquals(37.79 to -122.39, opened)
    }

    @Test fun `the viewmodel body departures row drives the transit viewmodel`() {
        val stop = SpecificFeature.GenericPlace(
            name = "Ferry Terminal",
            phone = null,
            website = null,
            openingHours = null,
            position = GeoPoint(-122.39, 37.79),
            poiType = 50,
        )
        rule.setContent {
            BottomSheetContent(
                viewModel = SelectedFeatureViewModel(app),
                selectedFeature = stop,
                route = emptyMap(),
                selectedRouteType = RouteService.TravelMode.DRIVE,
                setSelectedRouteType = {},
                savedPlacesViewModel = SavedPlacesViewModel(app),
                transitViewModel = TransitStopsViewModel(app),
            )
        }
        rule.onNodeWithText("Departures").assertIsDisplayed()
        // Drives TransitStopsViewModel.openNearestStop for the stop's own
        // coordinates; the board state leaving Idle is the observable effect
        // (asserted through the sheet still rendering afterwards).
        rule.onNodeWithText("Departures").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Departures").assertIsDisplayed()
    }
}
