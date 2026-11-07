package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The ViewModel-backed bottom-sheet body (the overload the map page actually
 * calls), plus the stateless branches the first pass did not render: a
 * restaurant and a transit-stop place.
 * Slice-2 coverage for maps (basename B, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class BottomSheetContentVmSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val cafe = SpecificFeature.Restaurant(
        name = "Blue Bottle",
        phone = null,
        website = null,
        menu = "https://example.com/menu",
        openingHours = null,
        position = GeoPoint(-122.4, 37.8),
    )
    private val place = SpecificFeature.GenericPlace(
        name = "Ferry Building",
        phone = null,
        website = null,
        openingHours = null,
        position = GeoPoint(-122.39, 37.79),
        address = "1 Ferry Plaza, San Francisco",
    )

    private fun vmContent(feature: SpecificFeature?, route: Map<RouteService.TravelMode, RouteService.RouteType?>? = emptyMap()) {
        rule.setContent {
            BottomSheetContent(
                viewModel = SelectedFeatureViewModel(app),
                selectedFeature = feature,
                route = route,
                selectedRouteType = RouteService.TravelMode.DRIVE,
                setSelectedRouteType = {},
                savedPlacesViewModel = SavedPlacesViewModel(app),
                transitViewModel = TransitStopsViewModel(app),
            )
        }
    }

    @Test fun `the viewmodel body renders a place panel`() {
        // The name lives in the header slot; the body shows the place's rows.
        vmContent(place)
        rule.onNodeWithText("1 Ferry Plaza, San Francisco").assertIsDisplayed()
    }

    @Test fun `the viewmodel body renders a restaurant panel`() {
        vmContent(cafe)
        rule.onNodeWithText("Menu").assertIsDisplayed()
    }

    @Test fun `the viewmodel body renders an admin label`() {
        vmContent(SpecificFeature.Admin1Label(name = "California", wikipedia = null))
        rule.onNodeWithText("California").assertIsDisplayed()
    }

    @Test fun `the viewmodel body says generating while a route is planned`() {
        vmContent(SpecificFeature.Route(listOf(null, place)), route = null)
        rule.onNodeWithText("Generating Route…").assertIsDisplayed()
    }

    @Test fun `the viewmodel body renders nothing with no selection`() {
        vmContent(null)
        rule.onNodeWithText("Ferry Building").assertDoesNotExist()
        rule.onNodeWithText("Generating Route…").assertDoesNotExist()
    }

    @Test fun `a transit stop place renders its panel with departures wiring`() {
        rule.setContent {
            BottomSheetContent(
                panelState = PlacePanelState(),
                panelActions = PlacePanelActions.Noop,
                selectedFeature = place.copy(poiType = 50),
                route = emptyMap(),
                selectedRouteType = RouteService.TravelMode.DRIVE,
                setSelectedRouteType = {},
            )
        }
        // The transit-stop place gets a Departures row in its panel.
        rule.onNodeWithText("Departures").assertIsDisplayed()
    }
}
