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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DetailSection
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.CatalogOptionSelection
import com.vayunmathur.shop.data.ProductVariant
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.data.message
import com.vayunmathur.shop.domain.MerchantUrls
import com.vayunmathur.shop.domain.MoneyFormat
import com.vayunmathur.shop.network.ShopBackend
import com.vayunmathur.shop.network.ShopBackendException
import com.vayunmathur.shop.network.ShopHttpBackend
import com.vayunmathur.shop.platform.AppInit
import com.vayunmathur.shop.platform.ShopViewModel
import kotlinx.coroutines.CancellationException

/** Search snapshots paint immediately, but only a live, exact-variant refresh permits purchase. */
@Composable
fun ProductScreen(
    viewModel: ShopViewModel,
    variantId: String,
    platformId: String,
    shopDomain: String,
    onBack: () -> Unit,
    onCheckout: () -> Unit,
) {
    val backend: ShopBackend = ShopHttpBackend
    val context = LocalContext.current
    val messenger = rememberMessenger()
    var product by remember(variantId, platformId, shopDomain) {
        mutableStateOf(viewModel.results.firstOrNull { candidate ->
            candidate.platformId == platformId && candidate.variants.any {
                it.id == variantId && (candidate.shopDomain == shopDomain || it.shopDomain == shopDomain)
            }
        })
    }
    var selectedVariant by remember(variantId, platformId, shopDomain) { mutableStateOf(variantId) }
    var quantity by remember(variantId, platformId, shopDomain) { mutableStateOf(1) }
    var retry by remember(variantId, platformId, shopDomain) { mutableStateOf(0) }
    var refreshing by remember(variantId, platformId, shopDomain) { mutableStateOf(true) }
    var activeKey by remember(variantId, platformId, shopDomain) { mutableStateOf<Pair<String, String>?>(null) }
    var verifiedKey by remember(variantId, platformId, shopDomain) { mutableStateOf<Pair<String, String>?>(null) }
    var failure by remember(variantId, platformId, shopDomain) { mutableStateOf<String?>(null) }
    val selected = product?.variants?.firstOrNull { it.id == selectedVariant }
    val publisherDomain = selected?.shopDomain?.takeIf { it.isNotBlank() }
        ?: product?.shopDomain?.takeIf { it.isNotBlank() } ?: shopDomain
    val refreshKey = selectedVariant to publisherDomain
    val loading = refreshing || activeKey != refreshKey

    LaunchedEffect(variantId, platformId, refreshKey, retry) {
        activeKey = refreshKey
        refreshing = true
        verifiedKey = null
        failure = null
        val snapshot = product
        val publisherName = selected?.storeName?.takeIf { it.isNotBlank() }
            ?: snapshot?.takeIf { it.shopDomain == publisherDomain }?.storeName.orEmpty()
        try {
            if (publisherDomain.isBlank()) {
                failure = "The publishing store domain is missing. Cart actions are disabled."
            } else {
                AppInit.awaitReady()
                backend.getProduct(
                    publisherDomain, snapshot?.id?.takeIf { it.isNotBlank() } ?: selectedVariant,
                    selectedVariant, snapshot?.title, selected?.selectedOptions.orEmpty(),
                ).fold(
                    onSuccess = { live ->
                        if (live.variants.none { it.id == selectedVariant }) {
                            failure = "The store no longer lists this exact variant. Cart actions are disabled."
                        } else {
                            // Detail responses can narrow to one option. Other choices stay unverified until selected.
                            val choices = snapshot?.variants?.map { previous ->
                                live.variants.firstOrNull { it.id == previous.id }?.let { refreshed ->
                                    refreshed.copy(
                                        storeName = refreshed.storeName.ifBlank { previous.storeName },
                                        shopDomain = refreshed.shopDomain.ifBlank { previous.shopDomain },
                                        selectedOptions = refreshed.selectedOptions.ifEmpty { previous.selectedOptions },
                                    )
                                } ?: previous
                            }.orEmpty()
                            product = live.copy(
                                storeName = live.storeName.ifBlank { publisherName },
                                variants = choices + live.variants.filter { fresh -> choices.none { it.id == fresh.id } },
                            )
                            verifiedKey = refreshKey
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        failure = (error as? ShopBackendException)?.error?.message()
                            ?: "Couldn't refresh this product from the publishing store. Check your connection and retry."
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = (error as? ShopBackendException)?.error?.message()
                ?: "Couldn't refresh this product from the publishing store. Check your connection and retry."
        } finally {
            refreshing = false
        }
    }

    val verified = !loading && verifiedKey == refreshKey
    val canAdd = verified && product?.checkoutEligible == true && selected?.available == true
    val addToCart: () -> Unit = {
        val current = product
        val liveVariant = current?.variants?.firstOrNull { it.id == selectedVariant }
        if (canAdd && current != null && liveVariant != null) {
            val cartProduct = current.copy(shopDomain = liveVariant.shopDomain.takeIf { it.isNotBlank() } ?: current.shopDomain)
            viewModel.addToCart(cartProduct, selectedVariant, quantity, context)
        }
    }
    AppScaffold(
        title = product?.title ?: stringResource(R.string.app_name),
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val current = product
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (loading) {
                        CircularProgressIndicator()
                        Text("Checking live price and availability...")
                    } else {
                        Text(failure ?: "This product is unavailable. Please retry.")
                        Button(onClick = { retry += 1 }) { Text("Retry") }
                    }
                }
            }
        } else {
            ProductDetailContent(
                product = current,
                selectedVariantId = selectedVariant,
                quantity = quantity,
                loading = loading,
                verified = verified,
                failure = failure,
                onSelectVariant = { selectedVariant = it },
                onQuantityChange = { quantity = it },
                onRetry = { retry += 1 },
                onAddToCart = { if (canAdd) { addToCart(); messenger.show("Added to cart") } },
                onBuyNow = { if (canAdd) { addToCart(); onCheckout() } },
                onOpenUrl = { url -> MerchantUrls.httpsUrl(url)?.let { openContinueUrl(context, it) } },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

/** Stateless content shared with metadata fixtures; all effects belong to the screen host. */
@Composable
internal fun ProductDetailContent(
    product: UcpProduct,
    selectedVariantId: String,
    quantity: Int,
    loading: Boolean,
    verified: Boolean,
    failure: String?,
    onSelectVariant: (String) -> Unit,
    onQuantityChange: (Int) -> Unit,
    onRetry: () -> Unit,
    onAddToCart: () -> Unit,
    onBuyNow: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val variant = product.variants.firstOrNull { it.id == selectedVariantId }
    val storeDomain = variant?.shopDomain?.takeIf { it.isNotBlank() } ?: product.shopDomain
    val storeName = variant?.storeName?.takeIf { it.isNotBlank() }
        ?: product.storeName.takeIf { storeDomain == product.shopDomain }.orEmpty()
    val canAdd = verified && !loading && product.checkoutEligible && variant?.available == true
    val description = remember(product.descriptionHtml, product.description, onOpenUrl) {
        // Native conversion also handles merchants that put HTML fragments in the plain field.
        AnnotatedString.fromHtml(
            product.descriptionHtml.ifBlank { product.description },
            linkInteractionListener = LinkInteractionListener { link ->
                (link as? LinkAnnotation.Url)?.url?.let { url ->
                    MerchantUrls.httpsUrl(url)?.let(onOpenUrl)
                }
            },
        )
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        if (product.images.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(product.images, key = { it.url }) { image ->
                        AsyncImage(
                            model = image.url,
                            contentDescription = image.alt ?: product.title,
                            modifier = Modifier.fillParentMaxWidth(0.85f).height(280.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
        item {
            DetailSection("Publishing store") {
                Text(storeName.ifBlank { storeDomain.ifBlank { "Publishing store" } }, style = MaterialTheme.typography.titleMedium)
                if (storeName.isNotBlank()) {
                    Text(storeDomain.ifBlank { "Store domain unavailable" }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            DetailSection("Price") {
                Text(
                    variant?.price?.let { MoneyFormat.format(it) } ?: "Select an option to see its price",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (product.variants.size > 1) {
            item {
                DetailSection("Options") {
                    VariantPicker(product.variants, selectedVariantId, onSelectVariant)
                }
            }
        }
        item { ProductQuantityRow(quantity, onQuantityChange) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> Text("Checking live price and availability...")
                    !verified -> {
                        Text(failure ?: "Live price and availability are not verified. Cart actions are disabled.")
                        Button(onClick = onRetry) { Text("Retry") }
                    }
                    variant?.available != true -> Text("This variant is out of stock.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAddToCart, enabled = canAdd, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.add_to_cart))
                    }
                    Button(onClick = onBuyNow, enabled = canAdd, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.buy_now))
                    }
                }
                if ((!product.checkoutEligible || !verified && !loading) && MerchantUrls.httpsUrl(product.url) != null) {
                    Button(onClick = { onOpenUrl(product.url) }) { Text("View on merchant site") }
                    Text(
                        "Completes on the merchant site.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (description.isNotBlank()) {
            item {
                DetailSection("Details") {
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun VariantPicker(
    variants: List<ProductVariant>,
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
                    Text(variant.title.ifBlank { "Default" }, style = MaterialTheme.typography.bodyMedium)
                    if (!variant.available) {
                        Text("Out of stock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    MoneyFormat.format(variant.price),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSelect(variant.id) }, enabled = variant.available && !selected) {
                    Text(if (selected) "Selected" else "Select")
                }
            }
        }
    }
}
