package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.GestureOptions
import com.vayunmathur.library.map.MapOptions
import com.vayunmathur.library.map.RouteOverlay
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconMyLocation
import com.vayunmathur.library.util.ConnectivityMonitor
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote

/** Values the wide/narrow layouts read; built per composition in [RideScreen]. */
internal data class RideContentData(
    val camera: CameraState,
    val routeOverlay: RouteOverlay?,
    val pickup: Place?,
    val destination: Place?,
    val pickupQuery: String,
    val destinationQuery: String,
    val suggestions: List<Place>,
    val searching: Boolean,
    val active: RouteField?,
    val results: Map<Provider, QuoteResult>,
    val comparing: Boolean,
)

/** Callbacks the layouts fire; thin wrappers over [RideScreen]'s local state. */
internal data class RideContentEvents(
    val onPickupChange: (String) -> Unit,
    val onPickupFocus: () -> Unit,
    val onResetPickup: () -> Unit,
    val onDestinationChange: (String) -> Unit,
    val onDestinationFocus: () -> Unit,
    val onClearDestination: () -> Unit,
    val onSearch: () -> Unit,
    val onSelect: (Place) -> Unit,
    val onBook: (Provider, RideQuote?) -> Unit,
)

@Composable
internal fun RideWideContent(
    data: RideContentData,
    events: RideContentEvents,
    modifier: Modifier = Modifier,
) {
    TaxiRideWideLayout(
        map = { mapModifier ->
            RideMapPane(
                camera = data.camera,
                routeOverlay = data.routeOverlay,
                pickup = data.pickup,
                destination = data.destination,
                modifier = mapModifier,
            )
        },
        panel = { panelModifier ->
            RideInputsPane(
                pickupQuery = data.pickupQuery,
                onPickupChange = events.onPickupChange,
                onPickupFocus = events.onPickupFocus,
                onResetPickup = events.onResetPickup,
                destinationQuery = data.destinationQuery,
                onDestinationChange = events.onDestinationChange,
                onDestinationFocus = events.onDestinationFocus,
                onClearDestination = events.onClearDestination,
                onSearch = events.onSearch,
                suggestions = data.suggestions,
                searching = data.searching,
                active = data.active,
                onSelect = events.onSelect,
                results = data.results,
                comparing = data.comparing,
                pickup = data.pickup,
                destination = data.destination,
                onBook = events.onBook,
                modifier = panelModifier,
            )
        },
        modifier = modifier,
    )
}

@Composable
internal fun RideNarrowContent(
    data: RideContentData,
    events: RideContentEvents,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)),
        ) {
            VectorMap(cameraState = data.camera, route = data.routeOverlay, modifier = Modifier.fillMaxSize(), options = MapOptions(gestureOptions = GestureOptions.TiltLocked), online = ConnectivityMonitor.isOnline)
            // Overlay pins for pickup and destination, positioned from the live camera
            // projection so they track pan/zoom (same pattern as fooddelivery's map).
            val projection = data.camera.projection
            if (projection != null) {
                data.pickup?.let {
                    MapPin(
                        projection.screenLocationFromPosition(
                            GeoPoint(it.location.longitude, it.location.latitude),
                        ),
                        PickupDotColor,
                    ) { IconMyLocation(tint = Color.White) }
                }
                data.destination?.let {
                    MapPin(
                        projection.screenLocationFromPosition(
                            GeoPoint(it.location.longitude, it.location.latitude),
                        ),
                        DestinationPinColor,
                    ) { IconLocationOn(tint = Color.White) }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RouteInputs(
                pickupQuery = data.pickupQuery,
                onPickupChange = events.onPickupChange,
                onPickupFocus = events.onPickupFocus,
                onResetPickup = events.onResetPickup,
                destinationQuery = data.destinationQuery,
                onDestinationChange = events.onDestinationChange,
                onDestinationFocus = events.onDestinationFocus,
                onClearDestination = events.onClearDestination,
                onSearch = events.onSearch,
            )

            if (data.suggestions.isNotEmpty() && data.active != null) {
                SuggestionList(data.suggestions) { events.onSelect(it) }
            } else if (data.searching && data.active != null) {
                LoadingRow(stringResource(R.string.searching))
            }
        }

        ResultsSection(
            results = data.results,
            comparing = data.comparing,
            hasRoute = data.pickup != null && data.destination != null,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onBook = events.onBook,
        )
    }
}
