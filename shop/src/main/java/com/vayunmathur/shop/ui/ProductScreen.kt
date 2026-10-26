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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DetailSection
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.data.message
import com.vayunmathur.shop.domain.MoneyFormat
import com.vayunmathur.shop.network.UcpCatalog
import com.vayunmathur.shop.network.UcpCheckoutApi
import com.vayunmathur.shop.network.UcpHttpException
import com.vayunmathur.shop.platform.ShopViewModel
import com.vayunmathur.shop.platform.AppInit

/**
 * Product detail: gallery, variant picker, add-to-cart. Price/availability are re-checked
 * live against the store on entry — search results may be stale and must never be trusted
 * for the buy decision.
 */
@Composable
fun ProductScreen(
    viewModel: ShopViewModel,
    variantId: String,
    platformId: String,
    shopDomain: String,
    onBack: () -> Unit,
    onCheckout: () -> Unit,
) {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    var product by remember(variantId, platformId, shopDomain) { mutableStateOf<UcpProduct?>(null) }
    var loading by remember(variantId, platformId, shopDomain) { mutableStateOf(true) }
    var refreshed by remember(variantId, platformId, shopDomain) { mutableStateOf(false) }
    var selectedVariant by remember(variantId, platformId, shopDomain) { mutableStateOf(variantId) }
    var quantity by remember(variantId, platformId, shopDomain) { mutableStateOf(1) }

    LaunchedEffect(variantId, platformId, shopDomain) {
        // Prefer the search snapshot for instant paint, then re-check live.
        viewModel.results.firstOrNull {
            it.platformId == platformId && it.shopDomain == shopDomain &&
                it.variants.any { v -> v.id == variantId }
        }?.let {
            product = it
        }
        AppInit.awaitReady()
        if (shopDomain.isNotBlank()) {
            val platform = UcpCheckoutApi.platformFor(platformId)
            UcpCatalog.lookup(platform, shopDomain, listOf(variantId)).fold(
                onSuccess = { products ->
                    products.firstOrNull()?.let {
                        product = it
                        refreshed = true
                    }
                    // The looked-up variant may differ from the tapped one; keep the
                    // selection only if the store still lists it.
                    if (products.flatMap { it.variants }.none { it.id == selectedVariant }) {
                        selectedVariant = products.firstOrNull()?.variants?.firstOrNull()?.id
                            ?: variantId
                    }
                },
                onFailure = {
                    val err = (it as? UcpHttpException)?.error
                    messenger.show(
                        err?.message() ?: "Couldn't refresh this product.",
                    )
                },
            )
        }
        loading = false
    }

    AppScaffold(
        title = product?.title ?: stringResource(R.string.app_name),
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val current = product
        if (loading && current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@AppScaffold
        }
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.check_connection_retry))
            }
            return@AppScaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val images = current.images
            if (images.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(images, key = { it.url }) { image ->
                            AsyncImage(
                                model = image.url,
                                contentDescription = image.alt ?: current.title,
                                modifier = Modifier
                                    .fillParentMaxWidth(0.85f)
                                    .height(280.dp),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }
            item {
                DetailSection("Price") {
                    val variant = current.variants.firstOrNull { it.id == selectedVariant }
                    Text(
                        MoneyFormat.format(variant?.price ?: current.priceRange),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (current.variants.size > 1) {
                item {
                    DetailSection("Options") {
                        VariantPicker(
                            variants = current.variants,
                            selectedId = selectedVariant,
                            onSelect = { selectedVariant = it },
                        )
                    }
                }
            }
            if (current.description.isNotBlank()) {
                item {
                    DetailSection("Details") {
                        Text(
                            current.description,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item {
                QuantityRow(quantity = quantity, onChange = { quantity = it })
            }
            item {
                val canAdd = refreshed && !loading && current.checkoutEligible &&
                    current.variants.firstOrNull { it.id == selectedVariant }?.available == true
                if (!current.checkoutEligible && current.url.isNotBlank()) {
                    Button(onClick = { openContinueUrl(context, current.url) }) {
                        Text("View on merchant site")
                    }
                }
                if (!refreshed) Text("Live price could not be verified. Cart actions are disabled.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.addToCart(current, selectedVariant, quantity, context)
                            messenger.show("Added to cart")
                        },
                        enabled = canAdd,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.add_to_cart))
                    }
                    Button(
                        onClick = {
                            viewModel.addToCart(current, selectedVariant, quantity, context)
                            onCheckout()
                        },
                        enabled = canAdd,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.buy_now))
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (!current.checkoutEligible && current.url.isNotBlank()) {
                    Text(
                        "Completes on the merchant site.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VariantPicker(
    variants: List<com.vayunmathur.shop.data.ProductVariant>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        variants.forEach { variant ->
            val selected = variant.id == selectedId
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        variant.title.ifBlank { "Default" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!variant.available) {
                        Text(
                            "Out of stock",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    MoneyFormat.format(variant.price),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSelect(variant.id) },
                    enabled = variant.available && !selected,
                ) {
                    Text(if (selected) "Selected" else "Select")
                }
            }
        }
    }
}

@Composable
private fun QuantityRow(quantity: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Quantity", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Button(onClick = { onChange((quantity - 1).coerceAtLeast(1)) }) { Text("−") }
        Spacer(Modifier.width(8.dp))
        Text("$quantity", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Button(onClick = { onChange((quantity + 1).coerceAtMost(99)) }) { Text("+") }
    }
}
