package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.domain.isCheckoutUnexpired
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.util.CheckoutState
import com.vayunmathur.travel.util.OrderDetailActions
import com.vayunmathur.travel.util.OrderDetailUiState
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.acceptNewPrice
import com.vayunmathur.travel.util.loadBookingDetail
import com.vayunmathur.travel.util.refreshBookingDetail
import androidx.compose.ui.res.stringResource

/** Binds [TravelViewModel] and the back stack to the stateless [OrderDetailScreen]. */
@Composable
fun OrderDetailPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.OrderDetail,
) {
    val state by viewModel.orderDetail.collectAsStateWithLifecycle()
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    val checkout by viewModel.checkout.collectAsStateWithLifecycle()
    val stored = trips.find { it.orderId == route.orderId }

    LaunchedEffect(route.orderId) {
        viewModel.loadBookingDetail(route.orderId)
    }

    val actions = remember(viewModel, backStack, route) {
        object : OrderDetailActions {
            override fun refresh() = viewModel.refreshBookingDetail(route.orderId)
            // The confirmation screen owns the in-app payment sheet and polling.
            override fun completePayment() {
                backStack.add(Route.Confirmation(route.orderId))
            }
            override fun acceptNewPrice() = viewModel.acceptNewPrice()
            override fun openChange(orderId: String) = backStack.add(Route.Change(orderId))
            override fun openCancel(orderId: String) = backStack.add(Route.Cancel(orderId))
            override fun back() = backStack.pop()
        }
    }

    OrderDetailScreen(
        state = OrderDetailUiState(
            loading = state.loading,
            error = state.error,
            order = state.order,
            refreshing = state.loading,
            checkout = checkout,
        ),
        pendingCheckout = stored?.status == "pending_payment" &&
            isCheckoutUnexpired(stored.checkoutExpiresAt),
        actions = actions,
    )
}

/**
 * A booked trip, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    state: OrderDetailUiState,
    actions: OrderDetailActions,
    pendingCheckout: Boolean = false,
) {
    DetailScaffold(
        title = stringResource(R.string.order),
        onNavigateBack = { actions.back() },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        val order = state.order
        if (order == null) {
            StatusBox(
                loading = state.loading,
                error = state.error,
                isEmpty = !state.loading && state.error == null,
                emptyMessage = stringResource(R.string.order_not_found),
            )
            return@DetailScaffold
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OrderRow(stringResource(R.string.booking_reference), order.bookingReference, emphasize = true)
                    HorizontalDivider()
                    OrderRow(stringResource(R.string.status_label), order.status.replaceFirstChar(Char::uppercase))
                    OrderRow(stringResource(R.string.total), formatMoney(order.totalAmount, order.currency))
                    if (order.passengerNames.isNotEmpty()) {
                        OrderRow(stringResource(R.string.passengers_label), order.passengerNames.joinToString(", "))
                    }
                    if (order.ticketNumbers.isNotEmpty()) {
                        OrderRow(stringResource(R.string.ticket_numbers), order.ticketNumbers.joinToString(", "))
                    }
                }
            }

            order.hotel?.let { hotel ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(hotel.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        if (hotel.address.isNotBlank()) {
                            Text(hotel.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (hotel.checkIn.isNotBlank() || hotel.checkOut.isNotBlank()) {
                            Text(
                                "${hotel.checkIn} → ${hotel.checkOut}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (hotel.roomName.isNotBlank() || hotel.boardName.isNotBlank()) {
                            Text(
                                listOf(hotel.roomName, hotel.boardName).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            order.slices.forEachIndexed { index, slice ->
                OrderSliceCard(
                    title = if (order.slices.size > 1) stringResource(R.string.flight_n, index + 1) else stringResource(R.string.itinerary),
                    slice = slice,
                )
            }

            if (pendingCheckout) {
                val loading = state.checkout is CheckoutState.Loading
                (state.checkout as? CheckoutState.Error)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                val fresh = (state.checkout as? CheckoutState.Ready)
                if (fresh?.checkout?.priceChanged == true) {
                    PriceChangeNotice(
                        original = fresh.checkout.originalAmount.orEmpty(),
                        current = fresh.checkout.totalAmount,
                        currency = fresh.checkout.currency,
                        accepted = fresh.acceptedNewPrice,
                        onAccept = { actions.acceptNewPrice() },
                    )
                }
                Button(
                    onClick = { actions.completePayment() },
                    enabled = !loading && (fresh == null || !fresh.checkout.priceChanged || fresh.acceptedNewPrice),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.complete_payment)) }
            }

            OutlinedButton(
                onClick = { actions.refresh() },
                enabled = !state.refreshing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.refresh_status)) }

            if (order.status != "cancelled") {
                if (order.canChange) {
                    OutlinedButton(
                        onClick = { actions.openChange(order.orderId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.change_flights)) }
                }
                if (order.canCancel) {
                    OutlinedButton(
                        onClick = { actions.openCancel(order.orderId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.cancel_order)) }
                }
            }
    }
}

@Composable
private fun PriceChangeNotice(
    original: String,
    current: String,
    currency: String,
    accepted: Boolean,
    onAccept: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.price_changed_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(
                    R.string.price_changed_summary,
                    if (original.isNotBlank()) formatMoney(original, currency) else "—",
                    formatMoney(current, currency)
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                com.vayunmathur.library.ui.Checkbox(checked = accepted, onCheckedChange = { if (it) onAccept() })
                Text(
                    stringResource(R.string.accept_new_price),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun OrderSliceCard(title: String, slice: SliceDto) {
    val is24 = rememberIs24Hour()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            slice.segments.forEach { seg ->
                Text(
                    "${seg.origin} ${formatTime(seg.departureAt, is24)}  →  ${seg.destination} ${formatTime(seg.arrivalAt, is24)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val carrier = listOf(seg.carrier, seg.flightNumber).filter { it.isNotBlank() }.joinToString(" ")
                if (carrier.isNotBlank()) {
                    Text(
                        carrier,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderRow(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
