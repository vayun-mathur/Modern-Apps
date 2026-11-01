package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.domain.groupInboundSlices
import com.vayunmathur.travel.domain.groupOutboundSlices
import com.vayunmathur.travel.domain.sliceSignature
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.util.FlightQuery
import com.vayunmathur.travel.util.PartialFlowState
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.loadPartialFares
import com.vayunmathur.travel.util.selectOffer
import com.vayunmathur.travel.util.startPartialSearch
import androidx.compose.ui.res.stringResource

/**
 * Round-trip step 1: pick the flights. One synchronous search; each outbound
 * flight is a card that expands into the return flights it pairs with (round
 * trips are priced as fixed pairs). Picking a return moves on to the fares.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val state by viewModel.partialFlow.collectAsStateWithLifecycle()
    // Each outbound (cheapest round trip using it) with the returns it pairs with.
    val outbounds = remember(state.allOffers) {
        groupOutboundSlices(state.allOffers).map { out ->
            out to groupInboundSlices(state.allOffers, sliceSignature(out.slices[0]))
        }
    }
    val expanded = remember { mutableStateListOf<String>() }
    PartialScaffold(
        title = stringResource(R.string.choose_flights),
        subtitle = stringResource(R.string.step_1_of_2),
        backStack = backStack,
        state = state,
        isEmpty = outbounds.isEmpty(),
        horizontalPadding = 16.dp,
    ) {
        outbounds.forEach { (outbound, returns) ->
            val signature = sliceSignature(outbound.slices[0])
            item(key = signature) {
                OutboundFlightCard(
                    outbound = outbound,
                    returns = returns,
                    expanded = signature in expanded,
                    onToggle = { if (!expanded.remove(signature)) expanded.add(signature) },
                    onPickReturn = { offer ->
                        val inbound = offer.slices.getOrNull(1)?.let { sliceSignature(it) }.orEmpty()
                        backStack.add(Route.FareSelect(signature, inbound))
                    },
                )
            }
        }
    }
}

/** Round-trip step 2: pick the fare for the chosen outbound + return, then review. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FareSelectPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.FareSelect,
) {
    LaunchedEffect(route.outboundSignature, route.inboundSignature) {
        viewModel.loadPartialFares(route.outboundSignature, route.inboundSignature)
    }
    val state by viewModel.partialFlow.collectAsStateWithLifecycle()
    PartialScaffold(
        title = stringResource(R.string.choose_fare),
        subtitle = stringResource(R.string.step_2_of_2),
        backStack = backStack,
        state = state,
        isEmpty = state.offers.isEmpty(),
    ) {
        items(state.offers) { offer ->
            FareCard(offer) {
                viewModel.selectOffer(offer)
                backStack.add(Route.OfferReview(offer.offerId))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartialScaffold(
    title: String,
    subtitle: String,
    backStack: NavBackStack<Route>,
    state: PartialFlowState,
    isEmpty: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp = 0.dp,
    content: LazyListScope.() -> Unit,
) {
    LazyListScaffold(
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
                navigationIcon = { IconNavigation(backStack) },
            )
        },
        horizontalPadding = horizontalPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        content()
        if (state.loading || state.error != null || isEmpty) {
            item {
                StatusBox(
                    loading = state.loading,
                    error = state.error,
                    isEmpty = !state.loading && state.error == null && isEmpty,
                    emptyMessage = stringResource(R.string.no_flights_for_leg),
                )
            }
        }
    }
}

/** One fare for a chosen outbound + return: both legs, fare brand, exact total. */
@Composable
private fun FareCard(offer: OfferDto, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onClick() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AirlineLogo(offer.ownerLogoUrl, offer.ownerIata)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (offer.fareBrand.isNotBlank()) {
                    AssistChip(onClick = {}, label = { Text(offer.fareBrand) })
                }
                offer.slices.forEach { slice ->
                    LegLines(slice, slice.segments.firstOrNull()?.carrier.orEmpty())
                }
            }
            RoundTripPrice(offer, from = false)
        }
    }
}
