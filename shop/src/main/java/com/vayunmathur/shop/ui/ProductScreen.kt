package com.vayunmathur.shop.ui

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.vayunmathur.library.ui.DetailSection
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.R as UiR
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
@Suppress("TooGenericExceptionCaught") // Refresh failures all show the same retry message.
fun ProductScreen(
    viewModel: ShopViewModel,
    variantId: String,
    platformId: String,
    shopDomain: String,
    onBack: () -> Unit,
    onPayInApp: (platformId: String, shopDomain: String) -> Unit,
) {
    val backend: ShopBackend = ShopHttpBackend
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val messenger = rememberMessenger()
    // Captured at composition: refresh failures are set inside LaunchedEffect.
    val domainMissingMessage = stringResource(R.string.product_domain_missing)
    val variantMissingMessage = stringResource(R.string.product_variant_missing)
    val refreshFailedMessage = stringResource(R.string.product_refresh_failed)
    val addedToCartMessage = stringResource(R.string.added_to_cart)
    var product by remember(variantId, platformId, shopDomain) {
        mutableStateOf((viewModel.results + viewModel.linkedProducts + listOfNotNull(viewModel.catalogProduct(variantId))).firstOrNull { candidate ->
            candidate.platformId == platformId && candidate.variants.any {
                it.id == variantId && (candidate.shopDomain == shopDomain || it.shopDomain == shopDomain)
            }
        })
    }
    var selectedVariant by remember(variantId, platformId, shopDomain) { mutableStateOf(variantId) }
    var quantity by remember(variantId, platformId, shopDomain) { mutableIntStateOf(1) }
    var retry by remember(variantId, platformId, shopDomain) { mutableIntStateOf(0) }
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
                failure = domainMissingMessage
            } else {
                AppInit.awaitReady()
                backend.getProduct(
                    publisherDomain, snapshot?.id?.takeIf { it.isNotBlank() } ?: selectedVariant,
                    selectedVariant, snapshot?.title, selected?.selectedOptions.orEmpty(),
                ).fold(
                    onSuccess = { live ->
                        if (live.variants.none { it.id == selectedVariant }) {
                            failure = variantMissingMessage
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
                            ?: refreshFailedMessage
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = (error as? ShopBackendException)?.error?.message()
                ?: refreshFailedMessage
        } finally {
            refreshing = false
        }
    }

    val verified = !loading && verifiedKey == refreshKey
    val canAdd = verified && product?.checkoutEligible == true && selected?.available == true
    /** Returns the (platformId, shopDomain) the line was added under, or null. */
    val addToCart: () -> Pair<String, String>? = add@{
        val current = product
        val liveVariant = current?.variants?.firstOrNull { it.id == selectedVariant }
        if (!canAdd || current == null || liveVariant == null) return@add null
        val cartProduct = current.copy(shopDomain = liveVariant.shopDomain.takeIf { it.isNotBlank() } ?: current.shopDomain)
        viewModel.addToCart(cartProduct, selectedVariant, quantity, context)
        cartProduct.platformId to cartProduct.shopDomain
    }
    AppScaffold(
        title = product?.title ?: stringResource(R.string.app_name),
        onNavigateBack = onBack,
        isRefreshing = loading,
    ) { padding ->
        val current = product
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (loading) {
                        Text(stringResource(R.string.product_checking))
                    } else {
                        Text(failure ?: stringResource(R.string.product_unavailable))
                        Button(onClick = { retry += 1 }) { Text(stringResource(UiR.string.retry)) }
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
                onAddToCart = { if (canAdd) { addToCart(); messenger.show(addedToCartMessage) } },
                onBuyNow = {
                    addToCart()?.let { (store, shop) ->
                        viewModel.checkoutStore(
                            store, shop, context, dark,
                            onReady = { onPayInApp(store, shop) },
                            onError = messenger::show,
                        )
                    }
                },
                onOpenUrl = { url -> MerchantUrls.httpsUrl(url)?.let { openContinueUrl(context, it) } },
                modifier = Modifier.fillMaxSize().padding(padding),
                startingCheckout = viewModel.startingCheckout != null,
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
    startingCheckout: Boolean = false,
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
            DetailSection(stringResource(R.string.store_label)) {
                Text(
                    storeName.ifBlank { storeDomain.ifBlank { stringResource(R.string.store_label) } },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        item {
            DetailSection(stringResource(R.string.price_label)) {
                Text(
                    variant?.price?.let { MoneyFormat.format(it) } ?: stringResource(R.string.select_option_price),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (product.variants.size > 1) {
            item {
                DetailSection(stringResource(R.string.options_label)) {
                    VariantPicker(product.variants, selectedVariantId, onSelectVariant)
                }
            }
        }
        item { ProductQuantityRow(quantity, onQuantityChange) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    loading -> Text(stringResource(R.string.product_checking))
                    !verified -> {
                        Text(failure ?: stringResource(R.string.product_not_verified))
                        Button(onClick = onRetry) { Text(stringResource(UiR.string.retry)) }
                    }
                    variant?.available != true -> Text(stringResource(R.string.product_variant_out_of_stock))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAddToCart, enabled = canAdd, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.add_to_cart))
                    }
                    Button(onClick = onBuyNow, enabled = canAdd && !startingCheckout, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.buy_now))
                    }
                }
                if ((!product.checkoutEligible || !verified && !loading) && MerchantUrls.httpsUrl(product.url) != null) {
                    Button(onClick = { onOpenUrl(product.url) }) { Text(stringResource(R.string.view_on_merchant_site)) }
                    Text(
                        stringResource(R.string.completes_on_merchant_site),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (description.isNotBlank()) {
            item {
                DetailSection(stringResource(R.string.details_label)) {
                    Text(description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

