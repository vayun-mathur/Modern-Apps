package com.vayunmathur.maps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.ui.CompassCalibrationHint
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FreeHeightBottomSheetScaffold
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.FreeHeightSheetState
import com.vayunmathur.library.ui.Messenger
import com.vayunmathur.library.ui.OverlayAction
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.TopAppBarOverlay
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.ui.rememberFreeHeightSheetState
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.map.GLOBE_DETAIL_ZOOM
import com.vayunmathur.library.map.MapBody
import com.vayunmathur.library.map.MoonTextures
import com.vayunmathur.maps.ui.map.rememberInitialCamera
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.SheetRoute
import com.vayunmathur.maps.popSheet
import com.vayunmathur.maps.effectiveFeature
import com.vayunmathur.maps.engineRoute
import com.vayunmathur.maps.parkedRoutePlan
import com.vayunmathur.maps.routePlan
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.parse
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.ipc.FamilyMember
import com.vayunmathur.maps.ui.map.LayerToggles
import com.vayunmathur.maps.ui.map.MapFabStack
import com.vayunmathur.maps.ui.map.MapOverlays
import com.vayunmathur.maps.ui.map.MapSearchBar
import com.vayunmathur.maps.ui.map.MapSurface
import com.vayunmathur.maps.ui.map.NavigationCameraFollow
import com.vayunmathur.maps.ui.map.WaypointList
import com.vayunmathur.maps.ui.map.rememberMapChromeState
import com.vayunmathur.maps.ui.streetview.StreetViewPegman
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.ui.map.MapChromeState
import com.vayunmathur.maps.util.DeparturesState
import com.vayunmathur.maps.util.PoiMapViewModel
import com.vayunmathur.maps.util.MapTileCache
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.MapSettingsViewModel
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.MoonAssetLoader
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import com.vayunmathur.maps.util.PoiIndex
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchUiState
import com.vayunmathur.maps.util.visibleBoundsOrWorld
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlinx.coroutines.launch
import com.vayunmathur.maps.R as MapsR


/**
 * The map screen.
 *
 * This composable is the wiring: it collects the ViewModel state, decides what the chrome should
 * show, and hands each piece to a stateless component in [com.vayunmathur.maps.ui.map]. The
 * pieces themselves — the search bar, the FAB stack, the sheets, the hit-test, the camera
 * follow, the style patch — each live in their own file and none of them know about this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPage(
    backStack: NavBackStack<Route>,
    viewModel: SelectedFeatureViewModel,
    savedPlacesViewModel: SavedPlacesViewModel,
    searchViewModel: MapsSearchViewModel,
    settingsViewModel: MapSettingsViewModel,
    parkingViewModel: com.vayunmathur.maps.util.ParkingViewModel,
    transitViewModel: com.vayunmathur.maps.util.TransitStopsViewModel,
) {
    // Obtained in-body (not a parameter) so the MainActivity call site stays stable: the offline
    // POI overlay pins are viewport-driven state owned by this screen alone.
    val poiViewModel: PoiMapViewModel = viewModel()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val messenger = rememberMessenger()
    val noResultsMessage = stringResource(MapsR.string.no_results_found)

    val chrome = rememberMapChromeState()
    val camera = rememberInitialCamera(viewModel)
    val sheetStack = rememberNavBackStack<SheetRoute>(SheetRoute.Browse)
    val sheetTop = sheetStack.backStack.last()
    // Dialog destinations drive the transit engine: the board/trip flows
    // re-key off the top route, not off a VM-held selection.
    LaunchedEffect(sheetTop) {
        when (val top = sheetTop) {
            is SheetRoute.Departures -> transitViewModel.openStop(
                com.vayunmathur.maps.data.transit.TransitStop(top.stopId, top.name, top.lat, top.lon),
            )
            is SheetRoute.Trip -> transitViewModel.openTrip(top.vehicleId, top.lat, top.lon)
            else -> {
                transitViewModel.closeTrip()
                if (sheetStack.backStack.none { it is SheetRoute.Departures }) transitViewModel.closeStop()
            }
        }
    }
    fun pushDepartures(lat: Double, lon: Double, name: String? = null) {
        coroutineScope.launch {
            val stop = transitViewModel.resolveNearestStop(lat, lon, name)
            sheetStack.add(SheetRoute.Departures(stop.id, stop.name, stop.lat, stop.lon))
        }
    }

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
    val selectedRouteType = activeRoutePlan?.mode ?: chrome.selectedRouteType
    val route by viewModel.routes.collectAsState(null)
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
    val familyMembers by com.vayunmathur.maps.ipc.rememberFamilyMembers()

    val trafficEnabled by settingsViewModel.trafficLayer.collectAsState()
    val satelliteEnabled by settingsViewModel.satelliteLayer.collectAsState()
    val safetyEnabled by settingsViewModel.safetyLayer.collectAsState()
    val transitEnabled by settingsViewModel.transitLayer.collectAsState()
    val globeEnabled by settingsViewModel.globeEnabled.collectAsState()
    val googleServices by settingsViewModel.googleServices.collectAsState()
    var googleConsentDone by remember {
        mutableStateOf(
            !shouldShowGoogleConsent(
                com.vayunmathur.library.util.DataStoreUtils.getInstance(context)
            )
        )
    }
    // Enabling the globe at street zoom shows nothing new: the sphere only
    // reads past the detail threshold. Zoom out to reveal it, so the toggle
    // answers visibly instead of arming a mode the user cannot see.
    LaunchedEffect(globeEnabled) {
        if (globeEnabled && camera.position.zoom >= GLOBE_DETAIL_ZOOM) {
            camera.animateTo(camera.position.copy(zoom = GLOBE_REVEAL_ZOOM))
        }
    }

    // The Moon raster pair, loaded on Moon select (never for Earth sessions) and dropped
    // again when the body goes back to Earth, so ~37MB of heap and ~40MB of GPU images are
    // only held while the Moon is actually shown.
    var moonTextures by remember { mutableStateOf<MoonTextures?>(null) }
    LaunchedEffect(chrome.body) {
        moonTextures = if (chrome.body == MapBody.Moon) MoonAssetLoader.load(context) else null
    }

    // Resolve the P6 map-theme setting against the OS the same way DynamicTheme does, so the map
    // flips light/dark together with the rest of the chrome.
    val themeMode by settingsViewModel.themeMode.collectAsState()
    val darkMap = themeMode.darkOverride ?: isSystemInDarkTheme()

    val navState by NavigationSessionManager.state.collectAsState()
    // Collected, not read off a field: a recalculation swaps the route mid-session, and reading
    // it as a plain property meant this screen kept drawing the OLD route's steps against the
    // new progress until something else happened to recompose.
    val navSession by NavigationSessionManager.session.collectAsState()
    val isNavigating = navState !is NavigationSessionManager.NavState.Idle
    val navProgress = (navState as? NavigationSessionManager.NavState.Navigating)?.progress

    // The side panel takes this content in wide mode, so the sheets stay
    // hidden there and the effects below only drive them in compact. A suspend
    // on an unmeasured scaffold would hang, not no-op, so every effect that
    // touches one is keyed on `wide` and returns early when it is true.
    val wide = isExpandedWidth()
    val sheetState = rememberFreeHeightSheetState(SheetValue.Hidden)
    // A second, independent sheet stacked over the first, rather than a mode of it. The two have
    // different peek heights and different lifetimes, and driving one state through both would
    // mean re-measuring the sheet at the moment its content swaps — which is exactly when the
    // learned content ceiling and the peek floor disagree.
    val searchSheetState = rememberFreeHeightSheetState(SheetValue.Hidden)
    // Null in wide mode — the side panel takes the sheets' content, so nothing
    // may suspend on them (an unmeasured scaffold's hide()/partialExpand hangs
    // rather than no-ops). The effects below and MapContentBox read these, and
    // the compact scaffolds below take the non-null originals directly.
    val wideSheetState: FreeHeightSheetState? =
        if (wide) null else sheetState
    val wideSearchSheetState: FreeHeightSheetState? =
        if (wide) null else searchSheetState
    // How much of the bottom the collapsed search bar is occupying, so the FAB stack and the
    // scale bar can clear it the same way they clear a sheet. Zero whenever the bar is not drawn.
    var searchBarLiftPx by remember { mutableIntStateOf(0) }
    val searchRoute = sheetTop as? SheetRoute.Search
    val searchState = SearchUiState(
        query = searchQuery,
        results = searchResults,
        recents = searchRecents,
        searching = searching,
    )
    val browsing = selectedFeature == null && !isNavigating
    // The bar is chrome, not a sheet, so it is composed away rather than animated out. Its
    // measured height therefore has to be discounted explicitly — a stale one would leave the
    // FAB stack floating above a bar that is no longer there.
    val searchBarVisible = browsing && searchRoute == null
    // Expanded widths show this content in the side panel instead of a sheet.
    val panelSearch = wide && searchRoute != null
    val panelPlace = wide && searchRoute == null && selectedFeature != null
    fun openSearch(query: String? = null, waypointIndex: Int? = null) {
        val near = com.vayunmathur.maps.util.searchBiasPoint(camera.visibleBoundsOrWorld(), userPosition)
        if (!query.isNullOrBlank()) searchViewModel.setQuery(query, near.latitude, near.longitude)
        sheetStack.add(SheetRoute.Search(waypointIndex, near.latitude, near.longitude))
    }
    val settingsAction = rememberSettingsAction(backStack)
    MapSheetEffects(
        wide = wide,
        viewModel = viewModel,
        camera = camera,
        selectedFeature = selectedFeature,
        inactiveNavigation = inactiveNavigation,
        isNavigating = isNavigating,
        sheetState = wideSheetState,
        searchSheetState = wideSearchSheetState,
        sheetStack = sheetStack,
        searchRoute = searchRoute,
    )
    NavigationCameraFollow(camera, chrome, navProgress, isNavigating)

    val searchActions = rememberSearchActions(
        searchRequest = searchRoute,
        searchViewModel = searchViewModel,
        viewModel = viewModel,
        sheetStack = sheetStack,
        coroutineScope = coroutineScope,
        onCloseSearch = { sheetStack.popSheet() },
    )

    val scope = MapPageScope(
        context = context,
        backStack = backStack,
        viewModel = viewModel,
        savedPlacesViewModel = savedPlacesViewModel,
        searchViewModel = searchViewModel,
        settingsViewModel = settingsViewModel,
        parkingViewModel = parkingViewModel,
        transitViewModel = transitViewModel,
        coroutineScope = coroutineScope,
        messenger = messenger,
        noResultsMessage = noResultsMessage,
        chrome = chrome,
        camera = camera,
        selectedFeature = selectedFeature,
        inactiveNavigation = inactiveNavigation,
        route = route,
        poiSection = poiSection,
        onPoiSection = { section ->
            (sheetTop as? SheetRoute.Place)?.let { sheetStack.setLast(it.copy(section = section)) }
        },
        onRouteMode = { mode ->
            val plan = sheetStack.backStack.routePlan()
            if (plan != null && sheetTop is SheetRoute.RoutePlan) sheetStack.setLast(plan.copy(mode = mode))
            else chrome.selectedRouteType = mode
        },
        selectedRouteType = selectedRouteType,
        poiEnrichmentKey = PoiEnrichmentKey.of(currentPoiInfo),
        userPosition = userPosition,
        userBearing = userBearing,
        userHeadingAccuracy = userHeadingAccuracy,
        userSpeedMps = userSpeedMps,
        savedPins = savedPins,
        parkingSpot = parkingSpot,
        searchResults = searchResults,
        searchRecents = searchRecents,
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
        body = chrome.body,
        moonTextures = moonTextures,
        darkMap = darkMap,
        navState = navState,
        navSession = navSession,
        navProgress = navProgress,
        isNavigating = isNavigating,
        browsing = browsing,
        searchBarVisible = searchBarVisible,
        searchBarLiftPx = searchBarLiftPx,
        onSearchBarLift = { searchBarLiftPx = it },
        sheetState = if (wide) null else sheetState,
        searchSheetState = if (wide) null else searchSheetState,
        searchRoute = searchRoute,
        openSearch = ::openSearch,
        settingsAction = settingsAction,
        wide = wide,
        sheetStack = sheetStack,
        pushDepartures = ::pushDepartures,
    )
    if (!googleConsentDone) {
        GoogleConsentDialog(settingsViewModel) { googleConsentDone = true }
    }
    if (wide) {
        scope.MapWideHost(
            searchActions = searchActions,
            searchState = searchState,
            panelSearch = panelSearch,
            panelPlace = panelPlace,
        )
        return
    }
    scope.MapSheets(
        searchActions = searchActions,
        searchState = searchState,
    )
}

/** End the session and stop the foreground service that outlives this screen. */
internal fun stopNavigation(context: android.content.Context) {
    NavigationSessionManager.stop()
    context.stopService(
        android.content.Intent(context, com.vayunmathur.maps.util.NavigationService::class.java)
    )
}

/**
 * Zoom the globe toggle reveals at: comfortably past the detail threshold so
 * the sphere reads as a sphere, close enough that the home continent is still
 * recognisable rather than a marble.
 */
private const val GLOBE_REVEAL_ZOOM = 3.0
