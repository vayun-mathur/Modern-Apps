package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppPullToRefreshBox
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconFlight
import com.vayunmathur.library.ui.IconHotel
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.TRIP_STATUS_CANCELLED
import com.vayunmathur.travel.domain.isTripPollingStatus
import com.vayunmathur.travel.domain.tripStatusLabel
import com.vayunmathur.travel.platform.TravelViewModel
import com.vayunmathur.travel.platform.refreshStoredTrip
import androidx.compose.ui.res.stringResource

/**
 * Trips hub: every stored trip (flights + stays). Pull-to-refresh (or the top
 * action) refreshes pending trips from the server; each row also carries its
 * own refresh while its status is still pending/processing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsPage(backStack: NavBackStack<Route>, viewModel: TravelViewModel) {
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    val pending = trips.any { isTripPollingStatus(it.status) }

    AppScaffold(
        title = stringResource(R.string.my_trips),
        backStack = backStack,
        actions = {
            IconButton(onClick = { trips.forEach { viewModel.refreshStoredTrip(it.orderId) } }) {
                IconRefresh()
            }
        },
    ) { padding ->
        AppPullToRefreshBox(
            isRefreshing = pending && trips.isNotEmpty(),
            onRefresh = { trips.forEach { viewModel.refreshStoredTrip(it.orderId) } },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                if (trips.isNotEmpty()) {
                    items(trips) { trip ->
                        LocalTripCard(
                            trip = trip,
                            onClick = { backStack.add(destinationForTrip(trip)) },
                            onRefresh = { viewModel.refreshStoredTrip(trip.orderId) },
                        )
                    }
                } else {
                    item {
                        StatusBox(
                            loading = false,
                            error = null,
                            isEmpty = true,
                            emptyMessage = "No booked trips yet.",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalTripCard(trip: BookedTrip, onClick: () -> Unit, onRefresh: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onClick() },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (trip.type == "stay") {
                IconHotel(modifier = Modifier.padding(end = 12.dp))
            } else {
                IconFlight(modifier = Modifier.padding(end = 12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(trip.route, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${TravelViewModel.prettyDate(trip.departDate)} · ${trip.bookingReference.ifBlank { stringResource(tripStatusLabel(trip.status)) }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isTripPollingStatus(trip.status)) {
                    AssistChip(onClick = onClick, label = { Text(stringResource(R.string.payment_due)) })
                } else if (trip.status.equals(TRIP_STATUS_CANCELLED, ignoreCase = true)) {
                    AssistChip(onClick = onClick, label = { Text(stringResource(R.string.cancelled)) })
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatMoney(trip.amount, trip.currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (isTripPollingStatus(trip.status)) {
                    IconButton(onClick = onRefresh) { IconRefresh() }
                }
            }
        }
    }
}

/** Where tapping a local trip goes, depending on flight vs stay. */
private fun destinationForTrip(trip: BookedTrip): Route =
    if (trip.type == "stay") Route.StayConfirmation(trip.orderId) else Route.Confirmation(trip.orderId)
