package com.vayunmathur.travel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.data.RecentSearch
import com.vayunmathur.travel.util.TravelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(backStack: NavBackStack<Route>, viewModel: TravelViewModel) {
    val recents by viewModel.recentSearches.collectAsStateWithLifecycle()
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flights") },
                actions = {
                    if (trips.isNotEmpty()) {
                        TextButton(onClick = { backStack.add(Route.Trips) }) { Text("My trips") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            FlightSearchForm(viewModel) { origin, destination, depart, ret, adults, cabin ->
                backStack.add(Route.FlightResults(origin, destination, depart, ret, adults, cabin))
            }

            if (recents.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionHeader("Recent searches")
                    TextButton(onClick = { viewModel.clearRecents() }) { Text("Clear") }
                }
                recents.forEach { recent ->
                    RecentSearchCard(recent) { openRecent(backStack, recent) }
                }
            }

            if (trips.isNotEmpty()) {
                SectionHeader("My trips")
                trips.take(3).forEach { trip ->
                    TripSummaryCard(trip) { backStack.add(Route.Confirmation(trip.orderId)) }
                }
                if (trips.size > 3) {
                    TextButton(
                        onClick = { backStack.add(Route.Trips) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("See all ${trips.size} trips") }
                }
            }

            Column(Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

@Composable
private fun RecentSearchCard(recent: RecentSearch, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Flight, contentDescription = null)
            Text(recent.label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TripSummaryCard(trip: BookedTrip, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(trip.route, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${TravelViewModel.prettyDate(trip.departDate)} · ${trip.bookingReference}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatMoney(trip.amount, trip.currency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Rebuild the flight-results Route for a stored search and navigate to it. */
private fun openRecent(backStack: NavBackStack<Route>, recent: RecentSearch) {
    backStack.add(
        Route.FlightResults(
            origin = recent.origin.orEmpty(),
            destination = recent.destination.orEmpty(),
            depart = recent.depart.orEmpty(),
            returnDate = recent.returnDate,
            adults = recent.adults,
            cabin = recent.cabin,
        )
    )
}
