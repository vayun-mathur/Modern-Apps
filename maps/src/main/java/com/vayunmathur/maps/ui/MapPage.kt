package com.vayunmathur.maps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomSheetScaffold
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.R
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.readLines
import com.vayunmathur.maps.FrameworkLocationManager
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.RouteService
import com.vayunmathur.maps.SelectedFeatureViewModel
import com.vayunmathur.maps.TransitRoute
import com.vayunmathur.maps.ZoneDownloadManager
import com.vayunmathur.maps.data.AmenityDatabase
import com.vayunmathur.maps.data.AmenityEntity
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.parse
import com.vayunmathur.maps.ensurePmtilesReady
import com.vayunmathur.maps.ui.components.BottomSheetContent
import com.vayunmathur.maps.ui.components.MyMapLayers
import com.vayunmathur.maps.ui.components.UserIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPage(backStack: NavBackStack<Route>, viewModel: SelectedFeatureViewModel, ds: DataStoreUtils, db: AmenityDatabase) {
    val selectedFeature by viewModel.selectedFeature.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val zoneManager = remember { ZoneDownloadManager(context) }

    // --- ZONE DOWNLOAD STATE ---
    val camera = rememberCameraState(CameraPosition(target = Position(-118.243683,34.052235), zoom = 5.0))

    val activeZone = remember(camera.position) {
        calculateZoneId(
            camera.position.target.latitude,
            camera.position.target.longitude,
            camera.position.zoom.toFloat()
        )
    }

    val hybridUrl = remember(activeZone) {
        val remoteUrl = "pmtiles://https://demo-bucket.protomaps.com/v4.pmtiles"

        if (activeZone == null) return@remember remoteUrl

        val localFile = File(context.getExternalFilesDir(null), "zone_$activeZone.pmtiles")
        val x = if (zoneManager.getZoneStatus(activeZone) == ZoneDownloadManager.ZoneStatus.FINISHED) {
            "pmtiles://file://${localFile.absolutePath}"
        } else {
            remoteUrl
        }
        println("URL: $x")
        x
    }

    val json = remember(hybridUrl) {
        patchStyleForHybrid(
            context.assets.open("style.json").source().readLines().joinToString("\n"),
            ensurePmtilesReady(context),
            hybridUrl
        )
    }

    var dismissedZone by remember { mutableStateOf<Int?>(null) }
    var showDownloadDialog by remember { mutableStateOf(false) }

    // Zone Prompting Logic
    LaunchedEffect(activeZone) {
        if (activeZone != null && activeZone != dismissedZone) {
            val status = zoneManager.getZoneStatus(activeZone)

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

    // --- UI & BOTTOM SHEET STATE ---
    var allowProgrammaticHide by retain { mutableStateOf(false) }
    val scaffoldState = rememberBottomSheetScaffoldState(
        rememberStandardBottomSheetState(SheetValue.Hidden, {
            it != SheetValue.Hidden || allowProgrammaticHide
        }, false)
    )

    suspend fun hide() {
        allowProgrammaticHide = true
        scaffoldState.bottomSheetState.hide()
        allowProgrammaticHide = false
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
    BottomSheetScaffold({
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 48.dp, top = 8.dp)) {
            BottomSheetContent(selectedFeature, { viewModel.set(it) }, route, selectedRouteType, { selectedRouteType = it }, inactiveNavigation)
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
                MaplibreMap(Modifier,
                    BaseStyle.Json(json),
                    camera,
                    options = MapOptions(RenderOptions(), GestureOptions.Standard, OrnamentOptions.AllDisabled),
                    onMapClick = { _, offset ->
                        coroutineScope.launch {
                            val projection = camera.projection
                            val features = projection?.queryRenderedFeatures(offset, setOf("places_country", "places_region", "pois").flatMap {
                                listOf("${it}_base", "${it}_hybrid")
                            }.toSet()) ?: emptyList()
                            println("FEATURES: $features")
                            val listedFeatures = features.mapNotNull { parse(it, db) }
                            println("LISTED FEATURES: $listedFeatures")

                            listedFeatures.firstOrNull()?.let {
                                if(selectedFeature is SpecificFeature.Route) viewModel.setInactiveNavigation(selectedFeature as SpecificFeature.Route)
                                viewModel.set(it)
                                scaffoldState.bottomSheetState.expand()
                            }
                        }
                        ClickResult.Pass
                    }
                ) {
                    MyMapLayers(selectedFeature, route?.get(selectedRouteType))
                }

                // USER ICON
                key(camera.position) {
                    Canvas(Modifier.fillMaxSize()) {
                        UserIcon(userPosition, userBearing, camera)
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
                                                ?: "Your location"
                                        )
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
                                                contentDescription = "Reorder",
                                                modifier = Modifier.draggableHandle(),
                                            )
                                        }
                                    }, colors = ListItemDefaults.colors(Color.Transparent), modifier = Modifier.clickable {
                                        val bbox = camera.projection!!.queryVisibleBoundingBox()
                                        backStack.add(Route.SearchPage(idx, bbox.east, bbox.west, bbox.north, bbox.south))
                                    })
                                }
                            }
                        }
                    }
                }

                // DOWNLOAD DIALOG
                if (showDownloadDialog && activeZone != null) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {
                            showDownloadDialog = false
                            dismissedZone = activeZone
                        },
                        title = { Text("Download Offline Map?") },
                        text = { Text("You are viewing Zone $activeZone. Would you like to download the high-detail offline map (approx. 4.7GB)?") },
                        confirmButton = {
                            androidx.compose.material3.Button(onClick = {
                                zoneManager.startDownload(activeZone)
                                showDownloadDialog = false
                                // We don't need to set dismissedZone here because getZoneStatus
                                // will now return DOWNLOADING, preventing the effect from re-triggering
                            }) {
                                Text("Download")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showDownloadDialog = false
                                dismissedZone = activeZone
                            }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Maps GPS coordinates to your 45x22.5 grid.
 */
fun calculateZoneId(lat: Double, lon: Double, zoom: Float): Int? {
    if (zoom < 6f) return null

    val lonIdx = ((lon + 180) / 45).toInt().coerceIn(0, 7)
    val latIdx = ((lat + 90) / 22.5).toInt().coerceIn(0, 7)

    return (latIdx * 8) + lonIdx
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
                // Zoom 0-6: Base Local
                add(buildJsonObject {
                    layer.forEach { (k, v) -> put(k, v) }
                    put("id", "${id}_base")
                    put("source", "protomaps_base")
                    put("maxzoom", 6)
                })
                // Zoom 6+: Hybrid (Switches between Local and Remote)
                add(buildJsonObject {
                    layer.forEach { (k, v) -> put(k, v) }
                    put("id", "${id}_hybrid")
                    put("source", "protomaps_hybrid")
                    put("minzoom", 6)
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