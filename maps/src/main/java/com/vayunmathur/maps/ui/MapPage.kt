package com.vayunmathur.maps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.readLines
import com.vayunmathur.library.util.round
import com.vayunmathur.maps.CountryMap
import com.vayunmathur.maps.OSM
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.RouteService
import com.vayunmathur.maps.Wikidata
import com.vayunmathur.maps.data.OpeningHours
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.source
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.map.RenderOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.rememberStyleState
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.PointGeometry
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import kotlin.time.Duration

sealed interface SpecificFeature {
    interface RoutableFeature : SpecificFeature {
        val position: Position
        val name: String
    }

    data class Admin0Label(val iso3166_1: String, val wikipedia: String, val name: String) : SpecificFeature
    data class Admin1Label(val iso3166_2: String, val wikipedia: String, val name: String) : SpecificFeature
    data class Restaurant(override val name: String, val phone: String?, val website: String?, val menu: String?, val openingHours: OpeningHours?,
                          override val position: Position): RoutableFeature
    data class Route(val from: RoutableFeature?, val to: RoutableFeature?) : SpecificFeature
}

typealias Feature1 = Feature<Geometry, JsonObject?>

fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

suspend fun parse(feature: Feature1): SpecificFeature? {
    val id = feature.id?.jsonPrimitive?.content?.toULong() ?: 0uL
    val osmID = id and ((1uL shl 44) - 1uL)
    val geometry = feature.geometry
    val properties = feature.properties ?: return null
    return when(properties.string("kind")) {
        "country" -> {
            val wiki = Wikidata.get(properties.string("wikidata")!!)
            SpecificFeature.Admin0Label(wiki.getProperty("P297")!!, wiki.getWikipedia()!!, properties.string("name:en")!!)
        }
        "region" -> {
            val wiki = Wikidata.get(properties.string("wikidata")!!)
            SpecificFeature.Admin1Label(wiki.getProperty("P300")!!, wiki.getWikipedia()!!, properties.string("name:en")!!)
        }
        "restaurant", "fast_food", "cafe" -> {
            val tags = OSM.getTags(osmID.toLong())
            println(properties)
            println(tags)
            SpecificFeature.Restaurant(tags["name"] ?: "", tags["phone"], tags["website"], tags["website:menu"], tags["opening_hours"]?.let { OpeningHours.from(it) }, (geometry as Point).coordinates)
        }
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPage() {
    var selectedFeature by remember { mutableStateOf<SpecificFeature?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        OSM.initialize(context)
        outlineSource = GeoJsonSource("selected-country-geojson", GeoJsonData.Features(
            Feature1(Polygon(
                coordinates = listOf(
                    listOf(
                        Position(-180.0, -90.0),
                        Position(180.0, -90.0),
                        Position(180.0, 90.0),
                        Position(-180.0, 90.0),
                        Position(-180.0, -90.0) // Close the ring
                    )
                )
            ), JsonObject(emptyMap()))), GeoJsonOptions())
        routeSource = GeoJsonSource("route-geojson", GeoJsonData.Features(Feature1(
            LineString(listOf(Position(0.0, 0.0),Position(0.0, 0.0))), JsonObject(emptyMap()))), GeoJsonOptions())
    }

    var allowProgrammaticHide by remember { mutableStateOf(false) }

    val scaffoldState = rememberBottomSheetScaffoldState(rememberStandardBottomSheetState(SheetValue.Hidden, {
        it != SheetValue.Hidden || allowProgrammaticHide
    }, false))

    suspend fun hide() {
        allowProgrammaticHide = true
        scaffoldState.bottomSheetState.hide()
        allowProgrammaticHide = false
    }

    var route: Route? by remember { mutableStateOf(null) }
    LaunchedEffect(selectedFeature) {
        if(selectedFeature is SpecificFeature.Route) {
            route = RouteService.computeRoute(selectedFeature as SpecificFeature.Route, Position(-118.2806312, 34.0213141))
        }
    }

    val coroutineScope = rememberCoroutineScope()

    BackHandler(selectedFeature != null) {
        coroutineScope.launch {
            selectedFeature = null
            hide()
        }
    }


    val camera = rememberCameraState()
    BottomSheetScaffold({
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 48.dp, top = 8.dp)) {
            when (val feature = selectedFeature) {
                is SpecificFeature.Admin0Label -> {
                    Column {
                        Text(feature.name, style = MaterialTheme.typography.titleLarge)
                        Text(feature.wikipedia, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is SpecificFeature.Admin1Label -> {
                    Column {
                        Text(feature.name, style = MaterialTheme.typography.titleLarge)
                        Text(feature.wikipedia, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is SpecificFeature.Restaurant -> {
                    RestaurantBottomSheet(feature) {
                        selectedFeature = SpecificFeature.Route(null, feature)
                    }
                }
                is SpecificFeature.Route -> {
                    if(route != null) {
                        val duration = Duration.parse(route!!.duration)
                        ListItem({Text(duration.toString())}, supportingContent = {
                            Text("${(route!!.distanceMeters/1000.0).round(2)} km")
                        })
                    }
                }
                else -> Unit
            }
        }
    }, Modifier, scaffoldState, (56+(if(scaffoldState.bottomSheetState.isVisible) 40 else 0)).dp) { paddingValues ->
        Box(Modifier.padding(paddingValues)) {
            Scaffold { paddingValues ->
                val coroutineScope = rememberCoroutineScope()
                val style = rememberStyleState()
                Box(Modifier.padding(paddingValues).fillMaxSize()) {

                    MaplibreMap(Modifier, //BaseStyle.Uri("https://api.protomaps.com/styles/v5/light/en.json?key=15a942f94a739448"),
                        BaseStyle.Json(LocalContext.current.assets.open("style.json").source().readLines().joinToString("\n")),
                        camera,
                        styleState = style,
                        options = MapOptions(RenderOptions(), GestureOptions.Standard, OrnamentOptions.AllDisabled),
                        onMapClick = { point, offset ->
                            coroutineScope.launch {
                                val features = camera.projection?.queryRenderedFeatures(offset, setOf("places_country", "places_region", "pois")) ?: emptyList()
                                println("FEATURES ${features.map { it.properties }}")
                                val listedFeatures = features.mapNotNull { parse(it) }
                                println("SPECIFIC FEATURES $listedFeatures")
                                listedFeatures.firstOrNull()?.let {
                                    selectedFeature = it
                                    scaffoldState.bottomSheetState.expand()
                                }
                            }
                            ClickResult.Pass
                        }
                    ) {
                        MyMapLayers(selectedFeature, route)
                    }

                    if(selectedFeature is SpecificFeature.Route) {
                        Column(Modifier.align(Alignment.TopCenter).padding(16.dp).fillMaxWidth()) {
                            Card(shape = verticalShape(0, 2)) {
                                ListItem({
                                    Text((selectedFeature as SpecificFeature.Route).from?.name ?: "Your location")
                                }, colors = ListItemDefaults.colors(Color.Transparent))
                            }
                            Spacer(Modifier.height(2.dp))
                            Card(shape = verticalShape(1, 2)) {
                                ListItem({
                                    Text((selectedFeature as SpecificFeature.Route).to?.name ?: "Your location")
                                }, colors = ListItemDefaults.colors(Color.Transparent))
                            }
                        }
                    }
                }
            }
        }
    }
}

fun createInvertedMask(countryFeature: Feature1): Feature1 {
    // 1. World Rectangle (Clockwise)
    val worldOuterRing = listOf(
        Position(-180.0, 90.0),  // Top Left
        Position(180.0, 90.0),   // Top Right
        Position(180.0, -90.0),  // Bottom Right
        Position(-180.0, -90.0), // Bottom Left
        Position(-180.0, 90.0)   // Close
    )

    // 2. Extract and Reverse the country rings (force them to be holes)
    val holes = when (val geom = countryFeature.geometry) {
        is Polygon -> listOf(geom.coordinates.first().reversed())
        is MultiPolygon -> geom.coordinates.map { it.first().reversed() }
        else -> emptyList()
    }

    // 3. Create Polygon: [Outer, Hole1, Hole2...]
    val donutGeometry = Polygon(listOf(worldOuterRing) + holes)
    return Feature(geometry = donutGeometry, properties = countryFeature.properties)
}

lateinit var outlineSource: GeoJsonSource
lateinit var routeSource: GeoJsonSource

@Composable
@MaplibreComposable
fun MyMapLayers(selectedFeature: SpecificFeature?, route: Route?) {
    val context = LocalContext.current
    when (selectedFeature) {
        is SpecificFeature.Admin0Label -> {
            LaunchedEffect(selectedFeature) {
                outlineSource.setData(GeoJsonData.Features(FeatureCollection(listOf(createInvertedMask(CountryMap.getAdmin0(context, selectedFeature.iso3166_1)!!)))))
            }
            FillLayer("global-mask", outlineSource,
                //filter = const(true),
                color = const(Color.Black.copy(alpha = 0.4f))
            )
            LineLayer("layer2", outlineSource,
                //filter = const(true),
                color = const(Color.Red)
            )
        }

        is SpecificFeature.Admin1Label -> {
            LaunchedEffect(selectedFeature) {
                outlineSource.setData(GeoJsonData.Features(FeatureCollection(listOf(createInvertedMask(CountryMap.getAdmin1(context, selectedFeature.iso3166_2)!!)))))
            }
            FillLayer("global-mask", outlineSource,
                //filter = const(true),
                color = const(Color.Black.copy(alpha = 0.4f))
            )
            LineLayer("layer2", outlineSource,
                //filter = const(true),
                color = const(Color.Red)
            )
        }

        is SpecificFeature.Route -> {
            if(route != null) {
                LaunchedEffect(route) {
                    routeSource.setData(GeoJsonData.Features(FeatureCollection(listOf(Feature1(
                        LineString(route.polyline), JsonObject(emptyMap())
                    )))))
                }
                LineLayer("route", routeSource,
                    color = const(Color.Blue), width = const(8.dp), cap = const(LineCap.Round))
            }
        }

        else -> Unit
    }
}