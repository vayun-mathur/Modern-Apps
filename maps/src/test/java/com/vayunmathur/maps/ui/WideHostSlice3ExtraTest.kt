package com.vayunmathur.maps.ui

import android.app.Application
import androidx.compose.runtime.rememberCoroutineScope
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.maps.ui.map.MapChromeState
import com.vayunmathur.maps.ui.map.MapOverlay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapBody
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.util.DeparturesState
import com.vayunmathur.maps.util.MapSettingsViewModel
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.ParkingViewModel
import com.vayunmathur.maps.util.PlacePanelState
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SearchUiState
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TripItineraryState
import com.vayunmathur.maps.util.TransitStopsViewModel
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Slice 3/3 extra: MapWideHost/MapSidePanel through a fully-constructed
 * MapPageScope. The map surface itself is a native Vulkan view; under
 * Robolectric it composes without presenting frames, which is enough for
 * the host/panel branch logic that is this slice's subject.
 */
// Robolectric fixtures build real ViewModels inside setContent on purpose: these tests drive
// the composables against live VM state, with no ViewModelStoreOwner to obtain them from.
@Suppress("ViewModelConstructorInComposable")
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WideHostSlice3ExtraTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** Builds a scope inside composition (Messenger is module-internal to library.ui). */
    @androidx.compose.runtime.Composable
    private fun buildScope(
        selectedFeature: com.vayunmathur.maps.data.SpecificFeature? = null,
        body: MapBody = MapBody.Earth,
        navState: NavigationSessionManager.NavState = NavigationSessionManager.NavState.Idle,
        isNavigating: Boolean = false,
        googleServices: Boolean = false,
        layers: Boolean = false,
        searchBarVisible: Boolean = true,
        navProgress: com.vayunmathur.maps.util.NavigationProgress? = null,
        parkingSpot: com.vayunmathur.maps.data.ParkingSpot? = null,
        savedPins: List<com.vayunmathur.maps.data.SavedPlace> = emptyList(),
        route: com.vayunmathur.maps.data.SpecificFeature.Route? = null,
        inactiveNavigation: com.vayunmathur.maps.data.SpecificFeature.Route? = null,
    ): MapPageScope {
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
            chrome = remember {
                MapChromeState(
                    mutableStateOf(RouteService.TravelMode.DRIVE),
                    mutableStateOf(null),
                    mutableStateOf(MapOverlay.None),
                    mutableStateOf(true),
                    mutableStateOf(false),
                    mutableStateOf(null),
                    mutableStateOf(0L),
                )
            },
            camera = CameraState(),
            selectedFeature = selectedFeature ?: route,
            inactiveNavigation = inactiveNavigation,
            route = null,
            poiSection = PoiSection.DETAILS,
            poiEnrichmentKey = PoiEnrichmentKey(0, hasHours = false, hasPopularTimes = false),
            userPosition = GeoPoint(-122.4, 37.8),
            userBearing = null,
            userHeadingAccuracy = 3,
            userSpeedMps = 0f,
            savedPins = savedPins,
            parkingSpot = parkingSpot,
            searchResults = emptyList(),
            searchRecents = emptyList(),
            selectedTransitStop = null,
            departuresState = DeparturesState.Idle,
            tripItineraryState = TripItineraryState.Idle,
            familyMembers = emptyList(),
            trafficEnabled = layers, satelliteEnabled = layers, safetyEnabled = layers, transitEnabled = layers,
            globeEnabled = layers, googleServices = googleServices,
            body = body, moonTextures = null, darkMap = false,
            navState = navState,
            navSession = NavigationSessionManager.NavSession(),
            navProgress = navProgress, isNavigating = isNavigating, browsing = true,
            searchBarVisible = searchBarVisible, searchBarLiftPx = 0, onSearchBarLift = {},
            sheetState = null, searchSheetState = null, searchRequest = null,
            openSearch = { _, _ -> }, settingsAction = emptyList(), wide = true,
        )
    }

    @Test fun wideHostMapOnlyWhenNoPanel() {
        rule.setContent {
            val scope = buildScope()
            DynamicTheme {
                with(scope) {
                    MapWideHost(searchActions = null, searchState = SearchUiState(), panelSearch = false, panelPlace = false)
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun wideHostPlacePanel() {
        val place = com.vayunmathur.maps.data.SpecificFeature.GenericPlace(
            "Blue Bottle", null, null, null, GeoPoint(-122.4, 37.8),
        )
        rule.setContent {
            val scope = buildScope(place)
            scope.viewModel.set(place)
            DynamicTheme {
                with(scope) {
                    MapWideHost(searchActions = null, searchState = SearchUiState(), panelSearch = false, panelPlace = true)
                }
            }
        }
        rule.waitForIdle()
        // The side panel must actually show the selected place, not just compose.
        rule.onNodeWithText("Blue Bottle", substring = true).assertIsDisplayed()
    }

    @Test fun sidePanelDirectStates() {
        rule.setContent {
            DynamicTheme {
                MapSidePanel(
                    searchOpen = true,
                    searchState = SearchUiState(query = "coffee"),
                    searchActions = com.vayunmathur.maps.util.SearchActions.Noop,
                    panelState = PlacePanelState(),
                    panelActions = com.vayunmathur.maps.util.PlacePanelActions.Noop,
                    selectedFeature = null,
                    onSelectFeature = {},
                    route = null,
                    selectedRouteType = RouteService.TravelMode.DRIVE,
                    onSelectedRouteType = {},
                    inactiveNavigation = null,
                    navState = NavigationSessionManager.NavState.Idle,
                    onClosePanel = {},
                )
            }
        }
        rule.waitForIdle()
        // The open search panel must show the typed query.
        rule.onNodeWithText("coffee", substring = true).assertIsDisplayed()
    }

    @Test fun contentBoxMoonAndLayersVariant() {
        rule.setContent {
            val moon = buildScope(body = MapBody.Moon, layers = true, googleServices = true, searchBarVisible = false)
            DynamicTheme {
                moon.MapContentBox()
            }
        }
        rule.waitForIdle()
    }

    @Test fun contentBoxRouteVariant() {
        val from = com.vayunmathur.maps.data.SpecificFeature.GenericPlace("From", null, null, null, GeoPoint(-122.4, 37.8))
        val to = com.vayunmathur.maps.data.SpecificFeature.GenericPlace("To", null, null, null, GeoPoint(-122.3, 37.9))
        rule.setContent {
            val routeScope = buildScope(selectedFeature = com.vayunmathur.maps.data.SpecificFeature.Route(listOf(from, to)))
            DynamicTheme {
                routeScope.MapContentBox()
            }
        }
        rule.waitForIdle()
    }

    @Test fun contentBoxNavigatingWithPinsAndParking() {
        val progress = com.vayunmathur.maps.util.NavigationProgress(
            snappedPosition = GeoPoint(-122.4, 37.8), segmentIndex = 0, currentStepIndex = 0,
            distanceAlongRoute = 10.0, distanceRemaining = 500.0, distanceToNextManeuver = 120.0,
            fractionComplete = 0.2, etaEpochMs = System.currentTimeMillis() + 300_000,
            courseOverGround = 45f, distanceOffRoute = 0.0, speedMps = 6f,
        )
        rule.setContent {
            val scope = buildScope(
                isNavigating = true,
                navState = NavigationSessionManager.NavState.Navigating(progress),
                navProgress = progress,
                parkingSpot = com.vayunmathur.maps.data.ParkingSpot(37.8, -122.4, 0L, "gate B"),
                savedPins = listOf(com.vayunmathur.maps.data.SavedPlace("Home", 37.8, -122.4)),
            )
            DynamicTheme {
                scope.MapContentBox()
            }
        }
        rule.waitForIdle()
    }
}
