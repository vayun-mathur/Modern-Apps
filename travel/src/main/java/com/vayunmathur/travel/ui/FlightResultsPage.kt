package com.vayunmathur.travel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.R
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.util.FlightQuery
import com.vayunmathur.travel.util.FlightResultsActions
import com.vayunmathur.travel.util.FlightResultsState
import com.vayunmathur.travel.util.OfferSort
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.searchFlights
import com.vayunmathur.travel.util.selectOffer
import com.vayunmathur.travel.util.setFareBrandFilter
import com.vayunmathur.travel.util.setMaxStopsFilter
import com.vayunmathur.travel.util.setSort
import com.vayunmathur.travel.util.toggleAirlineFilter
import androidx.compose.ui.res.stringResource

/** Binds [TravelViewModel] and the back stack to the stateless [FlightResultsScreen]. */
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

    val actions = remember(viewModel, backStack, query) {
        object : FlightResultsActions {
            override fun setSort(sort: OfferSort) = viewModel.setSort(sort)
            override fun setMaxStopsFilter(maxStops: Int?) = viewModel.setMaxStopsFilter(maxStops)
            override fun toggleAirlineFilter(iata: String) = viewModel.toggleAirlineFilter(iata)
            override fun setFareBrandFilter(fareBrand: String?) = viewModel.setFareBrandFilter(fareBrand)
            override fun refreshOffers() = viewModel.searchFlights(query)

            override fun openOffer(offer: OfferDto) {
                viewModel.selectOffer(offer)
                backStack.add(Route.OfferReview(offer.offerId))
            }

            override fun back() = backStack.pop()
        }
    }

    FlightResultsScreen(title = title, state = state, actions = actions)
}

/**
 * The offer list, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightResultsScreen(
    title: String,
    state: FlightResultsState,
    actions: FlightResultsActions,
) {
    LazyListScaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconNavigation { actions.back() } },
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        val visible = state.visibleOffers
        val expiry = visible.mapNotNull { it.expiresAt.ifBlank { null } }.minOrNull()
        if (state.allOffers.isNotEmpty()) {
            item { SortRow(state.sort) { actions.setSort(it) } }
            item {
                FilterRow(
                    maxStops = state.filters.maxStops,
                    airlines = state.availableAirlines,
                    selectedAirlines = state.filters.airlines,
                    fareBrands = state.availableFareBrands,
                    selectedFareBrand = state.filters.fareBrand,
                    onMaxStops = { actions.setMaxStopsFilter(it) },
                    onToggleAirline = { actions.toggleAirlineFilter(it) },
                    onSelectFareBrand = { actions.setFareBrandFilter(it) },
                )
            }
        }
        if (expiry != null && visible.isNotEmpty()) {
            item { OfferExpiryBanner(expiry) { actions.refreshOffers() } }
        }
        items(visible) { offer ->
            OfferCard(offer) { actions.openOffer(offer) }
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
                label = { Text(stringResource(sort.label)) },
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
        FilterChip(selected = maxStops == null, onClick = { onMaxStops(null) }, label = { Text(stringResource(R.string.any_stops)) })
        FilterChip(selected = maxStops == 0, onClick = { onMaxStops(0) }, label = { Text(stringResource(R.string.nonstop)) })
        FilterChip(selected = maxStops == 1, onClick = { onMaxStops(1) }, label = { Text(stringResource(R.string.n_1_stop)) })
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
                Text(stringResource(R.string.refreshing_prices), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    stringResource(R.string.prices_held, formatCountdown(remaining)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** A single bookable offer row. */
@Composable
internal fun OfferCard(offer: OfferDto, onClick: () -> Unit) {
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
            AirlineLogo(
                offer.ownerLogoUrl,
                offer.ownerIata,
                modifier = Modifier.sharedContainer("travel-offer-logo-${offer.offerId}"),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        offer.owner.ifBlank { stringResource(R.string.flight) },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.sharedText("travel-offer-owner-${offer.offerId}"),
                    )
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
    val is24 = rememberIs24Hour()
    val times = "${formatTime(slice.departureAt, is24)} – ${formatTime(slice.arrivalAt, is24)}"
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
