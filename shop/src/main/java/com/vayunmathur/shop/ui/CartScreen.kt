package com.vayunmathur.shop.ui

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.domain.MoneyFormat
import com.vayunmathur.shop.platform.ShopViewModel

/**
 * Cart: local lines grouped by shop, one checkout per shop at placement. Prices shown
 * are the add-time snapshot; the store re-prices authoritatively at checkout creation.
 */
@Composable
fun CartScreen(
    viewModel: ShopViewModel,
    onBack: (() -> Unit)?,
    onCheckout: () -> Unit,
) {
    val messenger = rememberMessenger()
    LaunchedEffect(viewModel.checkoutError) {
        viewModel.checkoutError?.let { error ->
            messenger.show(error)
            viewModel.dismissCheckoutError()
        }
    }
    // Persistence context hoisted once: rows stay stateless and context-free.
    val context = androidx.compose.ui.platform.LocalContext.current
    AppScaffold(
        title = stringResource(R.string.cart),
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        CartContent(
            cart = viewModel.cart,
            placing = viewModel.placingCheckout,
            onRemove = { viewModel.removeLine(it, context) },
            onCheckout = onCheckout,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/**
 * The cart page without ViewModel or scaffold, so previews render it with fixture
 * lines. Grouping by store is load-bearing (one UCP checkout per store), not cosmetic.
 */
@Composable
internal fun CartContent(
    cart: List<CartLine>,
    modifier: Modifier = Modifier,
    placing: Boolean = false,
    onRemove: (CartLine) -> Unit = {},
    onCheckout: () -> Unit = {},
) {
    if (cart.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconShoppingCart(
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.cart_empty),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        return
    }
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            // One section per store: each becomes its own checkout (UCP checkouts
            // are single-merchant), so the grouping is load-bearing, not cosmetic.
            val byShop = cart.groupBy { it.shopDomain.ifBlank { "Unknown store" } }
            byShop.forEach { (shop, lines) ->
                item(key = "header-$shop") {
                    Text(
                        shop,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(lines, key = { "${it.platformId}|${it.shopDomain}|${it.variantId}" }) { line ->
                    CartRow(line = line, onRemove = { onRemove(line) })
                }
            }
        }
        CartFooter(
            cart = cart,
            placing = placing,
            onCheckout = onCheckout,
        )
    }
}

@Composable
private fun CartRow(line: CartLine, onRemove: () -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (line.imageUrl.isNotEmpty()) {
                AsyncImage(
                    model = line.imageUrl,
                    contentDescription = line.productTitle,
                    modifier = Modifier.size(64.dp),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(line.productTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                if (line.variantTitle.isNotBlank()) {
                    Text(
                        line.variantTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${MoneyFormat.format(line.unitPrice)} × ${line.quantity}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Button(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun CartFooter(cart: List<CartLine>, placing: Boolean, onCheckout: () -> Unit) {
    val totals = cart.groupBy { it.unitPrice.currency }.map { (currency, lines) ->
        com.vayunmathur.shop.data.Money(
            lines.sumOf { it.unitPrice.amount * it.quantity }, currency,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.estimated_subtotal), style = MaterialTheme.typography.labelSmall)
            totals.forEach { total ->
                Text(MoneyFormat.format(total), style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onCheckout, enabled = !placing) {
            if (placing) {
                CircularProgressIndicator()
            } else {
                Text(stringResource(R.string.checkout))
            }
        }
    }
}

/**
 * Opens a `continue_url` (or any merchant URL) in a Custom Tab, falling back to a plain
 * view intent. Used for Phase-1 checkout completion and buy-by-URL fallback.
 */
fun openContinueUrl(context: Context, url: String) {
    if (com.vayunmathur.shop.domain.MerchantUrls.httpsUrl(url) == null) {
        AppMessages.show("Payment link is missing. Please try again.")
        return
    }
    runCatching {
        val params = CustomTabColorSchemeParams.Builder().build()
        val intent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(params)
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
        intent.launchUrl(context, Uri.parse(url))
    }.onFailure {
        runCatching {
            val view = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                Uri.parse(url),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(view)
        }.onFailure {
            AppMessages.show("No app can open the payment page.")
        }
    }
}
