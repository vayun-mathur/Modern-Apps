package com.vayunmathur.maps.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconWork
import androidx.compose.ui.unit.dp as dpAlias
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.PrimaryTabRow
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.verticalShape
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ipc.RideEstimateClient
import com.vayunmathur.maps.ipc.RideHandoffContract
import com.vayunmathur.maps.ipc.rememberRideEstimate
import com.vayunmathur.maps.util.NavigationService
import com.vayunmathur.maps.util.NavigationSessionManager
import com.vayunmathur.maps.util.RouteService
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.formatDistance
import com.vayunmathur.maps.util.formatDuration
import com.vayunmathur.maps.util.formatEta
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable

/**
 * The directions panel: one segment per travel mode, then the turn-by-turn step list.
 *
 * Each segment carries its own time and distance, so there is no separate summary row —
 * comparing modes is the point of the control, and that only works if the numbers are on
 * it. Rideshare appears as a fifth segment when the taxi app is installed, showing
 * arrival and price instead, and is a UI-level pseudo-mode rather than a
 * [RouteService.TravelMode].
 *
 * Stateless (no ViewModel) so the store-listing previews can render it - the map it
 * normally sits over is a native surface a preview cannot draw.
 */
@Composable
fun RouteSheet(
    selectedFeature: SpecificFeature.Route,
    route: Map<RouteService.TravelMode, RouteService.RouteType?>,
    selectedRouteType: RouteService.TravelMode,
    setSelectedRouteType: (RouteService.TravelMode) -> Unit,
    navState: NavigationSessionManager.NavState = NavigationSessionManager.NavState.Idle,
    userPosition: GeoPoint? = null,
    modifier: Modifier = Modifier,
    onStartNavigation: ((String, RouteService.TravelMode) -> Unit)? = null,
    alternatives: List<RouteService.Route> = emptyList(),
    selectedAlternative: RouteService.Route? = null,
    onSelectAlternative: (RouteService.Route?) -> Unit = {},
) {
    val context = LocalContext.current

    // Rideshare endpoints. Origin may be "from here" (a null waypoint) → the user's live
    // position; the destination must be a real waypoint, so it never falls back to it.
    val rideOrigin = selectedFeature.waypoints.firstOrNull()
    val rideDest = selectedFeature.waypoints.lastOrNull()
    val rideOriginPos = rideOrigin?.position ?: userPosition
    val rideDestPos = rideDest?.position
    val driveRoute = route[RouteService.TravelMode.DRIVE]

    val taxiInstalled = remember { RideEstimateClient.isInstalled(context) }
    val rideEstimate =
        if (taxiInstalled && rideOriginPos != null && rideDestPos != null) {
            rememberRideEstimate(
                context,
                rideOriginPos.latitude, rideOriginPos.longitude,
                rideDestPos.latitude, rideDestPos.longitude,
                rideOrigin?.name, rideDest.name,
            ).value?.takeIf { it.available }
        } else null

    // A ride substitutes for driving, so it needs the drive route to borrow a duration
    // and a polyline from. No taxi app, no segment — same as the card this replaced.
    val rideshareOffered = taxiInstalled &&
        rideOriginPos != null &&
        rideDestPos != null &&
        selectedFeature.waypoints.size >= 2 &&
        driveRoute != null

    // Rideshare is a segment in this selector but deliberately **not** a
    // [RouteService.TravelMode]: it has no routing profile of its own, and a new variant
    // would make every `when` across TravelMode's thirteen consumers non-exhaustive. So
    // its selection lives here, and picking it leaves the mode on DRIVE — a ride follows
    // the driving route, which is what the map is already drawing.
    var rideshareSelected by remember { androidx.compose.runtime.mutableStateOf(false) }
    val showRideshare = rideshareSelected && rideshareOffered

    Column(modifier) {
        // A tab row rather than the ButtonGroup this replaced: each segment now carries
        // three stacked lines (mode, time, distance), and `toggleableItem` takes a label
        // string with no content slot. Tab's content overload is the only primitive in
        // library/ui that holds a column.
        val modes = route.keys.toList()
        val selectedIndex =
            if (showRideshare) modes.size else modes.indexOf(selectedRouteType).coerceAtLeast(0)
        PrimaryTabRow(selectedTabIndex = selectedIndex, modifier = Modifier.fillMaxWidth()) {
            modes.forEach { mode ->
                val modeRoute = route[mode]?.takeIf { it !is RouteService.EmptyRoute }
                Tab(
                    selected = !showRideshare && selectedRouteType == mode,
                    onClick = {
                        rideshareSelected = false
                        setSelectedRouteType(mode)
                    },
                ) {
                    ModeTab(
                        label = stringResource(
                            when (mode) {
                                RouteService.TravelMode.WALK -> R.string.travel_mode_walk
                                RouteService.TravelMode.BICYCLE -> R.string.travel_mode_bicycle
                                RouteService.TravelMode.DRIVE -> R.string.travel_mode_drive
                                RouteService.TravelMode.TRANSIT -> R.string.layer_transit
                            }
                        ),
                        primary = modeRoute?.let { formatDuration(context, it.duration) },
                        secondary = modeRoute?.let { formatDistance(it.distanceMeters) },
                    )
                }
            }
            if (rideshareOffered) {
                Tab(
                    selected = showRideshare,
                    onClick = {
                        rideshareSelected = true
                        setSelectedRouteType(RouteService.TravelMode.DRIVE)
                    },
                ) {
                    ModeTab(
                        label = stringResource(R.string.route_taxi_title),
                        // Dropoff, which the provider does not give us: `etaMinutes` is
                        // the pickup wait. Device-local wall clock, unlike a transit
                        // itinerary's feed-local times, because a ride is happening now.
                        primary = rideEstimate?.etaMinutes?.let { wait ->
                            formatEta(
                                System.currentTimeMillis() +
                                    wait * 60_000L +
                                    driveRoute.duration.inWholeMilliseconds
                            )
                        },
                        secondary = rideEstimate?.fareEstimate,
                    )
                }
            }
        }

        if (showRideshare) {
            // No step list and no Start Navigation: the ride is driven for the user, so
            // booking it is the only action.
            RideshareBooking(
                originLat = rideOriginPos.latitude,
                originLng = rideOriginPos.longitude,
                originLabel = rideOrigin?.name,
                destLat = rideDestPos.latitude,
                destLng = rideDestPos.longitude,
                destLabel = rideDest.name,
            )
            return@Column
        }

        if (alternatives.isNotEmpty()) {
            AlternativesSection(
                alternatives = alternatives,
                selected = selectedAlternative,
                onSelect = onSelectAlternative,
            )
        }

        val routeForMode = route[selectedRouteType]
        if (routeForMode != null) {
            if (routeForMode !is RouteService.EmptyRoute) {
                // Time and distance live in the selector now, so the summary row that
                // used to sit here would only repeat the selected tab.
                //
                // "Start Navigation" needs a concrete Route (steps + polyline), no active
                // session, and a real destination. TRANSIT is excluded: the navigation
                // engine snaps GPS to the polyline and computes ETA from progress along
                // it, which doesn't model trains/buses, and a mid-trip recalc would
                // replace transit steps with walking ones. The step list still shows.
                val lastWaypoint = selectedFeature.waypoints.lastOrNull()
                if (routeForMode is RouteService.Route &&
                    navState is NavigationSessionManager.NavState.Idle &&
                    selectedRouteType != RouteService.TravelMode.TRANSIT &&
                    lastWaypoint != null
                ) {
                    Button(
                        onClick = {
                            val intent = Intent(context, NavigationService::class.java)
                            context.startForegroundService(intent)
                            NavigationSessionManager.init(context)
                            NavigationSessionManager.start(
                                route = routeForMode,
                                mode = selectedRouteType,
                                destination = lastWaypoint.position,
                                destinationLabel = lastWaypoint.name,
                            )
                            onStartNavigation?.invoke(lastWaypoint.name, selectedRouteType)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(stringResource(R.string.nav_action_start))
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (routeForMode) {
                    is RouteService.Route -> {
                        // "Leave at" and "Arrive at" bracket the leg list as ordinary rows, so
                        // the card rounding has to be indexed across all three groups rather
                        // than over the legs alone.
                        val leave = listOfNotNull(routeForMode.departureTime)
                        val arrive = listOfNotNull(routeForMode.arrivalTime)
                        val total = leave.size + routeForMode.step.size + arrive.size
                        itemsIndexed(leave) { idx, time ->
                            Card(shape = verticalShape(idx, total)) {
                                ListItem({ Text(stringResource(R.string.leave_at, time)) })
                            }
                        }
                        itemsIndexed(routeForMode.step) { idx, step ->
                            Card(shape = verticalShape(leave.size + idx, total)) {
                                val transit = step.transitDetails
                                ListItem({
                                    Text(step.navInstruction.instructions)
                                }, leadingContent = {
                                    step.navInstruction.maneuver.iconContent()?.let { icon ->
                                        icon(Modifier, LocalContentColor.current)
                                    }
                                }, supportingContent = transit?.let { t ->
                                    {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            LineBadge(
                                                t.transitLine.nameShort ?: t.transitLine.name,
                                                t.transitLine.color,
                                            )
                                            Text(
                                                transitSupportingText(t),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }, trailingContent = transit
                                    ?.stopDetails
                                    ?.takeIf { d -> d.departureTime.isNotBlank() }
                                    ?.let { d ->
                                        {
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    d.departureTime,
                                                    style = MaterialTheme.typography.labelLarge,
                                                )
                                                if (d.arrivalTime.isNotBlank()) {
                                                    Text(
                                                        d.arrivalTime,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme
                                                            .onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    })
                            }
                        }
                        itemsIndexed(arrive) { idx, time ->
                            Card(
                                shape = verticalShape(
                                    leave.size + routeForMode.step.size + idx,
                                    total,
                                )
                            ) {
                                ListItem({ Text(stringResource(R.string.arrive_at, time)) })
                            }
                        }
                    }

                    is RouteService.EmptyRoute -> {
                        item {
                            ListItem({
                                Text(stringResource(R.string.no_route_found))
                            })
                        }
                    }
                }
            }
        } else {
            ListItem({
                Text(stringResource(R.string.generating_route))
            })
        }
    }
}

/**
 * "Towards X · 4 stops" for a transit step, dropping whichever half the feed
 * doesn't provide (headsign is optional in GTFS; a single-hop ride has no
 * intermediate stops).
 */
@Composable
internal fun transitSupportingText(details: RouteService.API.TransitDetails): String {
    val headsign = details.headsign.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.transit_towards, it) }
    val stops = details.stopCount.takeIf { it > 0 }?.let {
        pluralStringResource(R.plurals.transit_stop_count, it, it)
    }
    return when {
        headsign != null && stops != null ->
            stringResource(R.string.transit_detail_separator, headsign, stops)
        else -> headsign ?: stops ?: ""
    }
}

/**
 * Genuinely different alternatives for the shown mode (already gated by
 * [com.vayunmathur.maps.util.AlternativeRoutes]): duration/distance, and
 * for transit the boarded lines, so the difference is visible rather than
 * asserted. Tapping one swaps it in as the shown route; tapping the
 * selected one again returns to the primary.
 */
@Composable
private fun AlternativesSection(
    alternatives: List<RouteService.Route>,
    selected: RouteService.Route?,
    onSelect: (RouteService.Route?) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Text(
            stringResource(R.string.alternative_routes_title),
            style = MaterialTheme.typography.titleSmall,
        )
        alternatives.forEach { alternative ->
            val lines = alternative.step.mapNotNull {
                it.transitDetails?.transitLine?.let { line -> line.nameShort ?: line.name }
            }.distinct()
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
                    .clickable { onSelect(if (selected == alternative) null else alternative) },
            ) {
                ListItem({
                    Text(
                        listOfNotNull(
                            formatDuration(context, alternative.duration),
                            formatDistance(alternative.distanceMeters),
                            lines.joinToString(" · ").takeIf { it.isNotBlank() },
                            stringResource(R.string.alternative_selected).takeIf { selected == alternative },
                        ).joinToString(" · "),
                    )
                })
            }
        }
    }
}
