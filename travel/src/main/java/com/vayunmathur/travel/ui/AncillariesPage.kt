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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.ServiceDto
import com.vayunmathur.travel.util.TravelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AncillariesPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.Ancillaries,
) {
    val review by viewModel.review.collectAsStateWithLifecycle()
    val selectedBaggage by viewModel.selectedBaggage.collectAsStateWithLifecycle()
    val selectedSeats by viewModel.selectedSeats.collectAsStateWithLifecycle()
    val offer = review.offer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add extras") },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (offer == null) {
            StatusBox(loading = false, error = null, isEmpty = true, emptyMessage = "No offer selected.")
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val bags = offer.availableServices.filter { it.type == "baggage" }
            if (bags.isNotEmpty()) {
                SectionHeader("Extra baggage")
                bags.forEach { svc ->
                    BaggageRow(
                        service = svc,
                        quantity = selectedBaggage[svc.id] ?: 0L,
                        onQuantity = { viewModel.setBaggageQuantity(svc.id, it) },
                    )
                }
            }

            SectionHeader("Seats")
                offer.slices.forEach { slice ->
                slice.segments.forEach { seg ->
                    val segSeats = selectedSeats.filterKeys { it.startsWith("${seg.id}|") }.values
                    val chosenLabels = segSeats.joinToString(", ") { it.designator }
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${seg.origin} → ${seg.destination}", style = MaterialTheme.typography.bodyLarge)
                                if (chosenLabels.isNotBlank()) {
                                    Text(
                                        "Seats: $chosenLabels",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { backStack.add(Route.SeatMap(offer.offerId, seg.id)) },
                                enabled = seg.id.isNotBlank(),
                            ) { Text(if (segSeats.isNotEmpty()) "Change" else "Choose") }
                        }
                    }
                }
            }

            HorizontalDivider()
            ExtrasSummary(offer, selectedBaggage, selectedSeats)

            Button(
                onClick = {
                    viewModel.initPassengers(offer)
                    backStack.add(Route.Passengers(offer.offerId))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue to passengers") }
        }
    }
}

@Composable
private fun BaggageRow(service: ServiceDto, quantity: Long, onQuantity: (Long) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(service.title.ifBlank { "Extra bag" }, style = MaterialTheme.typography.bodyLarge)
                Text(
                    formatMoney(service.totalAmount, service.totalCurrency) + " each",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CountStepper(
                label = "",
                count = quantity.toInt(),
                onCount = { onQuantity(it.toLong()) },
                min = 0,
                max = service.maxQuantity.toInt().coerceAtLeast(1),
            )
        }
    }
}

@Composable
private fun ExtrasSummary(
    offer: OfferDto,
    selectedBaggage: Map<String, Long>,
    selectedSeats: Map<String, com.vayunmathur.travel.network.SeatDto>,
) {
    val currency = offer.currency
    var total = 0.0
    selectedBaggage.forEach { (id, qty) ->
        val svc = offer.availableServices.find { it.id == id }
        total += (svc?.totalAmount?.toDoubleOrNull() ?: 0.0) * qty
    }
    selectedSeats.values.forEach { total += it.totalAmount?.toDoubleOrNull() ?: 0.0 }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Extras", style = MaterialTheme.typography.titleMedium)
        Text(
            formatMoney(total.toString(), currency),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
