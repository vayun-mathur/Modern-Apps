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
import com.vayunmathur.library.ui.AssistChip
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
import com.vayunmathur.travel.util.FlightQuery
import com.vayunmathur.travel.util.TravelViewModel

/**
 * Round-trip step 1: pick the outbound flight. Starts the partial offer request
 * and lists the outbound-leg offers.
 */
@Composable
fun OutboundSelectPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.OutboundSelect,
) {
    LaunchedEffect(route) {
        viewModel.startPartialSearch(
            FlightQuery(
                slices = route.slices,
                adults = route.adults,
                children = route.children,
                infants = route.infants,
                cabin = route.cabin,
                maxConnections = route.maxConnections,
                isRoundTrip = true,
            )
        )
    }
    PartialLegScaffold(
        title = "Choose outbound",
        subtitle = "Step 1 of 3",
        backStack = backStack,
        viewModel = viewModel,
    ) { offer ->
        backStack.add(Route.ReturnSelect(offer.offerId))
    }
}

/** Round-trip step 2: pick the return flight (priced against the outbound). */
@Composable
fun ReturnSelectPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.ReturnSelect,
) {
    LaunchedEffect(route.outboundId) { viewModel.loadPartialReturn(route.outboundId) }
    PartialLegScaffold(
        title = "Choose return",
        subtitle = "Step 2 of 3",
        backStack = backStack,
        viewModel = viewModel,
    ) { offer ->
        backStack.add(Route.FareSelect(route.outboundId, offer.offerId))
    }
}

/** Round-trip step 3: pick the final fare, then continue to review. */
@Composable
fun FareSelectPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.FareSelect,
) {
    LaunchedEffect(route.outboundId, route.returnId) {
        viewModel.loadPartialFares(route.outboundId, route.returnId)
    }
    PartialLegScaffold(
        title = "Choose fare",
        subtitle = "Step 3 of 3",
        backStack = backStack,
        viewModel = viewModel,
    ) { offer ->
        viewModel.selectOffer(offer)
        backStack.add(Route.OfferReview(offer.offerId))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartialLegScaffold(
    title: String,
    subtitle: String,
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    onSelect: (OfferDto) -> Unit,
) {
    val state by viewModel.partialFlow.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
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
            items(state.offers) { offer -> PartialOfferCard(offer) { onSelect(offer) } }
            if (state.loading || state.error != null || state.offers.isEmpty()) {
                item {
                    StatusBox(
                        loading = state.loading,
                        error = state.error,
                        isEmpty = !state.loading && state.error == null && state.offers.isEmpty(),
                        emptyMessage = "No flights available for this leg.",
                    )
                }
            }
        }
    }
}

@Composable
private fun PartialOfferCard(offer: OfferDto, onClick: () -> Unit) {
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
                        AssistChip(onClick = {}, label = { Text(offer.fareBrand) })
                    }
                }
                offer.slices.forEach { slice ->
                    val times = "${formatTime(slice.departureAt)} – ${formatTime(slice.arrivalAt)}"
                    Text(
                        "${slice.origin} → ${slice.destination}  ·  $times",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        listOf(stopsLabel(slice.stops), formatDuration(slice.durationMinutes))
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
