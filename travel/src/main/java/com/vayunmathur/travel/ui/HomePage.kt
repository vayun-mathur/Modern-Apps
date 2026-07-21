package com.vayunmathur.travel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
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

private enum class Product(val label: String) { FLIGHTS("Flights"), STAYS("Stays") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(backStack: NavBackStack<Route>, viewModel: TravelViewModel) {
    val recents by viewModel.recentSearches.collectAsStateWithLifecycle()
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    var product by remember { mutableStateOf(Product.FLIGHTS) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Travel") },
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Product.entries.forEach { p ->
                    FilterChip(selected = product == p, onClick = { product = p }, label = { Text(p.label) })
                }
            }

            when (product) {
                Product.FLIGHTS -> FlightSearchForm(viewModel) { query ->
                    backStack.add(
                        Route.FlightResults(
                            slices = query.slices,
                            adults = query.adults,
                            children = query.children,
                            infants = query.infants,
                            cabin = query.cabin,
                            maxConnections = query.maxConnections,
                        )
                    )
                }
                Product.STAYS -> StaySearchForm(viewModel) { place, checkIn, checkOut, rooms, adults ->
                    backStack.add(Route.StayResults(place, checkIn, checkOut, rooms, adults))
                }
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
                    TripSummaryCard(trip) { backStack.add(destinationForTrip(trip)) }
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

/** Where tapping a trip summary goes, depending on flight vs stay. */
private fun destinationForTrip(trip: BookedTrip): Route =
    if (trip.type == "stay") Route.StayConfirmation(trip.orderId) else Route.Confirmation(trip.orderId)

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

/** Rebuild the results Route for a stored search (flight or stay). */
private fun openRecent(backStack: NavBackStack<Route>, recent: RecentSearch) {
    if (recent.vertical == "STAYS") {
        backStack.add(
            Route.StayResults(
                place = recent.origin.orEmpty(),
                checkIn = recent.depart.orEmpty(),
                checkOut = recent.returnDate.orEmpty(),
                rooms = 1,
                adults = recent.adults,
            )
        )
        return
    }
    val origin = recent.origin.orEmpty()
    val destination = recent.destination.orEmpty()
    val depart = recent.depart.orEmpty()
    val slices = if (!recent.returnDate.isNullOrBlank()) {
        "$origin:$destination:$depart,$destination:$origin:${recent.returnDate}"
    } else {
        "$origin:$destination:$depart"
    }
    backStack.add(
        Route.FlightResults(
            slices = slices,
            adults = recent.adults,
            children = "",
            infants = 0,
            cabin = recent.cabin,
            maxConnections = -1,
        )
    )
}
