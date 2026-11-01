package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R
import com.vayunmathur.library.ui.R as UiR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.platform.CardPaymentResult
import com.vayunmathur.travel.platform.rememberCardPayment
import com.vayunmathur.travel.util.CheckoutState
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.acceptNewPrice
import com.vayunmathur.travel.util.confirmPaidTrip
import com.vayunmathur.travel.util.startCheckout
import androidx.compose.ui.res.stringResource

/**
 * Checkout screen: shows the total (and the original vs new price when it
 * changed, requiring an explicit accept), then "Pay securely" opens the
 * in-app card sheet. On success the trip is confirmed server-side and the app
 * moves to the confirmation screen, which polls to the final status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    @Suppress("UNUSED_PARAMETER") route: Route.Payment,
) {
    val review by viewModel.review.collectAsStateWithLifecycle()
    val checkout by viewModel.checkout.collectAsStateWithLifecycle()
    var paying by remember { mutableStateOf(false) }
    val cardPayment = rememberCardPayment { result ->
        paying = false
        val tripId = viewModel.currentTrip?.tripId.orEmpty()
        when (result) {
            CardPaymentResult.Completed -> if (tripId.isNotBlank()) {
                viewModel.confirmPaidTrip(tripId)
                backStack.add(Route.Confirmation(tripId))
            }
            CardPaymentResult.Canceled -> Unit
            is CardPaymentResult.Failed -> AppMessages.show(result.message)
        }
    }

    LaunchedEffect(Unit) {
        if (checkout is CheckoutState.Idle) viewModel.startCheckout()
    }

    val ready = (checkout as? CheckoutState.Ready)?.checkout
    val accepted = (checkout as? CheckoutState.Ready)?.acceptedNewPrice == true

    AppScaffold(
        title = stringResource(R.string.payment),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val offer = review.offer
        val loading = checkout is CheckoutState.Loading
        // Single-pane payment form; letterboxed on expanded windows.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val total = ready?.totalAmount ?: offer?.totalAmount
            val currency = ready?.currency ?: offer?.currency ?: "USD"
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.total_due), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (total != null) formatMoney(total, currency) else "—",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (ready?.priceChanged == true) {
                PriceChangeCard(
                    original = ready.originalAmount.orEmpty(),
                    current = ready.totalAmount,
                    currency = currency,
                    accepted = accepted,
                    onAccept = { viewModel.acceptNewPrice() },
                )
            }

            Text(
                stringResource(R.string.secure_checkout_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            (checkout as? CheckoutState.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { viewModel.startCheckout() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(UiR.string.retry)) }
            }

            val payEnabled = ready != null && !loading && !paying &&
                (!ready.priceChanged || accepted)
            Button(
                onClick = {
                    val r = ready ?: return@Button
                    paying = cardPayment.present(r.clientSecret, r.publishableKey)
                    if (!paying) AppMessages.show("Payment is unavailable right now. Please try again.")
                },
                enabled = payEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.preparing_checkout))
                } else {
                    Text(stringResource(R.string.pay_securely))
                }
            }
        }
        }
    }
}

@Composable
private fun PriceChangeCard(
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.original_price),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (original.isNotBlank()) formatMoney(original, currency) else "—",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.new_price),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatMoney(current, currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = accepted, onCheckedChange = { if (it) onAccept() })
                Text(
                    stringResource(R.string.accept_new_price),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
