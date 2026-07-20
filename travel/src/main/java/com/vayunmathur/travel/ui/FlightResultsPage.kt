package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.Favorite
import com.vayunmathur.travel.data.Vertical
import com.vayunmathur.travel.network.FlightDto
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.openBooking

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightResultsPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.FlightResults,
) {
    val context = LocalContext.current
    val state by viewModel.flights.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    LaunchedEffect(route) {
        viewModel.searchFlights(route.origin, route.destination, route.depart, route.returnDate, route.adults)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${route.origin} → ${route.destination}") },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            items(state.results) { flight ->
                val fav = favorites.any { it.bookingUrl == flight.bookingUrl }
                ResultCard(
                    title = flightTitle(flight),
                    subtitle = flightSubtitle(flight),
                    price = flight.price,
                    currency = flight.currency,
                    isFavorite = fav,
                    onFavorite = { viewModel.toggleFavorite(flight.toFavorite()) },
                    onBook = { openBooking(context, flight.bookingUrl) },
                )
            }
            if (state.loading || state.error != null || (state.hasSearched && state.results.isEmpty())) {
                item {
                    StatusBox(
                        loading = state.loading,
                        error = state.error,
                        isEmpty = state.hasSearched && state.results.isEmpty(),
                    )
                }
            }
        }
    }
}

private fun flightTitle(f: FlightDto): String {
    val airline = f.airline.ifBlank { "Flight" }
    return if (f.flightNumber.isBlank()) airline else "$airline ${f.flightNumber}"
}

private fun flightSubtitle(f: FlightDto): String {
    val date = TravelViewModel.prettyDate(f.departureAt.take(10))
    val stops = when (f.transfers) {
        0 -> "Nonstop"
        1 -> "1 stop"
        else -> "${f.transfers} stops"
    }
    val ret = f.returnAt?.takeIf { it.isNotBlank() }?.let { " · Return ${TravelViewModel.prettyDate(it.take(10))}" } ?: ""
    return "$date · $stops$ret"
}

private fun FlightDto.toFavorite() = Favorite(
    bookingUrl = bookingUrl,
    vertical = Vertical.FLIGHTS.name,
    title = flightTitle(this),
    subtitle = "$origin → $destination · ${flightSubtitle(this)}",
    price = price,
    currency = currency,
)
