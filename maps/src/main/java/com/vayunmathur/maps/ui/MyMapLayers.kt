package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.data.CountryMap
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.TransitRoute
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.RouteService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.TileSetOptions
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.sources.rememberVectorSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position

@Composable
@MaplibreComposable
fun MyMapLayers(selectedFeature: SpecificFeature?, route: RouteService.RouteType?, styleJson: String?) {
    val trafficVersion by OfflineRouter.trafficVersion.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        OfflineRouter.initialize(context)
    }

    key(styleJson) {
        var routeSource by remember { mutableStateOf<GeoJsonSource?>(null) }
        var outlineSource by remember { mutableStateOf<GeoJsonSource?>(null) }
        val trafficSource = rememberVectorSource(
                tiles = listOf("${OfflineRouter.trafficTileUrl}?v=$trafficVersion"),
                options = TileSetOptions(maxZoom = 14)
        )

        LaunchedEffect(Unit) {
            outlineSource = GeoJsonSource(
                "selected-country-geojson",
                GeoJsonData.Features(
                    Feature(
                        Polygon(
                            coordinates =
                            listOf(
                                listOf(
                                    Position(-180.0, -90.0),
                                    Position(180.0, -90.0),
                                    Position(180.0, 90.0),
                                    Position(-180.0, 90.0),
                                    Position(-180.0, -90.0)
                                )
                            )
                        ),
                        JsonObject(emptyMap())
                    )
                ),
                GeoJsonOptions()
            )
            routeSource = GeoJsonSource(
                "route-geojson",
                GeoJsonData.Features(
                    Feature1(
                        LineString(listOf(Position(0.0, 0.0), Position(0.0, 0.0))),
                        JsonObject(emptyMap())
                    )
                ),
                GeoJsonOptions()
            )
        }


        LineLayer(
            "traffic-layer",
            trafficSource,
            sourceLayer = "traffic",
                color = feature["color"].cast<StringValue>().convertToColor(),
                width =
                        interpolate(
                                linear(),
                                zoom(),
                                7 to const(0.5.dp),
                                11 to const(1.0.dp),
                                12 to const(1.5.dp),
                                14 to const(3.dp),
                                18 to const(6.dp)
                        ),
                cap = const(LineCap.Butt)
        )

    outlineSource?.let { outlineSource ->
        routeSource?.let { routeSource ->
            when (selectedFeature) {
                is SpecificFeature.Admin0Label -> {
                    LaunchedEffect(selectedFeature, outlineSource, styleJson) {
                        outlineSource.setData(
                                GeoJsonData.Features(
                                        FeatureCollection(
                                                listOf(
                                                        createInvertedMask(
                                                                CountryMap.getAdmin0(
                                                                        context,
                                                                        selectedFeature.iso
                                                                )!!
                                                        )
                                                )
                                        )
                                )
                        )
                    }
                    FillLayer(
                            "global-mask",
                            outlineSource,
                            // filter = const(true),
                            color = const(Color.Black.copy(alpha = 0.4f))
                    )
                    LineLayer(
                            "layer2",
                            outlineSource,
                            // filter = const(true),
                            color = const(Color.Red)
                    )
                }
                is SpecificFeature.Admin1Label -> {
                    LaunchedEffect(selectedFeature, outlineSource, styleJson) {
                        outlineSource.setData(
                                GeoJsonData.Features(
                                        FeatureCollection(
                                                listOf(
                                                        createInvertedMask(
                                                                CountryMap.getAdmin1(
                                                                        context,
                                                                        selectedFeature.iso
                                                                )!!
                                                        )
                                                )
                                        )
                                )
                        )
                    }
                    FillLayer(
                            "global-mask",
                            outlineSource,
                            // filter = const(true),
                            color = const(Color.Black.copy(alpha = 0.4f))
                    )
                    LineLayer(
                            "layer2",
                            outlineSource,
                            // filter = const(true),
                            color = const(Color.Red)
                    )
                }
                is SpecificFeature.Route -> {
                    if (route != null) {
                        LaunchedEffect(route, routeSource, styleJson) {
                            if (route is RouteService.Route) {
                                val features: List<Feature1> =
                                        listOf<Feature1>(
                                                // Feature1(LineString(route.polyline),
                                                // JsonObject(emptyMap()))
                                                ) +
                                                route.step.mapIndexed { idx, it ->
                                                    val color =
                                                            if (it.travelMode ==
                                                                            RouteService.TravelMode
                                                                                    .DRIVE
                                                            ) {
                                                                when {
                                                                    it.speedRatio < 0.5 ->
                                                                            "#F44336" // Red
                                                                    it.speedRatio < 0.9 ->
                                                                            "#FFC107" // Amber/Yellow
                                                                    else -> "#4CAF50" // Green
                                                                }
                                                            } else "#1710F1"

                                                    Feature1(
                                                            LineString(it.polyline),
                                                            JsonObject(
                                                                    mapOf(
                                                                            "color" to
                                                                                    JsonPrimitive(
                                                                                            color
                                                                                    )
                                                                    )
                                                            )
                                                    )
                                                }
                                routeSource.setData(
                                        GeoJsonData.Features(FeatureCollection(features))
                                )
                            } else if (route is TransitRoute) {
                                val features: List<Feature1> =
                                        route.steps.map {
                                            when (it) {
                                                is TransitRoute.Step.WalkStep -> {
                                                    Feature1(
                                                            LineString(it.polyline),
                                                            JsonObject(
                                                                    mapOf(
                                                                            "color" to
                                                                                    JsonPrimitive(
                                                                                            "#1710F1"
                                                                                    )
                                                                    )
                                                            )
                                                    )
                                                }
                                                is TransitRoute.Step.TransitStep -> {
                                                    Feature1(
                                                            LineString(it.polyline),
                                                            JsonObject(
                                                                    mapOf(
                                                                            "color" to
                                                                                    JsonPrimitive(
                                                                                            it.lineColor
                                                                                    )
                                                                    )
                                                            )
                                                    )
                                                }
                                            }
                                        }
                                routeSource.setData(
                                        GeoJsonData.Features(FeatureCollection(features))
                                )
                            }
                        }
                        LineLayer(
                                "route",
                                routeSource,
                                color = feature["color"].cast<StringValue>().convertToColor(),
                                width = const(8.dp),
                                cap = const(LineCap.Round)
                        )
                    }
                }
                else -> Unit
            }
        }
    }
}
}

private fun createInvertedMask(countryFeature: Feature1): Feature1 {
    // 1. World Rectangle (Clockwise)
    val worldOuterRing =
            listOf(
                    Position(-180.0, 90.0), // Top Left
                    Position(180.0, 90.0), // Top Right
                    Position(180.0, -90.0), // Bottom Right
                    Position(-180.0, -90.0), // Bottom Left
                    Position(-180.0, 90.0) // Close
            )

    // 2. Extract and Reverse the country rings (force them to be holes)
    val holes =
            when (val geom = countryFeature.geometry) {
                is Polygon -> listOf(geom.coordinates.first().reversed())
                is MultiPolygon -> geom.coordinates.map { it.first().reversed() }
                else -> emptyList()
            }

    // 3. Create Polygon: [Outer, Hole1, Hole2...]
    val donutGeometry = Polygon(listOf(worldOuterRing) + holes)
    return Feature(geometry = donutGeometry, properties = countryFeature.properties)
}
