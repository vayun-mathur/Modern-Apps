package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.airlineName
import com.vayunmathur.travel.util.initPassengers
import com.vayunmathur.travel.util.loadAircraft
import com.vayunmathur.travel.util.loadCities
import com.vayunmathur.travel.util.refreshOffer
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferReviewPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.OfferReview,
) {
    val review by viewModel.review.collectAsStateWithLifecycle()

    // Always re-verify: the review screen shows the guaranteed price, not the search price.
    LaunchedEffect(route.offerId) {
        viewModel.refreshOffer(route.offerId)
    }
    LaunchedEffect(Unit) {
        viewModel.loadAircraft()
        viewModel.loadCities()
    }

    AppScaffold(
        title = stringResource(R.string.review_flight),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val offer = review.offer
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (offer == null) {
                StatusBox(loading = review.loading, error = review.error, isEmpty = !review.loading)
                return@Column
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AirlineLogo(
                    offer.ownerLogoUrl,
                    offer.ownerIata,
                    size = 40.dp,
                    modifier = Modifier.sharedContainer("travel-offer-logo-${route.offerId}"),
                )
                Text(
                    offer.owner.ifBlank { stringResource(R.string.flight) },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.sharedText("travel-offer-owner-${route.offerId}"),
                )
            }

            val chips = conditionsLabels(offer.conditions)
            if (chips.isNotEmpty() || offer.fareBrand.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (offer.fareBrand.isNotBlank()) {
                        AssistChip(onClick = {}, label = { Text(offer.fareBrand) })
                    }
                    chips.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
            }

            if (offer.expiresAt.isNotBlank()) {
                val remaining = rememberSecondsUntil(offer.expiresAt)
                LaunchedEffect(remaining <= 0L) {
                    if (remaining <= 0L) viewModel.refreshOffer(offer.offerId)
                }
                Text(
                    if (remaining <= 0L) stringResource(R.string.refreshing_price) else stringResource(R.string.price_held, formatCountdown(remaining)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            offer.slices.forEachIndexed { index, slice ->
                SliceDetailCard(
                    title = if (offer.slices.size > 1) {
                        if (index == 0) "Outbound" else "Return"
                    } else {
                        "Itinerary"
                    },
                    slice = slice,
                )
            }

            OfferPoliciesCard(offer)
            OperatingCarrierCard(offer, viewModel)

            PriceSummaryCard(offer, review.loading)

            if (review.error != null) {
                Text(
                    review.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = {
                    viewModel.initPassengers(offer)
                    backStack.add(Route.Passengers(offer.offerId))
                },
                // A fare that failed verification can't be prebooked either.
                enabled = !review.loading && review.error == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.continue_action)) }
        }
    }
}

@Composable
private fun OfferPoliciesCard(offer: OfferDto) {
    val baggage = offer.baggageSummary
    if (offer.refundPolicy.isBlank() && offer.changePolicy.isBlank() && baggage.isBlank()) return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.fare_policies),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (baggage.isNotBlank()) {
                Text(
                    stringResource(R.string.bags, baggage),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (offer.refundPolicy.isNotBlank()) {
                Text(
                    stringResource(R.string.refund_policy, offer.refundPolicy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (offer.changePolicy.isNotBlank()) {
                Text(
                    stringResource(R.string.change_policy, offer.changePolicy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OperatingCarrierCard(offer: OfferDto, viewModel: TravelViewModel) {
    val operated = offer.slices.flatMap { it.segments }
        .filter { it.operatingCarrierIata.isNotBlank() && it.operatingCarrierIata != it.carrierIata }
        .distinctBy { it.operatingCarrierIata to it.carrierIata }
    if (operated.isEmpty()) return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            operated.forEach { seg ->
                val operating = viewModel.airlineName(seg.operatingCarrierIata)
                val flight = listOf(seg.carrierIata + seg.flightNumber).filter { it.isNotBlank() }
                    .joinToString(" ")
                Text(
                    if (flight.isNotBlank()) {
                        stringResource(R.string.operated_by_flight, operating, flight)
                    } else {
                        stringResource(R.string.operated_by, operating)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SliceDetailCard(title: String, slice: SliceDto) {
    val is24 = rememberIs24Hour()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    listOf(stopsLabel(slice.stops), formatDuration(slice.durationMinutes))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            slice.segments.forEach { seg ->
                Column {
                    Text(
                        "${seg.origin} ${formatTime(seg.departureAt, is24)}  →  ${seg.destination} ${formatTime(seg.arrivalAt, is24)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val carrier = listOf(seg.carrier, seg.flightNumber).filter { it.isNotBlank() }.joinToString(" ")
                    val detail = listOf(carrier, seg.aircraft, seg.cabinClass).filter { it.isNotBlank() }.joinToString(" · ")
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val bags = seg.baggages.filter { it.quantity > 0 }.joinToString(" · ") { it.label }
                    if (bags.isNotBlank()) {
                        Text(
                            stringResource(R.string.bags, bags),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceSummaryCard(offer: OfferDto, loading: Boolean) {
    val is24 = rememberIs24Hour()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.total), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loading) CircularProgressIndicator()
                    Text(
                        formatMoney(offer.totalAmount, offer.currency),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (offer.expiresAt.isNotBlank()) {
                HorizontalDivider()
                Text(
                    stringResource(R.string.offer_held_until_price_may_change, formatTime(offer.expiresAt, is24)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
