package com.vayunmathur.maps.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.CountryMap
import com.vayunmathur.maps.RouteService
import com.vayunmathur.maps.TransitRoute
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.*
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.*

private lateinit var outlineSource: GeoJsonSource
private lateinit var routeSource: GeoJsonSource

@Composable
@MaplibreComposable
fun MyMapLayers(selectedFeature: SpecificFeature?, route: RouteService.RouteType?) {
    LaunchedEffect(Unit) {
        outlineSource = GeoJsonSource("selected-country-geojson", GeoJsonData.Features(
            Feature(Polygon(
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
    val context = LocalContext.current
    when (selectedFeature) {
        is SpecificFeature.Admin0Label -> {
            LaunchedEffect(selectedFeature) {
                outlineSource.setData(
                    GeoJsonData.Features(
                    FeatureCollection(
                        listOf(
                            createInvertedMask(
                                CountryMap.getAdmin0(
                                    context,
                                    selectedFeature.iso3166_1
                                )!!
                            )
                        )
                    )
                ))
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
                    color = feature["color"].cast<StringValue>().convertToColor(), width = const(8.dp), cap = const(
                        LineCap.Round)
                )
            }
        }

        else -> Unit
    }
}

private fun createInvertedMask(countryFeature: Feature1): Feature1 {
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