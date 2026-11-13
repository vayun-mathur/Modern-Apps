package com.vayunmathur.maps.ui.map

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpRect
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.LayerOptions
import com.vayunmathur.library.map.MapBody
import com.vayunmathur.library.map.MapOptions
import com.vayunmathur.library.map.MoonTextures
import com.vayunmathur.library.map.TileSource
import com.vayunmathur.library.map.TrafficColorTable
import com.vayunmathur.library.map.UserPuck
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.ui.FreeHeightSheetState
import com.vayunmathur.library.util.ConnectivityMonitor
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.osmPlace
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.ipc.FamilyMember
import com.vayunmathur.maps.ui.map.MapFeaturePicker.Companion.NATIVE_LABEL_LAYER_IDS
import com.vayunmathur.maps.ui.map.MapFeaturePicker.Companion.toFeature1
import com.vayunmathur.maps.ui.theme.mapTokens
import com.vayunmathur.maps.util.DeparturesState
import com.vayunmathur.maps.util.MapsSearchViewModel
import com.vayunmathur.maps.util.NavigationProgress
import com.vayunmathur.maps.util.OfflineRouterTraffic
import com.vayunmathur.maps.util.PoiCategories
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.TransitStopsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The map surface: the renderer, its overlay layers, and what a tap on it means.
 *
 * Renders with library:map's [VectorMap] (Vulkan basemap, phone-side only). The overlay
 * pins and route are plain Compose in [MapLayers], hit-tested in-memory - the renderer
 * has no vector-layer API for them. The user puck is the exception: it has no hit-testing,
 * so it moved into the renderer as a [UserPuck] and no longer drags behind a pan.
 * Tile-baked place labels resolve through the native pick
 * ([queryRenderedLabels][com.vayunmathur.library.map.Projection.queryRenderedLabels]).
 *
 * The transit toggle is the one layer the renderer draws itself, via [LayerOptions]; see
 * [pinFeatures] for what that means for hit-testing.
 *
 * [sheetState] is null on expanded widths, where the side panel reads the selection
 * directly and there is no sheet to raise — so every tap handler skips it then.
 */
@Composable
fun MapSurface(
    camera: CameraState,
    chrome: MapChromeState,
    viewModel: SelectedFeatureViewModel,
    searchViewModel: MapsSearchViewModel,
    transitViewModel: TransitStopsViewModel,
    sheetState: FreeHeightSheetState?,
    selectedFeature: SpecificFeature?,
    route: RouteService.RouteType?,
    userPosition: GeoPoint,
    userBearing: Float?,
    navProgress: NavigationProgress?,
    searchResults: List<SearchResult>,
    savedPlaces: List<SavedPlace>,
    parkingSpot: ParkingSpot?,
    familyMembers: List<FamilyMember>,
    trafficEnabled: Boolean,
    satelliteEnabled: Boolean,
    safetyEnabled: Boolean,
    transitEnabled: Boolean,
    globeEnabled: Boolean,
    body: MapBody,
    moonTextures: MoonTextures?,
    selectedTransitStop: TransitStop?,
    darkBasemap: Boolean,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()

    // The renderer has carried a transit layer all along; :maps simply never asked for it,
    // so flipping the layers switch drew nothing but Compose stops. Remembered because
    // VulkanMapSurface keys a LaunchedEffect on this by equality and a fresh instance every
    // recomposition would churn it.
    val selectedCategory = chrome.selectedCategory
    val mapOptions = remember(transitEnabled, selectedCategory, trafficEnabled, safetyEnabled) {
        MapOptions(
            layerOptions = LayerOptions(
                poi = true,
                poiKinds = selectedCategory?.kinds.orEmpty(),
                transit = transitEnabled,
                // Gates the baked traffic layer's geometry (renderer layer 10). The colours
                // ride in separately via [trafficColors] below.
                traffic = trafficEnabled,
                safety = safetyEnabled,
            ),
            // maps is the one app that pushes a `.mamaps` archive to external files, so it
            // reads tiles from the file; every other app streams from the server (the default).
            tileSource = TileSource.LocalFile,
        )
    }

    // The live per-component colours: resolved from the current palette on the device (the
    // renderer only looks them up), pushed to the renderer as an id->ARGB table. Null when the
    // toggle is off or there is nothing to draw, which clears the overlay on the next frame —
    // the accumulated data stays cached in OfflineRouter so re-enabling is instant.
    val tokens = remember(darkBasemap) { mapTokens(darkBasemap) }
    // Built off the main thread: the table spans every component fetched this session (one
    // entry per road piece in every 1-degree square visited), and it is rebuilt whenever a
    // square lands, which is while the user is panning. The previous table keeps drawing until
    // the new one is ready.
    val components by OfflineRouterTraffic.trafficComponents.collectAsState()
    val trafficColors by produceState<TrafficColorTable?>(null, components, tokens, trafficEnabled) {
        value = if (!trafficEnabled) null else withContext(Dispatchers.Default) {
            buildTrafficColorTable(components, tokens)
        }
    }

    // The route, drawn inside the renderer's frame so it pans in lock-step with the basemap
    // (this is what retired the Compose `RouteLayer`). Recomputed on the same cadence that
    // layer used: on a route or palette change, and on the navigation bucket it keyed on
    // (`segmentIndex` + `distanceAlongRoute` quantised to 5 m), since the mid-step travelled
    // split moves the geometry. Only built when a route is actually selected.
    val routeOverlay = remember(
        selectedFeature, route, tokens,
        navProgress?.segmentIndex,
        navProgress?.distanceAlongRoute?.let { (it / 5.0).toInt() },
    ) {
        if (selectedFeature is SpecificFeature.Route && route is RouteService.Route) {
            buildRouteOverlay(route, navProgress, tokens)
        } else {
            null
        }
    }

    // The library takes a nullable GeoPoint, so :maps' two sentinels are converted here
    // and go no further: GeoPoint(0, 0) is this app's "no fix" and means null, and a null
    // bearing means no heading yet rather than due north.
    val userPuck = remember(userPosition, userBearing) {
        if (userPosition.latitude == 0.0 && userPosition.longitude == 0.0) {
            null
        } else {
            UserPuck(userPosition, userBearing)
        }
    }

    // The app's pins, moved into the renderer so they pan and tilt glued to the basemap instead of
    // trailing it a frame the way the Compose pin overlays did. Each pin gets a stable id and a
    // parallel map from that id to what a tap on it means, so a tap resolves through the renderer's
    // id buffer ([Projection.pickMarker]) rather than a CPU hit-test. Rebuilt only when the pin
    // inputs change, so a pan does not churn it. See [buildMarkers].
    val (markers, markerHits) = remember(searchResults, savedPlaces, parkingSpot, familyMembers) {
        buildMarkers(searchResults, savedPlaces, parkingSpot, familyMembers)
    }

    // The pack trip ids behind the selected stop's departures board: the join key into
    // `activeVehicles` (each offline departure's `tripVehicleId` is bit-identical to the matching
    // `Vehicle.id`), so only the selected station's own serving trips draw as sprites. Loading/Idle
    // (or online-only entries with no trip id) yields the empty set — strict-hide, per spec.
    val departuresState by transitViewModel.departures.collectAsState()
    val servingTripIds = remember(departuresState) {
        (departuresState as? DeparturesState.Loaded)?.departures.orEmpty()
            .mapNotNull { it.tripVehicleId }.toSet()
    }

    // Simulated in-service transit vehicles serving the selected stop, recomputed at ~1 Hz and drawn
    // by the renderer as billboarded sprites on their own overlay, apart from the pins above. Gated
    // on the transit toggle, the selected stop, and the lifecycle, and empty below its zoom gate, so
    // it costs nothing when transit is off, no station is selected, the map is hidden, or the
    // viewport is too wide to enumerate cheaply.
    val vehicles = rememberTransitVehicles(camera, transitEnabled, selectedTransitStop, servingTripIds)

    // Pack-driven lines for the selected stop, drawn in the separate rail
    // slot under the navigation route. Null (nothing drawn) until a stop is
    // selected — always-on lines are visual noise.
    val railLines = rememberRailLines(camera, transitEnabled, selectedTransitStop, tokens)

    TrafficPrefetchEffect(camera, trafficEnabled)

    VectorMap(
        cameraState = camera,
        modifier = modifier,
        darkBasemap = darkBasemap,
        globeEnabled = globeEnabled,
        body = body,
        moonTextures = moonTextures,
        options = mapOptions,
        // Live online/offline into the renderer's tile policy (injected so
        // :library:map keeps no :library:network edge, #884).
        online = ConnectivityMonitor.isOnline,
        userPuck = userPuck,
        regionMask = regionMaskFor(selectedFeature),
        // The live traffic overlay: baked geometry gated by [LayerOptions.traffic] above,
        // coloured by this id->ARGB table. Null clears it (toggle off / nothing to draw).
        trafficColors = trafficColors,
        // The route line, coloured per segment (traffic/transit/travelled) and drawn inside
        // the renderer's frame so it stays glued to the basemap on a pan. Null draws nothing.
        route = routeOverlay,
        // The pack-driven rail network, drawn under the route in its own slot.
        railLines = railLines,
        // The app's pins, drawn by the renderer as billboarded sprites so they pan/tilt in
        // lock-step with the basemap. Their taps resolve through the id buffer below.
        markers = markers,
        // Simulated transit vehicles, on their own ~1 Hz overlay under the pins. Not tap targets.
        vehicles = vehicles,
        onMapClickWithScreen = { click ->
            coroutineScope.launch {
                val projection = camera.projection ?: return@launch
                val offset = click.screen
                // Renderer-drawn pins resolve through the GPU id buffer first: correct under tilt,
                // and never a frame behind the basemap the way the old Compose hit-test was. On a
                // miss fall back to the CPU hit-test over the same features.
                val hit = markerHits[projection.pickMarker(offset.x.value, offset.y.value)]
                    ?: pickPinOnCpu(
                        offset, projection,
                        searchResults, savedPlaces, parkingSpot, familyMembers,
                        transitEnabled,
                    )

                when (hit) {
                    MapHit.Parking -> {
                        chrome.show(MapOverlay.Parking)
                        return@launch
                    }
                    is MapHit.Stop -> {
                        // Whatever place sheet was up is replaced, not stacked under: one tap
                        // should take one back to undo, and a board over a stale sheet takes two.
                        viewModel.set(null)
                        transitViewModel.openStop(hit.stop)
                        return@launch
                    }
                    is MapHit.Place -> {
                        // A tapped station POI carries no stop id: resolve the nearest
                        // baked stop and open its board instead of selecting the POI.
                        val station = hit.feature as? SpecificFeature.GenericPlace
                        if (station?.poiType == STATION_POI_TYPE) {
                            viewModel.set(null)
                            transitViewModel.openNearestStop(
                                station.position.latitude,
                                station.position.longitude,
                            )
                            return@launch
                        }
                        viewModel.stashRouteSelection()
                        viewModel.set(hit.feature)
                        // Compact only: in wide the side panel reads the selection
                        // directly, and a null state means the sheets never open.
                        sheetState?.partialExpand()
                        return@launch
                    }
                    null -> Unit
                }

                // A POI the renderer actually drew, from the same collision pass that put
                // it on screen. Ranked below the app's own pins deliberately: a saved place
                // or a search result the user put there outranks ambient map furniture
                // underneath it, which is the order the old probe chain had too.
                val poi = click.poi
                if (poi != null) {
                    val type = PoiCategories.typeOfKind(poi.kind)
                    // A transit stop carries no stop id, so its tap opens the nearest baked
                    // stop's departure board rather than a place sheet.
                    if (PoiCategories.opensDepartureBoard(poi.kind)) {
                        viewModel.set(null)
                        transitViewModel.openNearestStop(
                            poi.position.latitude,
                            poi.position.longitude,
                            // The pack names a stop by its MOTIS id, which is a machine string.
                            // The tapped POI already has the name a person would recognise.
                            name = poi.name.ifBlank { null },
                        )
                        return@launch
                    }
                    // The archive carries a POI's kind, name and point and nothing else, so
                    // phone, website, hours and address are still joined from the offline
                    // index on IO inside `osmPlace`.
                    viewModel.stashRouteSelection()
                    viewModel.set(osmPlace(poi.name, poi.position, poiType = type))
                    sheetState?.partialExpand()
                    return@launch
                }

                // Fall back to the basemap's own place labels from the native pick.
                // Resolving one may make a Wikidata round-trip, so the ViewModel owns
                // that rather than this handler. `VulkanMapSurface` registers the native
                // pick, so this is populated whenever a placed label is under the finger;
                // when nothing is, it falls through to reverse-geocode exactly like a
                // blank tap.
                // A slop box, not the bare point: a place label is a thin line of text, and a
                // zero-size query only hit when the finger landed exactly on a glyph — so a tap
                // "on" a city almost always fell through to reverse-geocode. The same
                // [MapChromeMetrics.hitSlop] the pin picker uses makes tapping the name select it.
                val slop = MapChromeMetrics.hitSlop
                val label = viewModel.resolveAdminLabel(
                    projection.queryRenderedLabels(
                        DpRect(offset.x - slop, offset.y - slop, offset.x + slop, offset.y + slop),
                        NATIVE_LABEL_LAYER_IDS,
                    ).mapNotNull { it.toFeature1() }
                )
                if (label != null) {
                    viewModel.stashRouteSelection()
                    viewModel.set(label)
                    sheetState?.partialExpand()
                    return@launch
                }

                // A tapped vehicle sprite opens its trip sheet (CPU nearest
                // check; vehicles sit outside the GPU pick path).
                if (openVehicleTrip(
                        vehicles, projection, offset,
                        viewModel, transitViewModel, sheetState,
                    )
                ) {
                    return@launch
                }

                // Nothing hit: reverse-geocode the point ("what's here?"). Online-only.
                val geo = click.position
                searchViewModel.reverseGeocode(geo.latitude, geo.longitude) { place ->
                    if (place != null) {
                        viewModel.stashRouteSelection()
                        viewModel.set(place)
                        coroutineScope.launch { sheetState?.partialExpand() }
                    }
                }
            }
        },
    ) {
        MapLayers(
            satelliteEnabled = satelliteEnabled,
            transitEnabled = transitEnabled,
        )
    }
}
