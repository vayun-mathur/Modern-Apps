package com.vayunmathur.maps.ui

import android.app.Application
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapBody
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.FreeHeightSheetState
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.ui.map.MapChromeState
import com.vayunmathur.maps.ui.map.MapOverlay
import com.vayunmathur.maps.util.DeparturesState
import com.vayunmathur.maps.util.MapSettingsViewModel
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.ParkingViewModel
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchUiState
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TripItineraryState
import com.vayunmathur.maps.util.TransitStopsViewModel
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Slice 3/3 pass 5: compact MapSheets with real sheet states. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Slice3Pass5SheetsTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @androidx.compose.runtime.Composable
    private fun compactScope(selectedFeature: SpecificFeature? = null): MapPageScope {
        val messenger = rememberMessenger()
        val cs = rememberCoroutineScope()
        return MapPageScope(
            context = app,
            backStack = NavBackStack(arrayOf(Route.MapPage)),
            viewModel = SelectedFeatureViewModel(app),
            savedPlacesViewModel = SavedPlacesViewModel(app),
            searchViewModel = MapsSearchViewModel(app),
            settingsViewModel = MapSettingsViewModel(app),
            parkingViewModel = ParkingViewModel(app),
            transitViewModel = TransitStopsViewModel(app),
            coroutineScope = cs,
            messenger = messenger,
            noResultsMessage = "No results",
            chrome = MapChromeState(
                mutableStateOf(RouteService.TravelMode.DRIVE),
                mutableStateOf(null),
                mutableStateOf(MapOverlay.None),
                mutableStateOf(true),
                mutableStateOf(false),
                mutableStateOf(null),
                mutableStateOf(0L),
            ),
            camera = CameraState(),
            selectedFeature = selectedFeature,
            inactiveNavigation = null,
            route = null,
            poiSection = PoiSection.DETAILS,
            poiEnrichmentKey = PoiEnrichmentKey(0, 0, false),
            userPosition = GeoPoint(-122.4, 37.8),
            userBearing = null,
            userHeadingAccuracy = 3,
            savedPins = emptyList(),
            parkingSpot = null,
            searchResults = emptyList(),
            searchRecents = emptyList(),
            selectedTransitStop = null,
            departuresState = DeparturesState.Idle,
            tripItineraryState = TripItineraryState.Idle,
            familyMembers = emptyList(),
            trafficEnabled = false, satelliteEnabled = false, safetyEnabled = false,
            transitEnabled = false, globeEnabled = false, googleServices = false,
            body = MapBody.Earth, moonTextures = null, darkMap = false,
            navState = NavigationSessionManager.NavState.Idle,
            navSession = NavigationSessionManager.NavSession(),
            navProgress = null, isNavigating = false, browsing = true,
            searchBarVisible = true, searchBarLiftPx = 0, onSearchBarLift = {},
            sheetState = FreeHeightSheetState(SheetValue.PartiallyExpanded),
            searchSheetState = FreeHeightSheetState(SheetValue.PartiallyExpanded),
            searchRequest = null,
            openSearch = { _, _ -> }, settingsAction = emptyList(), wide = false,
        )
    }

    @Test fun sheetsWithNoSelectionShowNoPlaceContent() {
        rule.setContent {
            val scope = compactScope()
            DynamicTheme {
                with(scope) {
                    MapSheets(searchActions = null, searchState = SearchUiState())
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Blue Bottle", substring = true).assertDoesNotExist()
    }

    @Test fun sheetsWithSelectionShowPlaceSheetContent() {
        val place = SpecificFeature.GenericPlace(
            "Blue Bottle", null, null, null, GeoPoint(-122.4, 37.8),
        )
        rule.setContent {
            val scope = compactScope(place)
            scope.viewModel.set(place)
            DynamicTheme {
                with(scope) {
                    MapSheets(searchActions = null, searchState = SearchUiState())
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Blue Bottle", substring = true).assertExists()
    }

    @Test fun vmBoundPlaceSheetRendersDetails() {
        val place = SpecificFeature.GenericPlace(
            "Blue Bottle", null, null, null, GeoPoint(-122.4, 37.8),
            address = "1 Ferry Bldg", reviewSummary = "Great coffee",
        )
        rule.setContent {
            val vm = SelectedFeatureViewModel(app)
            vm.set(place)
            DynamicTheme {
                PlaceSheet(viewModel = vm, feature = place)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("1 Ferry Bldg", substring = true).assertExists()
    }

    @Test fun vmBoundRestaurantSheetRendersMenu() {
        val restaurant = SpecificFeature.Restaurant(
            "Pizza Place", null, null, "https://example.com/menu", null, GeoPoint(-122.4, 37.8),
        )
        rule.setContent {
            val vm = SelectedFeatureViewModel(app)
            vm.set(restaurant)
            DynamicTheme {
                PlaceSheet(viewModel = vm, feature = restaurant)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Menu", substring = true).assertExists()
    }

    @Test fun sheetsWithSearchActionsShowSearchSheet() {
        rule.setContent {
            val scope = compactScope()
            DynamicTheme {
                with(scope) {
                    MapSheets(
                        searchActions = SearchActions.Noop,
                        searchState = SearchUiState(query = "coffee"),
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("coffee", substring = true).assertExists()
    }
}
