package com.vayunmathur.travel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.util.TravelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightResultsPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.FlightResults,
) {
    val state by viewModel.flights.collectAsStateWithLifecycle()

    LaunchedEffect(route) {
        viewModel.searchFlights(
            route.origin,
            route.destination,
            route.depart,
            route.returnDate,
            route.adults,
            route.cabin,
        )
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
            items(state.results) { offer ->
                OfferCard(offer) {
                    viewModel.selectOffer(offer)
                    backStack.add(Route.OfferReview(offer.offerId))
                }
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

@Composable
private fun OfferCard(offer: OfferDto, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    offer.owner.ifBlank { "Flight" },
                    style = MaterialTheme.typography.titleMedium,
                )
                offer.slices.forEach { slice -> SliceRow(slice) }
            }
            Text(
                formatMoney(offer.totalAmount, offer.currency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SliceRow(slice: SliceDto) {
    val times = "${formatTime(slice.departureAt)} – ${formatTime(slice.arrivalAt)}"
    val meta = listOf(stopsLabel(slice.stops), formatDuration(slice.durationMinutes))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    Column {
        Text(
            "${slice.origin} → ${slice.destination}  ·  $times",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            meta,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
