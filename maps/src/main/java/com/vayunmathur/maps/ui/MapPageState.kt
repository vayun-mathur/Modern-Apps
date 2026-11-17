package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.domain.RouteService
import com.vayunmathur.maps.intents.FamilyMember
import com.vayunmathur.maps.intents.rememberFamilyMembers
import com.vayunmathur.maps.platform.DeparturesState
import com.vayunmathur.maps.platform.MapSettingsViewModel
import com.vayunmathur.maps.platform.NavigationSessionManager
import com.vayunmathur.maps.platform.MapsSearchViewModel
import com.vayunmathur.maps.platform.ParkingViewModel
import com.vayunmathur.maps.platform.SavedPlacesViewModel
import com.vayunmathur.maps.platform.SearchResult
import com.vayunmathur.maps.platform.SelectedFeatureViewModel
import com.vayunmathur.maps.platform.TripItineraryState
import com.vayunmathur.maps.platform.TransitStopsViewModel

/** Collected screen state for [MapPage]: every ViewModel flow the map chrome reads, gathered in one place so the page itself stays wiring. */
internal class MapPageState(
    val selectedFeature: SpecificFeature?,
    val inactiveNavigation: SpecificFeature.Route?,
    val selectedRouteType: RouteService.TravelMode,
    val route: Map<RouteService.TravelMode, RouteService.RouteType?>?,
    val alternatives: List<RouteService.Route>,
    val poiSection: PoiSection,
    val currentPoiInfo: GooglePoiInfo?,
    val userPosition: GeoPoint,
    val userBearing: Float?,
    val userHeadingAccuracy: Int,
    val userSpeedMps: Float,
    val savedPins: List<SavedPlace>,
    val parkingSpot: ParkingSpot?,
    val searchResults: List<SearchResult>,
    val searchQuery: String,
    val searchRecents: List<String>,
    val searching: Boolean,
    val selectedTransitStop: TransitStop?,
    val departuresState: DeparturesState,
    val tripItineraryState: TripItineraryState,
    val familyMembers: List<FamilyMember>,
    val trafficEnabled: Boolean,
    val satelliteEnabled: Boolean,
    val safetyEnabled: Boolean,
    val transitEnabled: Boolean,
    val globeEnabled: Boolean,
    val googleServices: Boolean,
)

@Composable
internal fun rememberMapPageState(
    sheetStack: NavBackStack<SheetRoute>,
    sheetTop: SheetRoute,
    viewModel: SelectedFeatureViewModel,
    savedPlacesViewModel: SavedPlacesViewModel,
    searchViewModel: MapsSearchViewModel,
    settingsViewModel: MapSettingsViewModel,
    parkingViewModel: ParkingViewModel,
    transitViewModel: TransitStopsViewModel,
    selectedAlternative: RouteService.Route?,
): MapPageState {
    // What is showing is the top of the sheet stack; the VM only mirrors
    // the engine route (place to enrich / waypoints to plan) from it.
    val selectedFeature = sheetStack.backStack.effectiveFeature()
    val parkedRoutePlan = sheetStack.backStack.parkedRoutePlan()
    val inactiveNavigation = parkedRoutePlan?.let { SpecificFeature.Route(it.waypoints) }
    LaunchedEffect(sheetStack.backStack.toList()) {
        viewModel.setSheetRoute(sheetStack.backStack.engineRoute())
    }
    // The visible route tab is the RoutePlan's mode: switching it writes
    // the route back (setLast), and routes plans that mode first.
    val activeRoutePlan = sheetStack.backStack.routePlan()
    val selectedRouteType = activeRoutePlan?.mode ?: RouteService.TravelMode.DRIVE
    val routesByMode by viewModel.routes.collectAsState(null)
    val alternatives by viewModel.alternatives.collectAsState()
    val route = remember(routesByMode, selectedAlternative, activeRoutePlan) {
        val base = routesByMode ?: return@remember null
        val alt = selectedAlternative ?: return@remember base
        val mode = activeRoutePlan?.mode ?: return@remember base
        base.toMutableMap().apply { this[mode] = alt }
    }
    // Collected here (rather than inside the sheet) so the inner place sheet's
    // contentKey can name them: the tab switch and the late-arriving reviews
    // each change the sheet's content height, and without them in the key the
    // learned ceiling from Details traps the taller Reviews list.
    val poiSection = (sheetTop as? SheetRoute.Place)?.section ?: PoiSection.DETAILS
    val currentPoiInfo by viewModel.currentPoiInfo.collectAsState()
    val userPosition by viewModel.userPosition.collectAsState()
    val userBearing by viewModel.userBearing.collectAsState()
    val userHeadingAccuracy by viewModel.userHeadingAccuracy.collectAsState()
    val userSpeedMps by viewModel.userSpeedMps.collectAsState()

    val savedList by savedPlacesViewModel.saved.collectAsState()
    // The starred list drawn as one pin set, deduped.
    val savedPins = remember(savedList) { savedList.distinct() }

    val parkingSpot by parkingViewModel.active.collectAsState()
    val searchResults by searchViewModel.results.collectAsState()
    val searchQuery by searchViewModel.query.collectAsState()
    val searchRecents by searchViewModel.recents.collectAsState()
    val searching by searchViewModel.searching.collectAsState()
    val selectedTransitStop by transitViewModel.selected.collectAsState()
    val departuresState by transitViewModel.departures.collectAsState()
    val tripItineraryState by transitViewModel.tripItinerary.collectAsState()

    // The findfamily service is bound only while this screen is composed, so this is empty when
    // findfamily is absent.
    val familyMembers by rememberFamilyMembers()

    val trafficEnabled by settingsViewModel.trafficLayer.collectAsState()
    val satelliteEnabled by settingsViewModel.satelliteLayer.collectAsState()
    val safetyEnabled by settingsViewModel.safetyLayer.collectAsState()
    val transitEnabled by settingsViewModel.transitLayer.collectAsState()
    val globeEnabled by settingsViewModel.globeEnabled.collectAsState()
    val googleServices by settingsViewModel.googleServices.collectAsState()
    return MapPageState(
        selectedFeature = selectedFeature,
        inactiveNavigation = inactiveNavigation,
        selectedRouteType = selectedRouteType,
        route = route,
        alternatives = alternatives,
        poiSection = poiSection,
        currentPoiInfo = currentPoiInfo,
        userPosition = userPosition,
        userBearing = userBearing,
        userHeadingAccuracy = userHeadingAccuracy,
        userSpeedMps = userSpeedMps,
        savedPins = savedPins,
        parkingSpot = parkingSpot,
        searchResults = searchResults,
        searchQuery = searchQuery,
        searchRecents = searchRecents,
        searching = searching,
        selectedTransitStop = selectedTransitStop,
        departuresState = departuresState,
        tripItineraryState = tripItineraryState,
        familyMembers = familyMembers,
        trafficEnabled = trafficEnabled,
        satelliteEnabled = satelliteEnabled,
        safetyEnabled = safetyEnabled,
        transitEnabled = transitEnabled,
        globeEnabled = globeEnabled,
        googleServices = googleServices,
    )
}

/** End the session and stop the foreground service that outlives this screen. */
internal fun stopNavigation(context: android.content.Context) {
    NavigationSessionManager.stop()
    context.stopService(
        android.content.Intent(context, com.vayunmathur.maps.service.NavigationService::class.java)
    )
}
