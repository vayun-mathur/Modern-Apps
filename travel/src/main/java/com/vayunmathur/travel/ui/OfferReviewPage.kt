package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
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
fun OfferReviewPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.OfferReview,
) {
    val review by viewModel.review.collectAsStateWithLifecycle()

    LaunchedEffect(route.offerId) { viewModel.refreshOffer(route.offerId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review flight") },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
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
                AirlineLogo(offer.ownerLogoUrl, offer.ownerIata, size = 40.dp)
                Text(offer.owner.ifBlank { "Flight" }, style = MaterialTheme.typography.titleLarge)
            }

            val chips = conditionsLabels(offer.conditions)
            if (chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
            }

            if (offer.expiresAt.isNotBlank()) {
                val remaining = rememberSecondsUntil(offer.expiresAt)
                LaunchedEffect(remaining <= 0L) {
                    if (remaining <= 0L) viewModel.refreshOffer(offer.offerId)
                }
                Text(
                    if (remaining <= 0L) "Refreshing price…" else "Price held · ${formatCountdown(remaining)}",
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

            PriceSummaryCard(offer, review.loading)

            if (review.error != null) {
                Text(
                    review.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = { backStack.add(Route.Ancillaries(offer.offerId)) },
                enabled = !review.loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue") }
        }
    }
}

@Composable
private fun SliceDetailCard(title: String, slice: SliceDto) {
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
                        "${seg.origin} ${formatTime(seg.departureAt)}  →  ${seg.destination} ${formatTime(seg.arrivalAt)}",
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
                            "Bags: $bags",
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
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Total", style = MaterialTheme.typography.titleMedium)
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
                    "Offer held until ${formatTime(offer.expiresAt)} · price may change",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
