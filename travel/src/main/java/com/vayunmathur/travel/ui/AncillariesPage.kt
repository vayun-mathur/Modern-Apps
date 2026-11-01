package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R
import com.vayunmathur.library.ui.R as UiR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRemove
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.ServiceDto
import com.vayunmathur.travel.util.AncillariesState
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.initPassengers
import com.vayunmathur.travel.util.retrySeatMaps
import com.vayunmathur.travel.util.saveAncillaries
import com.vayunmathur.travel.util.setBaggageQuantity
import com.vayunmathur.travel.util.setExtraQuantity
import androidx.compose.ui.res.stringResource

/**
 * Extras step: bag services + seat maps off the created trip. Saving posts
 * the selections, then the flow moves to checkout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AncillariesPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    @Suppress("UNUSED_PARAMETER") route: Route.Ancillaries,
) {
    val state by viewModel.ancillaries.collectAsStateWithLifecycle()
    val review by viewModel.review.collectAsStateWithLifecycle()
    val selectedBaggage by viewModel.selectedBaggage.collectAsStateWithLifecycle()
    val selectedExtras by viewModel.selectedExtras.collectAsStateWithLifecycle()
    val selectedSeats by viewModel.selectedSeats.collectAsStateWithLifecycle()
    val offer = review.offer
    val services = state.ancillaries?.services.orEmpty()

    AppScaffold(
        title = stringResource(R.string.add_extras),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        if (offer == null) {
            StatusBox(loading = false, error = null, isEmpty = true, emptyMessage = "No offer selected.")
            return@AppScaffold
        }
        if (state.loading && services.isEmpty()) {
            StatusBox(loading = true, error = null, isEmpty = false, modifier = Modifier.padding(padding))
            return@AppScaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val passengerCount = offer.passengers.size.coerceAtLeast(1)
            val multiPassenger = passengerCount > 1
            val bags = services.filter { it.type == "baggage" }
            if (bags.isNotEmpty()) {
                SectionHeader(stringResource(R.string.extra_baggage))
                bags.forEach { svc ->
                    BaggageRow(
                        service = svc,
                        quantity = selectedBaggage[svc.id] ?: 0L,
                        passengerLabel = if (multiPassenger) passengerLabel(offer, svc.passengerIds.firstOrNull()) else null,
                        onQuantity = { viewModel.setBaggageQuantity(svc.id, it) },
                    )
                }
            }

            val extras = services.filter { it.type != "baggage" && it.type != "seat" }
            if (extras.isNotEmpty()) {
                SectionHeader(stringResource(R.string.extras))
                extras.forEach { svc ->
                    ExtraServiceRow(
                        service = svc,
                        quantity = selectedExtras[svc.id] ?: 0L,
                        passengerLabel = if (multiPassenger) passengerLabel(offer, svc.passengerIds.firstOrNull()) else null,
                        onQuantity = { viewModel.setExtraQuantity(svc.id, it) },
                    )
                }
            }

            SectionHeader(stringResource(R.string.seats_1))
            if (state.seatMapsLoading) {
                SeatMapsPendingCard { viewModel.retrySeatMaps() }
            }
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
                                        stringResource(R.string.seats, chosenLabels),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { backStack.add(Route.SeatMap(offer.offerId, seg.id)) },
                                enabled = seg.id.isNotBlank(),
                            ) { Text(if (segSeats.isNotEmpty()) stringResource(R.string.change) else stringResource(R.string.choose)) }
                        }
                    }
                }
            }

            HorizontalDivider()
            ExtrasSummary(offer.currency, services, selectedBaggage, selectedExtras, selectedSeats)

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    viewModel.saveAncillaries { backStack.add(Route.Payment(offer.offerId)) }
                },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.continue_to_checkout)) }
        }
    }
}

@Composable
private fun BaggageRow(
    service: ServiceDto,
    quantity: Long,
    passengerLabel: String?,
    onQuantity: (Long) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(service.title.ifBlank { stringResource(R.string.extra_bag) }, style = MaterialTheme.typography.bodyLarge)
                Text(
                    formatMoney(service.totalAmount, service.totalCurrency) + stringResource(R.string.each),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (passengerLabel != null) {
                    Text(
                        stringResource(R.string.for_passenger, passengerLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Compact stepper (must not fill width, or it squeezes the title column).
            val max = service.maxQuantity.toInt().coerceAtLeast(1)
            IconButton(
                onClick = { onQuantity((quantity - 1).coerceAtLeast(0)) },
                enabled = quantity > 0,
            ) { IconRemove() }
            Text("$quantity", style = MaterialTheme.typography.titleMedium)
            IconButton(
                onClick = { onQuantity((quantity + 1).coerceAtMost(max.toLong())) },
                enabled = quantity < max,
            ) { IconAdd() }
        }
    }
}

/** A non-baggage service (CFAR, priority boarding, …) as an on/off toggle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtraServiceRow(
    service: ServiceDto,
    quantity: Long,
    passengerLabel: String?,
    onQuantity: (Long) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(service.title.ifBlank { stringResource(R.string.extra) }, style = MaterialTheme.typography.bodyLarge)
                Text(
                    formatMoney(service.totalAmount, service.totalCurrency),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (passengerLabel != null) {
                    Text(
                        stringResource(R.string.for_passenger, passengerLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            com.vayunmathur.library.ui.Switch(
                checked = quantity > 0,
                onCheckedChange = { onQuantity(if (it) 1L else 0L) },
            )
        }
    }
}

/** A human label for a passenger id, e.g. "Adult 1" / "Child 2". */
private fun passengerLabel(offer: com.vayunmathur.travel.network.OfferDto, passengerId: String?): String? {
    if (passengerId == null) return null
    val index = offer.passengers.indexOfFirst { it.id == passengerId }
    if (index < 0) return null
    val kind = offer.passengers[index].type
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
        .ifBlank { "Passenger" }
    return "$kind ${index + 1}"
}

@Composable
private fun SeatMapsPendingCard(onRetry: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.seat_maps_still_loading),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(UiR.string.retry))
            }
        }
    }
}

@Composable
private fun ExtrasSummary(
    currency: String,
    services: List<ServiceDto>,
    selectedBaggage: Map<String, Long>,
    selectedExtras: Map<String, Long>,
    selectedSeats: Map<String, com.vayunmathur.travel.network.SeatElementDto>,
) {
    var total = 0.0
    (selectedBaggage + selectedExtras).forEach { (id, qty) ->
        val svc = services.find { it.id == id }
        total += (svc?.totalAmount?.toDoubleOrNull() ?: 0.0) * qty
    }
    selectedSeats.values.forEach { total += it.totalAmount?.toDoubleOrNull() ?: 0.0 }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.extras), style = MaterialTheme.typography.titleMedium)
        Text(
            formatMoney(total.toString(), currency),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
