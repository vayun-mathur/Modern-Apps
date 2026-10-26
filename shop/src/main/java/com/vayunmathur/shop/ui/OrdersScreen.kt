package com.vayunmathur.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.domain.MoneyFormat
import com.vayunmathur.shop.platform.ShopViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Local pending checkout history. Reopening a link does not confirm payment;
 * authoritative order status must be checked on the merchant site.
 */
@Composable
fun OrdersScreen(viewModel: ShopViewModel) {
    val context = LocalContext.current
    AppScaffold(
        title = stringResource(R.string.orders),
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val orders = viewModel.orders
        if (orders.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.no_orders_yet),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            return@AppScaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            items(orders, key = { "${it.checkoutId}-${it.placedAtMs}" }) { order ->
                OrderRow(order = order, onOpen = {
                    if (order.continueUrl.isNotBlank()) openContinueUrl(context, order.continueUrl)
                })
            }
        }
    }
}

@Composable
private fun OrderRow(order: PlacedOrder, onOpen: () -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${order.itemCount} item(s) · ${MoneyFormat.format(order.total)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(stringResource(R.string.checkout_pending), style = MaterialTheme.typography.labelMedium)
                if (order.shopDomain.isNotBlank()) Text(order.shopDomain, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(order.placedAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (order.continueUrl.isNotBlank()) {
                Button(onClick = onOpen) {
                    Text(stringResource(R.string.continue_in_browser))
                }
            }
        }
    }
}
