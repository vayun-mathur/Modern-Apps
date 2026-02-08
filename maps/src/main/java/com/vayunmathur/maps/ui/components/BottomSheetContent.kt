package com.vayunmathur.maps.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.vayunmathur.library.util.round
import com.vayunmathur.maps.RouteService
import com.vayunmathur.maps.TransitRoute
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ui.RestaurantBottomSheet
import com.vayunmathur.maps.ui.verticalShape
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toLocalDateTime

@Composable
fun BottomSheetContent(selectedFeature: SpecificFeature?, setSelectedFeature: (SpecificFeature?) -> Unit, route: Map<RouteService.TravelMode, RouteService.RouteType?>?, selectedRouteType: RouteService.TravelMode, setSelectedRouteType: (RouteService.TravelMode) -> Unit, inactiveNavigation: SpecificFeature.Route?) {
    when (selectedFeature) {
        is SpecificFeature.Admin0Label -> {
            Column {
                Text(selectedFeature.name, style = MaterialTheme.typography.titleLarge)
                Text(selectedFeature.wikipedia, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is SpecificFeature.Admin1Label -> {
            Column {
                Text(selectedFeature.name, style = MaterialTheme.typography.titleLarge)
                Text(selectedFeature.wikipedia, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is SpecificFeature.Restaurant -> {
            RestaurantBottomSheet(inactiveNavigation, selectedFeature) {
                if(inactiveNavigation == null) {
                    setSelectedFeature(SpecificFeature.Route(listOf(null, selectedFeature)))
                } else {
                    setSelectedFeature(SpecificFeature.Route(inactiveNavigation.waypoints + listOf(selectedFeature)))
                }
            }
        }
        is SpecificFeature.Route -> {
            if(route != null) {
                Column {
                    PrimaryTabRow(route.entries.indexOfFirst { it.key == selectedRouteType }) {
                        route.entries.forEach { it ->
                            Tab(
                                selectedRouteType == it.key,
                                { setSelectedRouteType(it.key) }) {
                                Text(
                                    it.key.name.lowercase()
                                        .replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                    val route = route[selectedRouteType]
                    if(route != null) {
                        ListItem({ Text(route.duration.toString()) }, supportingContent = {
                            Text("${(route.distanceMeters / 1000.0).round(2)} km")
                        })
                        Spacer(Modifier.height(8.dp))
                        Column(
                            Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (route is TransitRoute) {
                                val TIME_FORMAT = LocalTime.Format {
                                    amPmHour(Padding.NONE)
                                    chars(":")
                                    minute()
                                    amPmMarker(" AM", " PM")
                                }
                                Card(shape = verticalShape(0, 2)) {
                                    ListItem({
                                        val origin = selectedFeature.waypoints.first()?.name ?: "Your location"
                                        Text(origin)
                                    }, trailingContent = {
                                        Text(
                                            route.startTime()
                                                .toLocalDateTime(TimeZone.currentSystemDefault()).time.format(
                                                TIME_FORMAT
                                            )
                                        )
                                    })
                                }
                                route.steps.forEachIndexed { idx, it ->
                                    Card() {
                                        when (it) {
                                            is TransitRoute.Step.WalkStep -> {
                                                ListItem({
                                                    Text(
                                                        "Walk ${it.duration} (${
                                                            (it.distanceMeters / 1000).round(
                                                                1
                                                            )
                                                        } km)"
                                                    )
                                                })
                                            }

                                            is TransitRoute.Step.TransitStep -> {
                                                if (idx > 0 && route.steps[idx - 1] is TransitRoute.Step.TransitStep) {
                                                    val prev =
                                                        route.steps[idx - 1] as TransitRoute.Step.TransitStep
                                                    if (prev.arrivalStation == it.departureStation) {
                                                        ListItem({
                                                            Text("Transfer")
                                                        })
                                                    }
                                                }
                                                Row(Modifier.height(IntrinsicSize.Min)) {
                                                    Surface(
                                                        Modifier.fillMaxHeight().width(10.dp),
                                                        RoundedCornerShape(12.dp),
                                                        color = Color(it.lineColor.toColorInt())
                                                    ) {}
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
                                        val origin = selectedFeature.waypoints.last()?.name ?: "Your location"
                                        Text(origin)
                                    }, trailingContent = {
                                        Text(
                                            route.endTime()
                                                .toLocalDateTime(TimeZone.currentSystemDefault()).time.format(
                                                TIME_FORMAT
                                            )
                                        )
                                    })
                                }
                            } else if (route is RouteService.Route) {
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
                    } else {
                        ListItem({
                            Text("No route found")
                        })
                    }
                }
            }
        }
        else -> Unit
    }
}