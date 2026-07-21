package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.util.TravelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CancellationPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.Cancel,
) {
    val state by viewModel.cancellation.collectAsStateWithLifecycle()

    LaunchedEffect(route.orderId) { viewModel.quoteCancellation(route.orderId) }

    Scaffold(
        topBar = {
            TopBar(onBack = { backStack.pop() })
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                state.done -> {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                    )
                    Text("Order cancelled", style = MaterialTheme.typography.headlineSmall)
                    state.quote?.let {
                        Text(
                            "Refund of ${formatMoney(it.refundAmount, it.refundCurrency)} issued to your ${it.refundTo.ifBlank { "balance" }}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.resetCancellation()
                            backStack.reset(Route.Home, Route.Trips)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Done") }
                }
                state.quote != null -> {
                    val quote = state.quote!!
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Cancel this order?", style = MaterialTheme.typography.titleMedium)
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Refund", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    formatMoney(quote.refundAmount, quote.refundCurrency),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                "Refunded to your ${quote.refundTo.ifBlank { "balance" }}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { viewModel.confirmCancellation(route.orderId) },
                        enabled = !state.confirming,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.confirming) "Cancelling…" else "Confirm cancellation") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(onBack: () -> Unit) {
    com.vayunmathur.library.ui.TopAppBar(
        title = { Text("Cancel order") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}
