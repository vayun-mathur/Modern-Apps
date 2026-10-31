package com.vayunmathur.taxi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.GestureOptions
import com.vayunmathur.library.map.MapOptions
import com.vayunmathur.library.map.RouteOverlay
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.util.ConnectivityMonitor
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconMyLocation
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote

@Composable
internal fun RideMapPane(
    camera: CameraState,
    routeOverlay: RouteOverlay?,
    pickup: Place?,
    destination: Place?,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        VectorMap(cameraState = camera, route = routeOverlay, modifier = Modifier.fillMaxSize(), options = MapOptions(gestureOptions = GestureOptions.TiltLocked), online = ConnectivityMonitor.isOnline)
        val projection = camera.projection
        if (projection != null) {
            pickup?.let {
                MapPin(
                    projection.screenLocationFromPosition(
                        GeoPoint(it.location.longitude, it.location.latitude),
                    ),
                    PickupDotColor,
                ) { IconMyLocation(tint = Color.White) }
            }
            destination?.let {
                MapPin(
                    projection.screenLocationFromPosition(
                        GeoPoint(it.location.longitude, it.location.latitude),
                    ),
                    DestinationPinColor,
                ) { IconLocationOn(tint = Color.White) }
            }
        }
    }
}

@Composable
internal fun RideInputsPane(
    pickupQuery: String,
    onPickupChange: (String) -> Unit,
    onPickupFocus: () -> Unit,
    onResetPickup: () -> Unit,
    destinationQuery: String,
    onDestinationChange: (String) -> Unit,
    onDestinationFocus: () -> Unit,
    onClearDestination: () -> Unit,
    onSearch: () -> Unit,
    suggestions: List<Place>,
    searching: Boolean,
    active: RouteField?,
    onSelect: (Place) -> Unit,
    results: Map<Provider, QuoteResult>,
    comparing: Boolean,
    pickup: Place?,
    destination: Place?,
    onBook: (Provider, RideQuote?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RouteInputs(
                pickupQuery = pickupQuery,
                onPickupChange = onPickupChange,
                onPickupFocus = onPickupFocus,
                onResetPickup = onResetPickup,
                destinationQuery = destinationQuery,
                onDestinationChange = onDestinationChange,
                onDestinationFocus = onDestinationFocus,
                onClearDestination = onClearDestination,
                onSearch = onSearch,
            )
            if (suggestions.isNotEmpty() && active != null) {
                SuggestionList(suggestions, onSelect)
            } else if (searching && active != null) {
                LoadingRow(stringResource(R.string.searching))
            }
        }
        ResultsSection(
            results = results,
            comparing = comparing,
            hasRoute = pickup != null && destination != null,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onBook = onBook,
        )
    }
}

@Composable
internal fun RouteInputs(
    pickupQuery: String,
    onPickupChange: (String) -> Unit,
    onPickupFocus: () -> Unit,
    onResetPickup: () -> Unit,
    destinationQuery: String,
    onDestinationChange: (String) -> Unit,
    onDestinationFocus: () -> Unit,
    onClearDestination: () -> Unit,
    onSearch: () -> Unit,
) {
    OutlinedTextField(
        value = pickupQuery,
        onValueChange = onPickupChange,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onPickupFocus() },
        label = { Text(stringResource(R.string.pickup_label)) },
        placeholder = { Text(stringResource(R.string.pickup_placeholder)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = { Box(Modifier.size(11.dp).clip(CircleShape).background(PickupDotColor)) },
        trailingIcon = {
            IconButton(onClick = onResetPickup) {
                IconMyLocation(tint = MaterialTheme.colorScheme.primary)
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )

    OutlinedTextField(
        value = destinationQuery,
        onValueChange = onDestinationChange,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onDestinationFocus() },
        label = { Text(stringResource(R.string.destination)) },
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = { IconLocationOn(tint = DestinationPinColor) },
        trailingIcon = {
            if (destinationQuery.isNotEmpty()) {
                IconButton(onClick = onClearDestination) { IconClose() }
            } else {
                IconSearch(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

/** A circular marker centred on [at] (a viewport offset from the camera projection). */
@Composable
internal fun MapPin(at: DpOffset, color: Color, icon: @Composable () -> Unit) {
    val size = 32.dp
    Box(Modifier.offset(at.x - size / 2, at.y - size / 2)) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
    }
}

@Composable
internal fun SuggestionList(suggestions: List<Place>, onSelect: (Place) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            suggestions.forEachIndexed { index, place ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(place) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconLocationOn(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            place.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val address = place.address
                        if (address != null && address != place.name) {
                            Text(
                                address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (index < suggestions.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = 48.dp))
                }
            }
        }
    }
}
