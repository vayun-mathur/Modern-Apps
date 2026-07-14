package com.vayunmathur.maps.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.R
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconWork
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.readLines
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.AmenityDatabase
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.parse
import com.vayunmathur.maps.ensurePmtilesReady
import com.vayunmathur.maps.util.MapsZonesViewModel
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import com.vayunmathur.maps.util.ZoneDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.source
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import com.vayunmathur.maps.R as MapsR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPage(backStack: NavBackStack<Route>, viewModel: SelectedFeatureViewModel, zonesViewModel: MapsZonesViewModel, savedPlacesViewModel: SavedPlacesViewModel, db: AmenityDatabase) {
    val selectedFeature by viewModel.selectedFeature.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val savedHome by savedPlacesViewModel.home.collectAsState()
    val savedWork by savedPlacesViewModel.work.collectAsState()

    // --- ZONE DOWNLOAD STATE ---
    val camera = rememberCameraState(CameraPosition(target = Position(-118.243683,34.052235), zoom = 5.0))

    val activeZone = remember(camera.position) {
        calculateZoneId(
            camera.position.target.latitude,
            camera.position.target.longitude,
            camera.position.zoom.toFloat()
        )
    }

    LaunchedEffect(camera.position) {
        if (camera.position.zoom >= 11.0) {
            delay(300) // Debounce traffic loading
            val projection = camera.projection
            if (projection != null) {
                val bbox = projection.queryVisibleBoundingBox()
                // Load traffic for all four corners to ensure the current view is covered
                OfflineRouter.ensureTrafficLoadedNative(bbox.north, bbox.east, true)
                OfflineRouter.ensureTrafficLoadedNative(bbox.north, bbox.west, true)
                OfflineRouter.ensureTrafficLoadedNative(bbox.south, bbox.east, true)
                OfflineRouter.ensureTrafficLoadedNative(bbox.south, bbox.west, true)
            }
        }
    }

    val hybridUrl = remember(activeZone) {
        if (activeZone == null) return@remember ""

        val localFile = File(context.getExternalFilesDir(null), "zone_$activeZone.pmtiles")
        if (zonesViewModel.getZoneStatus(activeZone) == ZoneDownloadManager.ZoneStatus.FINISHED) {
            "pmtiles://file://${localFile.absolutePath}"
        } else {
            ""
        }
    }

    // Inside MapPage
    var json by remember { mutableStateOf<String?>(null) }

    // Read the style asset on Dispatchers.IO (file open), then the hybrid
    // patch step is light enough to stay on the same coroutine.
    LaunchedEffect(hybridUrl) {
        val updatedStyle = withContext<String>(Dispatchers.IO) {
            val rawStyle = context.assets.open("style.json").source().readLines().joinToString("\n")
            patchStyleForHybrid(
                rawStyle,
                ensurePmtilesReady(context),
                hybridUrl
            )
        }
        json = updatedStyle
    }

    var dismissedZone by remember { mutableStateOf<Int?>(null) }
    var showDownloadDialog by remember { mutableStateOf(false) }

    // Zone Prompting Logic
    LaunchedEffect(activeZone) {
        if (activeZone != null && activeZone != dismissedZone) {
            val status = zonesViewModel.getZoneStatus(activeZone)

            // Only prompt if the user hasn't started the download yet
            if (status == ZoneDownloadManager.ZoneStatus.NOT_STARTED) {
                showDownloadDialog = true
            }
        }
    }

    // --- LOCATION & OSM INITIALIZATION ---
    val userPosition by viewModel.userPosition.collectAsState()
    val userBearing by viewModel.userBearing.collectAsState()

    val inactiveNavigation by viewModel.inactiveNavigation.collectAsState()

    // --- ROUTE COMPUTATION ---
    val route by viewModel.routes.collectAsState(null)

    // --- NAVIGATION SESSION ---
    val navState by com.vayunmathur.maps.util.NavigationSessionManager.state.collectAsState()
    val isNavigating = navState !is com.vayunmathur.maps.util.NavigationSessionManager.NavState.Idle
    var autoFollow by remember { mutableStateOf(true) }
    var lastProgrammaticMoveMs by remember { mutableStateOf(0L) }
    val activeRoute = com.vayunmathur.maps.util.NavigationSessionManager.currentRoute
    val navProgress = (navState as? com.vayunmathur.maps.util.NavigationSessionManager.NavState.Navigating)?.progress

    // --- UI & BOTTOM SHEET STATE ---
    var allowProgrammaticHide by retain { mutableStateOf(false) }

    val scaffoldState = rememberBottomSheetScaffoldState(
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
            confirmValueChange = {
                it != SheetValue.Hidden || allowProgrammaticHide
            }
        )
    )

    LaunchedEffect(Unit) {
        if(selectedFeature != null) scaffoldState.bottomSheetState.expand()
    }

    suspend fun hide() {
        allowProgrammaticHide = true
        scaffoldState.bottomSheetState.hide()
        allowProgrammaticHide = false
    }

    fun openSearch() {
        // Style may not have finished loading — fall back to a world-spanning
        // bbox so the search query still works.
        val bbox = camera.projection?.queryVisibleBoundingBox()
        backStack.add(
            Route.SearchPage(
                null,
                bbox?.east ?: 180.0,
                bbox?.west ?: -180.0,
                bbox?.north ?: 85.0,
                bbox?.south ?: -85.0,
            )
        )
    }

    // Tapping a saved Home/Work chip recenters onto the place and opens its
    // bottom sheet, from which the user can start Directions or remove the slot.
    fun showSavedPlace(place: SavedPlace) {
        coroutineScope.launch {
            camera.animateTo(
                camera.position.copy(
                    target = Position(place.lon, place.lat),
                    zoom = maxOf(camera.position.zoom, 14.0),
                )
            )
        }
        viewModel.set(place.toFeature())
        coroutineScope.launch { scaffoldState.bottomSheetState.expand() }
    }

    BackHandler(selectedFeature != null) {
        coroutineScope.launch {
            viewModel.set(null)
            hide()
        }
    }

    BackHandler(selectedFeature == null && inactiveNavigation != null) {
        viewModel.setInactiveNavigation(null)
    }

    var selectedRouteType by retain { mutableStateOf(RouteService.TravelMode.DRIVE) }

    // --- RENDER ---
    // While actively navigating we don't want the bottom sheet to slide up
    // automatically; the in-screen overlay is the primary nav UI.
    LaunchedEffect(isNavigating) {
        if (isNavigating) {
            hide()
        }
    }

    // Camera follow: animate to snapped position / bearing whenever we get
    // a new progress sample AND the user hasn't panned away.
    LaunchedEffect(navProgress, autoFollow) {
        val p = navProgress ?: return@LaunchedEffect
        if (!autoFollow) return@LaunchedEffect
        lastProgrammaticMoveMs = System.currentTimeMillis()
        camera.animateTo(
            camera.position.copy(
                target = p.snappedPosition,
                bearing = p.courseOverGround.toDouble(),
                tilt = 60.0,
                zoom = 17.0,
            ),
            kotlin.time.Duration.parse("800ms"),
        )
    }

    // Detect user-initiated camera moves: if isCameraMoving becomes true
    // outside the ~1.2s window after our own animateTo, treat it as a pan
    // and disable auto-follow until the user taps Recenter.
    LaunchedEffect(camera.isCameraMoving, isNavigating) {
        if (!isNavigating) return@LaunchedEffect
        if (camera.isCameraMoving &&
            System.currentTimeMillis() - lastProgrammaticMoveMs > 1_200
        ) {
            autoFollow = false
        }
    }

    BottomSheetScaffold({
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 48.dp, top = 8.dp)) {
            BottomSheetContent(viewModel, selectedFeature, { viewModel.set(it) }, route, selectedRouteType, { selectedRouteType = it }, inactiveNavigation, savedPlacesViewModel, navState)
        }
    }, Modifier, scaffoldState, 170.dp) { paddingValues ->
        Scaffold(Modifier.padding(top = paddingValues.calculateTopPadding()), topBar = {
            TopAppBar({}, actions = {
                Row {
                    IconButton({
                        backStack.add(Route.DownloadedMapsPage)
                    }) {
                        IconSettings()
                    }
                } }, colors = TopAppBarDefaults.topAppBarColors(Color.Transparent))
        }) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                json?.let { json ->
                    MaplibreMap(
                        Modifier,
                        BaseStyle.Json(json),
                        camera,
                        options = MapOptions(
                            RenderOptions(),
                            GestureOptions.Standard,
                            OrnamentOptions.AllDisabled
                        ),
                        onMapClick = { _, offset ->
                            coroutineScope.launch {
                                val projection = camera.projection
                                val features = projection?.queryRenderedFeatures(
                                    offset,
                                    setOf("places_country", "places_region", "pois").flatMap {
                                        listOf("${it}_base", "${it}_hybrid")
                                    }.toSet()
                                ) ?: emptyList()
                                // parse() may do network (Wikidata) + Room
                                // per feature. queryRenderedFeatures returns
                                // one Feature PER LAYER at the tap point —
                                // often 2-3 — and we only use the first
                                // parseable result. Stop at the first hit
                                // instead of doing every Wikidata round-trip
                                // serially in the foreground.
                                val firstFeature = withContext(Dispatchers.IO) {
                                    features.firstNotNullOfOrNull { raw ->
                                        runCatching { parse(raw, db) }.getOrNull()
                                    }
                                }

                                firstFeature?.let {
                                    if (selectedFeature is SpecificFeature.Route) viewModel.setInactiveNavigation(
                                        selectedFeature as SpecificFeature.Route
                                    )
                                    viewModel.set(it)
                                    scaffoldState.bottomSheetState.expand()
                                }
                            }
                            ClickResult.Pass
                        }
                ) {
                        MyMapLayers(selectedFeature, route?.get(selectedRouteType), json, userPosition, userBearing, navProgress)
                    }
                }

                // ROUTE OVERLAY HEADERS
                if(selectedFeature is SpecificFeature.Route || inactiveNavigation != null) {
                    val routeFeature = if(selectedFeature is SpecificFeature.Route) selectedFeature as SpecificFeature.Route else inactiveNavigation!!
                    val listState = rememberLazyListState()
                    val state = rememberReorderableLazyListState(listState, onMove = { from, to ->
                        // swap their indices in the list
                        val newList = routeFeature.waypoints.toMutableList()
                        val temp = newList[from.index]
                        newList[from.index] = newList[to.index]
                        newList[to.index] = temp
                        viewModel.set(routeFeature.copy(waypoints = newList))
                    })
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).fillMaxWidth()
                    ) {
                        itemsIndexed(routeFeature.waypoints, key = { idx, it -> it?.position?.toString()?:"" }) { idx, item ->
                            ReorderableItem(state, key = item?.position?.toString() ?: "") { isDragging ->

                                val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)

                                Card(shape = verticalShape(idx, routeFeature.waypoints.size), elevation = CardDefaults.cardElevation(elevation)) {
                                    ListItem({
                                        Text(
                                            item?.name
                                                ?: stringResource(MapsR.string.your_location)
                                        )
                                    }, Modifier.clickable {
                                        // Style may not have finished loading
                                        // — fall back to a world-spanning bbox
                                        // so the search query still works.
                                        val bbox = camera.projection?.queryVisibleBoundingBox()
                                        backStack.add(Route.SearchPage(idx,
                                            bbox?.east ?: 180.0,
                                            bbox?.west ?: -180.0,
                                            bbox?.north ?: 85.0,
                                            bbox?.south ?: -85.0))
                                    }, trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if(idx > 0 && idx < routeFeature.waypoints.size - 1) {
                                                IconButton({
                                                    val newList = routeFeature.waypoints.toMutableList()
                                                    newList.removeAt(idx)
                                                    viewModel.set(routeFeature.copy(waypoints = newList))
                                                }) {
                                                    IconClose()
                                                }
                                            }
                                            Icon(
                                                painterResource(R.drawable.drag_handle_24px),
                                                stringResource(MapsR.string.reorder), Modifier.draggableHandle(),
                                            )
                                        }
                                    }, colors = ListItemDefaults.colors(Color.Transparent))
                                }
                            }
                        }
                    }
                } else {
                    val name = if(selectedFeature is SpecificFeature.RoutableFeature) {
                        (selectedFeature as SpecificFeature.RoutableFeature).name
                    } else {
                        stringResource(MapsR.string.search_placeholder)
                    }
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Card(shape = RoundedCornerShape(12.dp)) {
                            ListItem({
                                Text(name)
                            }, colors = ListItemDefaults.colors(Color.Transparent), modifier = Modifier.clickable {
                                openSearch()
                            })
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = { savedHome?.let { showSavedPlace(it) } ?: openSearch() },
                                label = {
                                    Text(stringResource(if (savedHome != null) MapsR.string.saved_place_home else MapsR.string.set_home))
                                },
                                leadingIcon = { IconHome(Modifier.size(18.dp)) },
                            )
                            AssistChip(
                                onClick = { savedWork?.let { showSavedPlace(it) } ?: openSearch() },
                                label = {
                                    Text(stringResource(if (savedWork != null) MapsR.string.saved_place_work else MapsR.string.set_work))
                                },
                                leadingIcon = { IconWork(Modifier.size(18.dp)) },
                            )
                        }
                    }
                }

                // DOWNLOAD DIALOG
                if (showDownloadDialog && activeZone != null) {
                    AlertDialog(
                        {
                            showDownloadDialog = false
                            dismissedZone = activeZone
                        }, {
                            Button({
                                zonesViewModel.startDownload(activeZone)
                                showDownloadDialog = false
                                // We don't need to set dismissedZone here because getZoneStatus
                                // will now return DOWNLOADING, preventing the effect from re-triggering
                            }) {
                                Text(stringResource(MapsR.string.download))
                            }
                        }, title = { Text(stringResource(MapsR.string.download_offline_map_title)) },
                        text = { Text(stringResource(MapsR.string.download_offline_map_text_overview, activeZone)) },
                        dismissButton = {
                            TextButton({
                                showDownloadDialog = false
                                dismissedZone = activeZone
                            }) {
                                Text(stringResource(MapsR.string.cancel))
                            }
                        }
                    )
                }

                // Live navigation overlay (top maneuver card, bottom ETA strip,
                // recenter FAB, arrival card). Hidden when nav is Idle.
                NavigationOverlay(
                    navState = navState,
                    steps = activeRoute?.step ?: emptyList(),
                    autoFollow = autoFollow,
                    onRecenter = { autoFollow = true },
                    onEndTrip = {
                        com.vayunmathur.maps.util.NavigationSessionManager.stop()
                        context.stopService(android.content.Intent(context, com.vayunmathur.maps.util.NavigationService::class.java))
                        autoFollow = true
                    },
                    onDismissArrival = {
                        com.vayunmathur.maps.util.NavigationSessionManager.stop()
                        context.stopService(android.content.Intent(context, com.vayunmathur.maps.util.NavigationService::class.java))
                    },
                )
            }
        }
    }
}

/**
 * Maps GPS coordinates to your 45x22.5 grid.
 */
fun calculateZoneId(lat: Double, lon: Double, zoom: Float): Int? {
    if(zoom < 7f) return null
    // 1. Normalize coordinates to [0, 1] range
    val normX = (lon + 180.0) / 360.0
    val normY = (lat + 90.0) / 180.0

    // 2. Map to 32-bit unsigned integer space (matching C++ uint32_t)
    // We use Long in Kotlin to safely handle unsigned 32-bit range, then toUInt
    val ix = (normX * 4294967295.0).toLong().toUInt()
    val iy = (normY * 4294967295.0).toLong().toUInt()

    // 3. Interleave the bits (Morton Encoding)
    // Since we only need the Zone ID (top 6 bits of the 64-bit spatial ID),
    // we only actually need to interleave the top 3 bits of ix and iy.
    var spatialId: Long = 0
    for (i in 0 until 32) {
        val xBit = (ix.toLong() shr i) and 1L
        val yBit = (iy.toLong() shr i) and 1L

        spatialId = spatialId or (xBit shl (2 * i))
        spatialId = spatialId or (yBit shl (2 * i + 1))
    }

    // 4. Extract top 6 bits (matching C++: (spatial_id >> 58) & 0x3F)
    // In Kotlin, for signed Long, we use ushr for logical right shift
    return ((spatialId ushr 58) and 0x3F).toInt()
}

fun patchStyleForHybrid(
    jsonString: String,
    baseLocalUrl: String,
    hybridUrl: String
): String {
    val json = Json { ignoreUnknownKeys = true }
    val root = json.parseToJsonElement(jsonString).jsonObject

    val newSources = buildJsonObject {
        putJsonObject("protomaps_base") {
            put("type", "vector")
            put("url", baseLocalUrl)
        }
        putJsonObject("protomaps_hybrid") {
            put("type", "vector")
            put("url", hybridUrl)
        }
    }

    val oldLayers = root["layers"]?.jsonArray ?: buildJsonArray {}
    val newLayers = buildJsonArray {
        oldLayers.forEach { layerElement ->
            val layer = layerElement.jsonObject
            val id = layer["id"]?.jsonPrimitive?.content ?: ""
            val type = layer["type"]?.jsonPrimitive?.content ?: ""

            if (type == "background") {
                add(layer)
            } else {
                // Zoom 0-7: Base Local
                add(buildJsonObject {
                    layer.forEach { (k, v) -> put(k, v) }
                    put("id", "${id}_base")
                    put("source", "protomaps_base")
                    put("maxzoom", 7)
                })
                // Zoom 7+: Hybrid (Local Only)
                add(buildJsonObject {
                    layer.forEach { (k, v) -> put(k, v) }
                    put("id", "${id}_hybrid")
                    put("source", "protomaps_hybrid")
                    put("minzoom", 7)
                })
            }
        }
    }

    return buildJsonObject {
        root.forEach { (k, v) -> if (k != "sources" && k != "layers") put(k, v) }
        put("sources", newSources)
        put("layers", newLayers)
    }.toString()
}
