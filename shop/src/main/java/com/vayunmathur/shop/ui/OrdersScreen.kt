package com.vayunmathur.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppPullToRefreshBox
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderShipment
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.domain.MerchantUrls
import com.vayunmathur.shop.domain.MoneyFormat
import com.vayunmathur.shop.platform.ShopViewModel
import com.vayunmathur.shop.platform.trackingStatusRes
import java.text.DateFormat
import java.time.OffsetDateTime
import java.util.Date

/**
 * Checkout history plus merchant-reported delivery tracking. The merchant's order page
 * stays the authoritative record; tracking comes from its order webhooks via the server.
 */
@Composable
fun OrdersScreen(viewModel: ShopViewModel) {
    val context = LocalContext.current
    AppScaffold(
        title = stringResource(R.string.orders),
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        AppPullToRefreshBox(
            isRefreshing = viewModel.refreshingTracking,
            onRefresh = { viewModel.refreshTracking(context) },
            modifier = Modifier.fillMaxSize().padding(padding),
            topClearance = 0.dp,
        ) {
            val orders = viewModel.orders
            if (orders.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.no_orders_yet),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                return@AppPullToRefreshBox
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(orders, key = { "${it.shopDomain}-${it.checkoutId}-${it.placedAtMs}" }) { order ->
                    OrderRow(order = order, onOpen = { url -> openContinueUrl(context, url) })
                }
            }
        }
    }
}

@Composable
private fun OrderRow(order: PlacedOrder, onOpen: (String) -> Unit) {
    val tracking = order.tracking
    val completed = order.status == "completed"
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.displayName.ifBlank { stringResource(R.string.orders) },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                tracking?.label?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            }
            Text(
                "${order.itemCount} item(s) · ${MoneyFormat.format(order.total)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    tracking != null -> stringResource(trackingStatusRes(tracking.status))
                    completed -> stringResource(R.string.order_placed)
                    else -> stringResource(R.string.checkout_pending)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (tracking?.status == "delivery_issue") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (tracking != null && tracking.refundedTotal > 0) {
                Text(
                    stringResource(R.string.tracking_refunded, MoneyFormat.format(Money(tracking.refundedTotal, tracking.currency))),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            tracking?.shipments?.forEach { ShipmentRow(it, onOpen) }
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(order.placedAtMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val orderUrl = tracking?.permalinkUrl?.let(MerchantUrls::httpsUrl)
                ?: order.continueUrl.takeIf { it.isNotBlank() }
            if (orderUrl != null) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = { onOpen(orderUrl) }) {
                    Text(stringResource(if (completed) R.string.view_order else R.string.continue_in_browser))
                }
            }
        }
    }
}

@Composable
private fun ShipmentRow(shipment: OrderShipment, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        val headline = listOfNotNull(
            shipment.carrier,
            stringResource(trackingStatusRes(shipment.status)),
            shipment.occurredAt.toDisplayTime(),
        ).joinToString(" · ")
        Text(headline, style = MaterialTheme.typography.bodyMedium)
        shipment.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val trackingUrl = shipment.trackingUrl?.let(MerchantUrls::httpsUrl)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            shipment.trackingNumber?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
            }
            if (trackingUrl != null) {
                OutlinedButton(onClick = { onOpen(trackingUrl) }) {
                    Text(stringResource(R.string.track_package))
                }
            }
        }
    }
}

private fun String.toDisplayTime(): String? = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(OffsetDateTime.parse(this).toInstant().toEpochMilli()))
}.getOrNull()
