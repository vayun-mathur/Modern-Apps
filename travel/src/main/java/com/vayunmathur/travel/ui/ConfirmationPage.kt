package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.TRIP_STATUS_FAILED
import com.vayunmathur.travel.data.TRIP_STATUS_PARTIAL
import com.vayunmathur.travel.data.TRIP_STATUS_PENDING_PAYMENT
import com.vayunmathur.travel.data.TRIP_STATUS_PROCESSING
import com.vayunmathur.travel.domain.isCheckoutUnexpired
import com.vayunmathur.travel.domain.isTripAwaitingPayment
import com.vayunmathur.travel.domain.isTripPollingStatus
import com.vayunmathur.travel.domain.tripStatusLabel
import com.vayunmathur.travel.platform.CardPaymentResult
import com.vayunmathur.travel.platform.rememberCardPayment
import com.vayunmathur.travel.util.CheckoutState
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.TripPollState
import com.vayunmathur.travel.util.confirmPaidTrip
import com.vayunmathur.travel.util.pollTrip
import com.vayunmathur.travel.util.recheckoutForStoredTrip
import androidx.compose.ui.res.stringResource

/**
 * Post-payment confirmation: polls the trip while pending/processing and
 * shows the final status (confirmed / processing / failed / partial) with the
 * booking reference. While pending_payment (and the checkout has not expired)
 * offers "Complete payment" (in-app card sheet) + "Refresh status".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.Confirmation,
) {
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    val trip = trips.find { it.orderId == route.orderId }
    val poll by viewModel.tripPoll.collectAsStateWithLifecycle()
    val checkout by viewModel.checkout.collectAsStateWithLifecycle()
    val tripId = trip?.tripId?.ifBlank { route.orderId } ?: route.orderId
    var paying by remember { mutableStateOf(false) }
    var presentWhenReady by remember { mutableStateOf(false) }
    val cardPayment = rememberCardPayment { result ->
        paying = false
        when (result) {
            CardPaymentResult.Completed -> viewModel.confirmPaidTrip(tripId)
            CardPaymentResult.Canceled -> Unit
            is CardPaymentResult.Failed -> AppMessages.show(result.message)
        }
    }

    LaunchedEffect(route.orderId) {
        viewModel.pollTrip(tripId)
    }

    // "Complete payment" fetched a fresh intent: present it once it arrives.
    LaunchedEffect(checkout, presentWhenReady) {
        val ready = (checkout as? CheckoutState.Ready)?.checkout ?: return@LaunchedEffect
        if (!presentWhenReady) return@LaunchedEffect
        presentWhenReady = false
        paying = cardPayment.present(ready.clientSecret, ready.publishableKey)
    }

    DetailScaffold(
        title = stringResource(confirmationTitle(trip?.status.orEmpty())),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (trip == null) {
                Text(stringResource(R.string.trip_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { backStack.reset(Route.Home) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.back_to_search))
                }
                return@Column
            }

            IconCheckCircle(
                modifier = Modifier.size(64.dp).padding(top = 8.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(confirmationHeadline(trip.status)),
                style = MaterialTheme.typography.headlineSmall
            )

            if (poll is TripPollState.Polling || trip.status == TRIP_STATUS_PROCESSING) {
                Text(
                    stringResource(R.string.confirming_payment),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow(
                        stringResource(R.string.booking_reference),
                        trip.bookingReference.ifBlank { "—" },
                        emphasize = true
                    )
                    HorizontalDivider()
                    DetailRow(stringResource(R.string.route_label), trip.route)
                    DetailRow(stringResource(R.string.departs), TravelViewModel.prettyDate(trip.departDate))
                    DetailRow(
                        stringResource(if (isTripAwaitingPayment(trip.status)) R.string.amount_due else R.string.amount_paid),
                        formatMoney(trip.amount, trip.currency)
                    )
                    DetailRow(stringResource(R.string.status_label), stringResource(tripStatusLabel(trip.status)))
                    if (trip.status == TRIP_STATUS_FAILED && trip.failureMessage.isNotBlank()) {
                        Text(
                            trip.failureMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (isTripAwaitingPayment(trip.status)) {
                (poll as? TripPollState.Error)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                (checkout as? CheckoutState.Error)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (isCheckoutUnexpired(trip.checkoutExpiresAt)) {
                    val loading = checkout is CheckoutState.Loading
                    Button(
                        onClick = {
                            val fresh = (checkout as? CheckoutState.Ready)?.checkout
                            if (fresh != null && viewModel.currentTrip?.tripId == trip.tripId.ifBlank { trip.orderId }) {
                                paying = cardPayment.present(fresh.clientSecret, fresh.publishableKey)
                            } else {
                                presentWhenReady = true
                                viewModel.recheckoutForStoredTrip(trip.orderId)
                            }
                        },
                        enabled = !loading && !paying,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.complete_payment)) }
                } else {
                    Text(
                        stringResource(R.string.payment_link_expired),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (isTripPollingStatus(trip.status) || trip.status == TRIP_STATUS_PARTIAL) {
                OutlinedButton(
                    onClick = { viewModel.pollTrip(trip.tripId.ifBlank { trip.orderId }) },
                    enabled = poll !is TripPollState.Polling,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.refresh_status)) }
            }

            Button(onClick = { backStack.reset(Route.Home) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(UiR.string.done))
            }
            OutlinedButton(
                onClick = { backStack.add(Route.OrderDetail(trip.orderId)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.view_full_itinerary)) }
            OutlinedButton(
                onClick = { backStack.reset(Route.Home, Route.Trips) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.view_my_trips)) }
        }
    }
}

private fun confirmationTitle(status: String): Int = when (status) {
    TRIP_STATUS_PENDING_PAYMENT -> R.string.status_payment_pending
    TRIP_STATUS_PROCESSING -> R.string.status_processing
    TRIP_STATUS_PARTIAL -> R.string.status_partial
    TRIP_STATUS_FAILED -> R.string.status_failed
    else -> R.string.booking_confirmed
}

private fun confirmationHeadline(status: String): Int = when (status) {
    TRIP_STATUS_PENDING_PAYMENT -> R.string.payment_pending_headline
    TRIP_STATUS_PROCESSING -> R.string.confirming_payment
    TRIP_STATUS_PARTIAL -> R.string.partial_headline
    TRIP_STATUS_FAILED -> R.string.booking_failed_headline
    else -> R.string.you_re_booked
}

@Composable
private fun DetailRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
