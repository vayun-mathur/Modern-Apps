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
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.platform.TravelViewModel
import com.vayunmathur.travel.platform.confirmCancellation
import com.vayunmathur.travel.platform.quoteCancellation
import com.vayunmathur.travel.platform.resetCancellation
import androidx.compose.ui.res.stringResource

/**
 * Shared flight/hotel cancellation: preview (refund, penalty,
 * commitable/message) → confirm → poll until succeeded/failed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CancellationPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.Cancel,
) {
    val state by viewModel.cancellation.collectAsStateWithLifecycle()

    LaunchedEffect(route.orderId) { viewModel.quoteCancellation(route.orderId) }

    DetailScaffold(
        title = stringResource(R.string.cancel_order),
        onNavigateBack = { backStack.pop() },
    ) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                state.done -> {
                    IconCheckCircle(
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(R.string.order_cancelled), style = MaterialTheme.typography.headlineSmall)
                    state.quote?.let {
                        Text(
                            stringResource(R.string.refund_of_issued_to_your, formatMoney(it.refundAmount, it.refundCurrency), it.refundTo.ifBlank { stringResource(R.string.original_payment) }),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.resetCancellation()
                            backStack.reset(Route.FlightsHome, Route.Trips)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(UiR.string.done)) }
                }
                state.quote != null -> {
                    val quote = state.quote!!
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.cancel_this_order), style = MaterialTheme.typography.titleMedium)
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.refund), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    formatMoney(quote.refundAmount, quote.refundCurrency),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (!quote.penaltyAmount.isNullOrBlank()) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.penalty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        formatMoney(quote.penaltyAmount, quote.refundCurrency),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.refunded_to_your, quote.refundTo.ifBlank { stringResource(R.string.original_payment) }),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!quote.message.isNullOrBlank()) {
                                Text(
                                    quote.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.polling) {
                        Text(
                            stringResource(R.string.cancellation_processing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { viewModel.confirmCancellation(route.orderId) },
                        enabled = !state.confirming && !state.polling && quote.commitable,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.confirming) stringResource(R.string.cancelling) else stringResource(R.string.confirm_cancellation)) }
                }
                else -> StatusBox(
                    loading = state.loading,
                    error = state.error,
                    isEmpty = !state.loading && state.error == null,
                    emptyMessage = "This order can't be cancelled.",
                )
            }
        }
    }
}
