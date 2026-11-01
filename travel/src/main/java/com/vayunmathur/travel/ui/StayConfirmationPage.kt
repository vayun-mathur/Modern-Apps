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
import com.vayunmathur.travel.util.refreshStoredTrip
import androidx.compose.ui.res.stringResource

/**
 * Stay confirmation: same pending → confirmed lifecycle as flights (polls
 * the trip via `GET /travel/trips/:id`), with "Complete payment" while
 * pending and checkout unexpired.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayConfirmationPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.StayConfirmation,
) {
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    val trip = trips.find { it.orderId == route.bookingId }
    val poll by viewModel.tripPoll.collectAsStateWithLifecycle()
    val checkout by viewModel.checkout.collectAsStateWithLifecycle()
    val tripId = trip?.tripId?.ifBlank { route.bookingId } ?: route.bookingId
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

    LaunchedEffect(route.bookingId) {
        viewModel.pollTrip(tripId)
    }

    LaunchedEffect(checkout, presentWhenReady) {
        val ready = (checkout as? CheckoutState.Ready)?.checkout ?: return@LaunchedEffect
        if (!presentWhenReady) return@LaunchedEffect
        presentWhenReady = false
        paying = cardPayment.present(ready.clientSecret, ready.publishableKey)
    }

    DetailScaffold(
        title = stringResource(if (trip != null && isTripAwaitingPayment(trip.status)) R.string.status_payment_pending else R.string.booking_confirmed),
        scrollBehavior = appBarScrollBehavior()
    ) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconCheckCircle(
                modifier = Modifier.size(64.dp).padding(top = 8.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(if (trip != null && isTripAwaitingPayment(trip.status)) R.string.payment_pending_headline else R.string.you_re_booked),
                style = MaterialTheme.typography.headlineSmall
            )
            if (trip != null) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StayDetailRow(stringResource(R.string.booking_reference), trip.bookingReference.ifBlank { "—" }, emphasize = true)
                        HorizontalDivider()
                        StayDetailRow(stringResource(R.string.hotel), trip.route)
                        StayDetailRow(stringResource(R.string.check_in), TravelViewModel.prettyDate(trip.departDate))
                        StayDetailRow(stringResource(R.string.amount_paid), formatMoney(trip.amount, trip.currency))
                        StayDetailRow(stringResource(R.string.status_label), stringResource(tripStatusLabel(trip.status)))
                    }
                }
                if (isTripAwaitingPayment(trip.status) && isCheckoutUnexpired(trip.checkoutExpiresAt)) {
                    (poll as? TripPollState.Error)?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    (checkout as? CheckoutState.Error)?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            val fresh = viewModel.stayCheckout?.takeIf { it.quoteId == tripId }
                            if (fresh != null) {
                                paying = cardPayment.present(fresh.clientSecret, fresh.publishableKey)
                            } else {
                                presentWhenReady = true
                                viewModel.recheckoutForStoredTrip(trip.orderId)
                            }
                        },
                        enabled = !paying && checkout !is CheckoutState.Loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.complete_payment)) }
                }
                if (isTripPollingStatus(trip.status)) {
                    OutlinedButton(
                        onClick = {
                            trip.let { viewModel.refreshStoredTrip(it.orderId) }
                            viewModel.pollTrip(trip.tripId.ifBlank { trip.orderId })
                        },
                        enabled = poll !is TripPollState.Polling,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.refresh_status)) }
                }
            }
            Button(onClick = { backStack.reset(Route.Home) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(UiR.string.done)) }
        }
    }
}

@Composable
private fun StayDetailRow(label: String, value: String, emphasize: Boolean = false) {
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
