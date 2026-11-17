package com.vayunmathur.maps.ui.map
import com.vayunmathur.maps.platform.TripItineraryState

import androidx.compose.runtime.Composable
import com.vayunmathur.maps.ui.SheetRoute
import com.vayunmathur.maps.data.ParkingSpot
import com.vayunmathur.maps.data.transit.TripStop
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.ui.DeparturesSheet
import com.vayunmathur.maps.ui.LayersSheet
import com.vayunmathur.maps.ui.ParkingSheet
import com.vayunmathur.maps.ui.TripSheet
import com.vayunmathur.maps.platform.DeparturesState

/** The layer toggles, grouped so the signature does not carry eight loose parameters. */
class LayerToggles(
    val traffic: Boolean,
    val satellite: Boolean,
    val safety: Boolean,
    val transit: Boolean,
    val onTraffic: (Boolean) -> Unit,
    val onSatellite: (Boolean) -> Unit,
    val onSafety: (Boolean) -> Unit,
    val onTransit: (Boolean) -> Unit,
)

/**
 * The dialog destinations over the map, rendered from the top of the
 * sheet stack: Layers / Parking / Departures / Trip are [SheetRoute]
 * dialog destinations (DialogPage-style modal sheets), pushed and
 * popped like any other stack entry. A Trip pushed over a Departures
 * board returns to that board on pop.
 */
@Composable
fun MapOverlays(
    top: SheetRoute,
    onDismiss: () -> Unit,
    layers: LayerToggles,
    parkingSpot: ParkingSpot?,
    onClearParking: () -> Unit,
    onParkingDirections: () -> Unit,
    onParkingNoteChange: (String) -> Unit,
    departures: DeparturesState,
    onRefreshDepartures: () -> Unit,
    onTrainTap: (Departure) -> Unit,
    tripSheet: TripItineraryState,
    onTripStopTap: (TripStop) -> Unit,
) {
    when (top) {
        SheetRoute.Layers -> LayersSheet(
            onDismiss = onDismiss,
            trafficEnabled = layers.traffic,
            onTrafficChange = layers.onTraffic,
            satelliteEnabled = layers.satellite,
            onSatelliteChange = layers.onSatellite,
            safetyEnabled = layers.safety,
            onSafetyChange = layers.onSafety,
            transitEnabled = layers.transit,
            onTransitChange = layers.onTransit,
        )
        SheetRoute.Parking -> parkingSpot?.let { spot ->
            ParkingSheet(
                spot = spot,
                onDismiss = onDismiss,
                onClear = onClearParking,
                onDirections = onParkingDirections,
                onNoteChange = onParkingNoteChange,
            )
        }
        is SheetRoute.Departures -> DeparturesSheet(
            state = departures,
            onDismiss = onDismiss,
            onRefresh = onRefreshDepartures,
            onTrainTap = onTrainTap,
        )
        is SheetRoute.Trip -> if (
            tripSheet is TripItineraryState.Loaded ||
            tripSheet is TripItineraryState.Loading ||
            tripSheet is TripItineraryState.Missing
        ) {
            TripSheet(
                state = tripSheet,
                onDismiss = onDismiss,
                onStopTap = onTripStopTap,
            )
        }
        else -> Unit
    }
}
