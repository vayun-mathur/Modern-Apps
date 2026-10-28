package com.vayunmathur.shop.platform

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.NativeCheckoutState
import com.vayunmathur.shop.domain.NativeCheckoutCoordinator
import com.vayunmathur.shop.domain.PendingCheckoutSink
import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.OrderStore
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.ShopCartStore
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.data.message
import com.vayunmathur.shop.domain.MerchantUrls
import com.vayunmathur.shop.network.ShopBackend
import com.vayunmathur.shop.network.ShopBackendException
import com.vayunmathur.shop.network.ShopHttpBackend
import com.vayunmathur.shop.network.splitByShop
import kotlinx.coroutines.launch

/** Session catalog state plus persisted cart and pending checkout history. */
class ShopViewModel(
    private val backend: ShopBackend = ShopHttpBackend,
) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var searchShopDomain by mutableStateOf("")
        private set
    var results by mutableStateOf<List<UcpProduct>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    var cart by mutableStateOf<List<CartLine>>(emptyList())
        private set
    var placingCheckout by mutableStateOf(false)
        private set
    var checkoutError by mutableStateOf<String?>(null)
        private set
    var browserCheckouts by mutableStateOf<List<UcpCheckout>>(emptyList())
        private set
    var orders by mutableStateOf<List<PlacedOrder>>(emptyList())
        private set
    var nativeCheckouts by mutableStateOf<List<NativeCheckoutState>>(emptyList())
        private set
    private val nativeCheckout = NativeCheckoutCoordinator(changed = { states ->
        nativeCheckouts = states
        browserCheckouts = states.mapNotNull { it.checkout }.filter { MerchantUrls.httpsUrl(it.continueUrl) != null }
    })

    fun editCheckoutInputs(platformId: String, shop: String, inputs: CheckoutInputs) {
        nativeCheckout.edit(platformId, shop, inputs)
    }

    fun refreshCheckoutExpiry() { nativeCheckout.refreshExpiry() }

    fun reconcileCheckout(platformId: String, shop: String) {
        viewModelScope.launch { nativeCheckout.reconcile(platformId, shop) }
    }

    fun prepareMerchantCheckout(platformId: String, shop: String, context: Context) {
        if (placingCheckout) return
        val lines = cart.filter { it.platformId == platformId && it.shopDomain == shop }.toList()
        viewModelScope.launch {
            AppInit.awaitReady()
            nativeCheckout.submit(platformId, shop, lines, PendingCheckoutSink { OrderStore.add(context, it) })
            nativeCheckout.cartChanged(cart)
            orders = OrderStore.getAll(context)
        }
    }

    fun updateQuery(value: String) { query = value }
    fun updateShopDomain(value: String) { searchShopDomain = value }

    fun loadCart(context: Context) {
        viewModelScope.launch { cart = ShopCartStore.getAll(context) }
    }

    fun loadOrders(context: Context) {
        viewModelScope.launch { orders = OrderStore.getAll(context) }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        val shop = if (searchShopDomain.isBlank()) null else MerchantUrls.domain(searchShopDomain)
        if (searchShopDomain.isNotBlank() && shop == null) {
            searchError = "Enter a public HTTPS merchant domain without a path."
            return
        }
        searching = true
        searchError = null
        results = emptyList()
        viewModelScope.launch {
            try {
                AppInit.awaitReady()
                backend.search(q, shop).fold(
                    onSuccess = { results = it },
                    onFailure = { searchError = it.toBackendMessage() },
                )
            } finally {
                searching = false
            }
        }
    }

    fun addToCart(product: com.vayunmathur.shop.data.UcpProduct, variantId: String, quantity: Int, context: Context) {
        val variant = product.variants.firstOrNull { it.id == variantId } ?: return
        if (!variant.available || !product.checkoutEligible || quantity !in 1..99) return
        val existing = cart.indexOfFirst {
            it.variantId == variantId && it.platformId == product.platformId &&
                it.shopDomain == product.shopDomain
        }
        cart = if (existing >= 0) {
            cart.toMutableList().also {
                it[existing] = it[existing].copy(quantity = (it[existing].quantity + quantity).coerceAtMost(99))
            }
        } else {
            cart + CartLine(
                variantId = variant.id,
                productTitle = product.title,
                variantTitle = variant.title,
                unitPrice = variant.price,
                platformId = product.platformId,
                shopDomain = product.shopDomain,
                quantity = quantity,
            )
        }
        browserCheckouts = emptyList()
        nativeCheckout.cartChanged(cart)
        ShopCartStore.save(context, cart)
    }

    fun removeLine(line: CartLine, context: Context) {
        cart = cart.filterNot {
            it.variantId == line.variantId && it.platformId == line.platformId && it.shopDomain == line.shopDomain
        }
        browserCheckouts = emptyList()
        nativeCheckout.cartChanged(cart)
        ShopCartStore.save(context, cart)
    }

    /** Explicit session preparation only. Cart lines survive every pending session or failure. */
    fun placeCheckout(context: Context) {
        if (placingCheckout || cart.isEmpty()) return
        val snapshot = cart.toList()
        placingCheckout = true
        checkoutError = null
        viewModelScope.launch {
            try {
                AppInit.awaitReady()
                for ((key, lines) in splitByShop(snapshot)) {
                    nativeCheckout.submit(key.first, key.second, lines, PendingCheckoutSink { OrderStore.add(context, it) })
                    nativeCheckout.cartChanged(cart)
                }
                orders = OrderStore.getAll(context)
            } finally {
                placingCheckout = false
            }
        }
    }

    fun dismissCheckoutError() { checkoutError = null }
    fun dismissSearchError() { searchError = null }

    private fun Throwable.toBackendMessage(): String =
        (this as? ShopBackendException)?.error?.message()
            ?: "Couldn't reach the store. Check your connection and try again."
}
