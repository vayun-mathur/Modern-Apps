package com.vayunmathur.maps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle.Companion.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColor
import androidx.core.graphics.toColorInt
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.readLines
import com.vayunmathur.library.util.round
import com.vayunmathur.maps.CountryMap
import com.vayunmathur.maps.FrameworkLocationManager
import com.vayunmathur.maps.OSM2
import com.vayunmathur.maps.OSM
import com.vayunmathur.maps.RouteService
import com.vayunmathur.maps.TransitRoute
import com.vayunmathur.maps.Wikidata
import com.vayunmathur.maps.data.OpeningHours
import com.vayunmathur.maps.data.TagDatabase
import com.vayunmathur.maps.ensurePmtilesReady
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okio.source
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.rem
import org.maplibre.compose.expressions.value.ExpressionValue
import org.maplibre.compose.expressions.value.IntValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.StringValue
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
fun MapPage(ds: DataStoreUtils, db: TagDatabase) {
    var selectedFeature by remember { mutableStateOf<SpecificFeature?>(null) }
    val context = LocalContext.current

    val locationManager = remember { FrameworkLocationManager(context) }

    var userPosition by remember { mutableStateOf(Position(0.0, 0.0)) }
    var userBearing by remember { mutableStateOf(0f) }
    DisposableEffect(Unit) {
        val listener = locationManager.startUpdates { position, bearing ->
            userPosition = position
            userBearing = bearing
        }

        onDispose {
            locationManager.stopUpdates(listener)
        }
    }

    LaunchedEffect(Unit) {
        OSM.initialize(context)
        OSM2.init(context, ds,db)
        println("SUBWAY SEARCH")
        println(OSM2.search("Galero Grill").map{
            OSM.getTags(it)
        })
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

    var route: Map<RouteService.TravelMode, RouteService.RouteType>? by remember { mutableStateOf(null) }
    LaunchedEffect(selectedFeature) {
        if(selectedFeature is SpecificFeature.Route) {
            route = RouteService.TravelMode.entries.associateWith {
                if(it == RouteService.TravelMode.TRANSIT) return@associateWith TransitRoute.computeRoute(selectedFeature as SpecificFeature.Route, userPosition)
                RouteService.computeRoute(selectedFeature as SpecificFeature.Route, userPosition, it)!!
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()

    BackHandler(selectedFeature != null) {
        coroutineScope.launch {
            selectedFeature = null
            hide()
        }
    }

    var selectedRouteType by remember { mutableStateOf(RouteService.TravelMode.DRIVE) }

    val camera = rememberCameraState(CameraPosition(target = Position(-118.243683,34.052235), zoom = 5.0))
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
                        Column {
                            PrimaryTabRow(route!!.entries.indexOfFirst { it.key == selectedRouteType }) {
                                route!!.entries.forEach { it ->
                                    Tab(
                                        selectedRouteType == it.key,
                                        { selectedRouteType = it.key }) {
                                        Text(
                                            it.key.name.lowercase()
                                                .replaceFirstChar { it.uppercase() })
                                    }
                                }
                            }
                            val route = route!![selectedRouteType]!!
                            ListItem({ Text(route.duration.toString()) }, supportingContent = {
                                Text("${(route.distanceMeters / 1000.0).round(2)} km")
                            })
                            Spacer(Modifier.height(8.dp))
                            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if(route is TransitRoute) {
                                    val TIME_FORMAT = LocalTime.Format {
                                        amPmHour(Padding.NONE)
                                        chars(":")
                                        minute()
                                        amPmMarker(" AM", " PM")
                                    }
                                    Card(shape = verticalShape(0, 2)) {
                                        ListItem({
                                            val origin = (selectedFeature as SpecificFeature.Route).from?.name ?: "Your location"
                                            Text(origin)
                                        }, trailingContent = {
                                            Text(route.startTime().toLocalDateTime(TimeZone.currentSystemDefault()).time.format(TIME_FORMAT))
                                        })
                                    }
                                    route.steps.forEachIndexed { idx, it ->
                                        Card() {
                                            when(it) {
                                                is TransitRoute.Step.WalkStep -> {
                                                    ListItem({
                                                        Text("Walk ${it.duration} (${(it.distanceMeters / 1000).round(1)} km)")
                                                    })
                                                }
                                                is TransitRoute.Step.TransitStep -> {
                                                    if(idx > 0 && route.steps[idx-1] is TransitRoute.Step.TransitStep) {
                                                        val prev = route.steps[idx-1] as TransitRoute.Step.TransitStep
                                                        if(prev.arrivalStation == it.departureStation) {
                                                            ListItem({
                                                                Text("Transfer")
                                                            })
                                                        }
                                                    }
                                                    Row(Modifier.height(IntrinsicSize.Min)) {
                                                        Surface(Modifier.fillMaxHeight().width(10.dp), RoundedCornerShape(12.dp), color = Color(it.lineColor.toColorInt())) {}
                                                        Column {
                                                            ListItem({
                                                                Text(it.departureStation)
                                                            })
                                                            ListItem({
                                                                Surface(
                                                                    Modifier,
                                                                    RoundedCornerShape(12.dp),
                                                                    Color(it.lineColor.toColorInt())
                                                                ) {
                                                                    Text(
                                                                        it.lineName,
                                                                        Modifier.padding(
                                                                            horizontal = 12.dp,
                                                                            vertical = 2.dp
                                                                        )
                                                                    )
                                                                }
                                                            }, supportingContent = {
                                                                Text(it.lineDirection)
                                                            }, trailingContent = {
                                                                Text(
                                                                    it.departureTime.toLocalDateTime(
                                                                        TimeZone.currentSystemDefault()
                                                                    ).time.format(TIME_FORMAT)
                                                                )
                                                            })
                                                            ListItem({
                                                                Text(it.arrivalStation)
                                                            }, trailingContent = {
                                                                Text(
                                                                    it.arrivalTime.toLocalDateTime(
                                                                        TimeZone.currentSystemDefault()
                                                                    ).time.format(TIME_FORMAT)
                                                                )
                                                            })
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Card(shape = verticalShape(1, 2)) {
                                        ListItem({
                                            val origin = (selectedFeature as SpecificFeature.Route).to?.name ?: "Your location"
                                            Text(origin)
                                        }, trailingContent = {
                                            Text(route.endTime().toLocalDateTime(TimeZone.currentSystemDefault()).time.format(TIME_FORMAT))
                                        })
                                    }
                                } else if(route is RouteService.Route) {
                                    route.step.forEachIndexed { idx, it ->
                                        Card(shape = verticalShape(idx, route.step.size)) {
                                            ListItem({
                                                Text(it.navInstruction.instructions)
                                            }, leadingContent = {
                                                it.navInstruction.maneuver.icon()?.let {
                                                    Icon(painterResource(it), null)
                                                }
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
    }, Modifier, scaffoldState, 170.dp) { paddingValues ->
        Box(Modifier.padding(top = paddingValues.calculateTopPadding())) {
            Scaffold { paddingValues ->
                val coroutineScope = rememberCoroutineScope()
                val style = rememberStyleState()
                Box(Modifier.padding(paddingValues).fillMaxSize()) {

                    MaplibreMap(Modifier,
                        BaseStyle.Json(LocalContext.current.assets.open("style.json").source().readLines().joinToString("\n").replace("PLACEHOLDER_URL", ensurePmtilesReady(LocalContext.current))),
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
                        MyMapLayers(selectedFeature, route?.get(selectedRouteType))
                    }

                    key(camera.position) {
                        Canvas(Modifier.fillMaxSize()) {
                            if (userPosition != Position(0.0, 0.0) && camera.projection != null) {
                                val offset =
                                    camera.projection!!.screenLocationFromPosition(userPosition)

                                val centerOffset = Offset(offset.x.toPx(), offset.y.toPx())
                                val arcRadius = 20.dp.toPx() // The distance from center to the arc's stroke
                                val strokeWidth = 8.dp.toPx() // How thick the arc is

                                drawCircle(
                                    Color(0xFFFFFFFF),
                                    center = centerOffset,
                                    radius = 9.5.dp.toPx()
                                )
                                drawCircle(
                                    Color(0xFF0E35F1),
                                    center = centerOffset,
                                    radius = 8.dp.toPx()
                                )

// Create a radial gradient that fades out
                                val fadingBrush = Brush.radialGradient(
                                    0f to Color(0xFF0E35F1),          // Opaque blue at the very center
                                    0.8f to Color(0xFF0E35F1),        // Stay opaque until the arc's edge
                                    1.0f to Color.Transparent,        // Fade to nothing
                                    center = centerOffset,
                                    radius = arcRadius + strokeWidth  // Gradient ends just past the stroke
                                )


                                drawArc(
                                    brush = fadingBrush,
                                    startAngle = userBearing - 90f - 30f,           // Start position (3 o'clock)
                                    sweepAngle = 60f,         // Length of the arc
                                    useCenter = false,         // Set to false for a curved line, true for a pie slice
                                    topLeft = Offset(centerOffset.x - arcRadius, centerOffset.y - arcRadius),
                                    size = Size(arcRadius * 2, arcRadius * 2),
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }
                        }
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
fun MyMapLayers(selectedFeature: SpecificFeature?, route: RouteService.RouteType?) {
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
                    if(route is RouteService.Route) {
                        val features: List<Feature1> = listOf<Feature1>(
                            //Feature1(LineString(route.polyline), JsonObject(emptyMap()))
                        ) + route.step.mapIndexed { idx, it ->
                            Feature1(
                                LineString(it.polyline),
                                JsonObject(mapOf("color" to JsonPrimitive("#1710F1")))
                            )
                        }
                        routeSource.setData(GeoJsonData.Features(FeatureCollection(features)))
                    } else if(route is TransitRoute) {
                        val features: List<Feature1> = route.steps.mapNotNull {
                            when(it) {
                                is TransitRoute.Step.WalkStep -> {
                                    Feature1(
                                        LineString(it.polyline),
                                        JsonObject(mapOf("color" to JsonPrimitive("#1710F1")))
                                    )
                                }
                                is TransitRoute.Step.TransitStep -> {
                                    Feature1(
                                        LineString(it.polyline),
                                        JsonObject(mapOf("color" to JsonPrimitive(it.lineColor)))
                                    )
                                }
                            }
                        }
                        routeSource.setData(GeoJsonData.Features(FeatureCollection(features)))
                    }
                }
                LineLayer("route", routeSource,
                    color = feature["color"].cast<StringValue>().convertToColor(), width = const(8.dp), cap = const(LineCap.Round)
                )
            }
        }

        else -> Unit
    }
}