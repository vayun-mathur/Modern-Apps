package com.vayunmathur.travel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.util.FlightQuery
import com.vayunmathur.travel.util.OfferSort
import com.vayunmathur.travel.util.TravelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightResultsPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.FlightResults,
) {
    val state by viewModel.flights.collectAsStateWithLifecycle()
    val query = FlightQuery(
        slices = route.slices,
        adults = route.adults,
        children = route.children,
        infants = route.infants,
        cabin = route.cabin,
        maxConnections = route.maxConnections,
    )

    LaunchedEffect(route) { viewModel.searchFlights(query) }

    val firstLeg = route.slices.substringBefore(',').split(':')
    val title = "${firstLeg.getOrNull(0).orEmpty()} → ${firstLeg.getOrNull(1).orEmpty()}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val visible = state.visibleOffers
        val expiry = visible.mapNotNull { it.expiresAt.ifBlank { null } }.minOrNull()
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            if (state.allOffers.isNotEmpty()) {
                item { SortRow(state.sort) { viewModel.setSort(it) } }
                item {
                    FilterRow(
                        maxStops = state.filters.maxStops,
                        airlines = state.availableAirlines,
                        selectedAirlines = state.filters.airlines,
                        fareBrands = state.availableFareBrands,
                        selectedFareBrand = state.filters.fareBrand,
                        onMaxStops = { viewModel.setMaxStopsFilter(it) },
                        onToggleAirline = { viewModel.toggleAirlineFilter(it) },
                        onSelectFareBrand = { viewModel.setFareBrandFilter(it) },
                    )
                }
            }
            if (expiry != null && visible.isNotEmpty()) {
                item { OfferExpiryBanner(expiry) { viewModel.searchFlights(query) } }
            }
            if (state.polling && visible.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "Still searching for more fares…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(visible) { offer ->
                OfferCard(offer) {
                    viewModel.selectOffer(offer)
                    backStack.add(Route.OfferReview(offer.offerId))
                }
            }
            if (state.loading || state.error != null || (state.hasSearched && visible.isEmpty())) {
                item {
                    StatusBox(
                        loading = state.loading,
                        error = state.error,
                        isEmpty = state.hasSearched && visible.isEmpty(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SortRow(current: OfferSort, onSort: (OfferSort) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OfferSort.entries.forEach { sort ->
            FilterChip(
                selected = current == sort,
                onClick = { onSort(sort) },
                label = { Text(sort.label) },
            )
        }
    }
}

@Composable
private fun FilterRow(
    maxStops: Int?,
    airlines: List<String>,
    selectedAirlines: Set<String>,
    fareBrands: List<String>,
    selectedFareBrand: String?,
    onMaxStops: (Int?) -> Unit,
    onToggleAirline: (String) -> Unit,
    onSelectFareBrand: (String?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = maxStops == null, onClick = { onMaxStops(null) }, label = { Text("Any stops") })
        FilterChip(selected = maxStops == 0, onClick = { onMaxStops(0) }, label = { Text("Nonstop") })
        FilterChip(selected = maxStops == 1, onClick = { onMaxStops(1) }, label = { Text("≤ 1 stop") })
        fareBrands.forEach { brand ->
            FilterChip(
                selected = brand == selectedFareBrand,
                onClick = { onSelectFareBrand(if (brand == selectedFareBrand) null else brand) },
                label = { Text(brand) },
            )
        }
        airlines.forEach { iata ->
            FilterChip(
                selected = iata in selectedAirlines,
                onClick = { onToggleAirline(iata) },
                label = { Text(iata) },
            )
        }
    }
}

/**
 * A slim banner showing how long the current prices are held. When the hold
 * lapses (only tracked while the app is in the foreground) it calls [onExpired]
 * to reload fresh offers.
 */
@Composable
private fun OfferExpiryBanner(expiresAt: String, onExpired: () -> Unit) {
    val remaining = rememberSecondsUntil(expiresAt)
    LaunchedEffect(remaining <= 0L) { if (remaining <= 0L) onExpired() }
    val expired = remaining <= 0L
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (expired) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Refreshing prices…", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "Prices held · ${formatCountdown(remaining)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AirlineLogo(offer.ownerLogoUrl, offer.ownerIata)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(offer.owner.ifBlank { "Flight" }, style = MaterialTheme.typography.titleMedium)
                    if (offer.fareBrand.isNotBlank()) {
                        FareBrandBadge(offer.fareBrand)
                    }
                }
                offer.slices.forEach { slice -> SliceRow(slice) }
                val chips = conditionsLabels(offer.conditions)
                val bags = offer.baggageSummary
                if (chips.isNotEmpty() || bags.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        chips.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                        if (bags.isNotEmpty()) AssistChip(onClick = {}, label = { Text(bags) })
                    }
                }
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
private fun FareBrandBadge(brand: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    ) {
        Text(
            brand,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
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
