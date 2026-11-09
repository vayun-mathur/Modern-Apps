package com.vayunmathur.shop.platform

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.shop.Route
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.NativeCheckoutState
import com.vayunmathur.shop.domain.NativeCheckoutCoordinator
import com.vayunmathur.shop.domain.PendingCheckoutSink
import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.OrderStore
import com.vayunmathur.shop.data.EmbeddedCheckoutRequest
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.ProfileRepository
import com.vayunmathur.shop.data.ShopCartStore
import com.vayunmathur.shop.data.ShopProfile
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.data.message
import com.vayunmathur.shop.domain.EmbeddedCheckout
import com.vayunmathur.shop.domain.MerchantLink
import com.vayunmathur.shop.domain.MerchantLinks
import com.vayunmathur.shop.domain.MerchantUrls
import com.vayunmathur.shop.domain.OrderTrackingUpdates
import com.vayunmathur.shop.domain.ProfileCheckout
import com.vayunmathur.shop.network.ShopBackend
import com.vayunmathur.shop.network.ShopBackendException
import com.vayunmathur.shop.network.ShopHttpBackend
import kotlinx.coroutines.launch

/** Session catalog state plus persisted cart and pending checkout history. */
class ShopViewModel(
    private val backend: ShopBackend = ShopHttpBackend,
) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var scopeDomain by mutableStateOf<String?>(null)
        private set
    var scopeName by mutableStateOf<String?>(null)
        private set
    var focusSearchRequest by mutableStateOf(0)
        private set
    var focusOrdersRequest by mutableIntStateOf(0)
        private set
    var refreshingTracking by mutableStateOf(false)
        private set
    /** Set after an order is placed so the UI can ask for the delivery-notification permission. */
    var notificationPermissionRequest by mutableIntStateOf(0)
        private set
    var pendingRoute by mutableStateOf<Route?>(null)
        private set
    var linkedProducts by mutableStateOf<List<UcpProduct>>(emptyList())
        private set
    var launchLinkHandled = false
    /** Held in memory only: the URL carries a merchant session key. */
    var embeddedCheckout by mutableStateOf<EmbeddedCheckoutRequest?>(null)
        private set
    var results by mutableStateOf<List<UcpProduct>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    var cart by mutableStateOf<List<CartLine>>(emptyList())
        private set
    /** Store whose one-tap checkout is being prepared, as (platformId, shopDomain). */
    var startingCheckout by mutableStateOf<Pair<String, String>?>(null)
        private set
    var orders by mutableStateOf<List<PlacedOrder>>(emptyList())
        private set
    var profile by mutableStateOf<ShopProfile?>(null)
        private set
    var profileLoaded by mutableStateOf(false)
        private set
    val profileComplete: Boolean get() = profile?.validationError() == null && profile != null
    private var profileRepository: ProfileRepository? = null
    private val nativeCheckout = NativeCheckoutCoordinator()

    fun observeProfile(context: Context) {
        if (profileRepository != null) return
        val repository = ProfileRepository.get(context).also { profileRepository = it }
        viewModelScope.launch { repository.profile.collect { profile = it; profileLoaded = true } }
    }

    fun saveProfile(value: ShopProfile) {
        val repository = profileRepository ?: return
        if (value.validationError() != null) return
        viewModelScope.launch { repository.save(value) }
    }

    fun checkoutInputs(state: NativeCheckoutState): CheckoutInputs? =
        profile?.takeIf { it.validationError() == null }?.let { ProfileCheckout.inputs(it, state) }

    /**
     * One tap: a fresh merchant session from the saved profile, then the merchant's checkout
     * embedded in the app. A rejected create still carries the merchant's handoff URL.
     */
    fun checkoutStore(
        platformId: String,
        shop: String,
        context: Context,
        dark: Boolean,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (startingCheckout != null) return
        val lines = cart.filter { it.platformId == platformId && it.shopDomain == shop }.toList()
        if (lines.isEmpty()) return
        startingCheckout = platformId to shop
        val sink = PendingCheckoutSink { OrderStore.add(context, it) }
        viewModelScope.launch {
            try {
                AppInit.awaitReady()
                // A handoff URL may be single-use, so every tap starts a new session.
                nativeCheckout.forget(platformId, shop)
                submitFromProfile(platformId, shop, lines, sink)
                val state = nativeCheckout.snapshot.firstOrNull { it.platformId == platformId && it.shopDomain == shop }
                val url = (state?.checkout?.continueUrl?.let(MerchantUrls::httpsUrl)) ?: state?.handoffUrl
                if (url != null && openEmbeddedCheckout(platformId, shop, url, dark)) {
                    onReady()
                } else {
                    onError(state?.message ?: "This store didn't return a checkout. Please try again.")
                }
                orders = OrderStore.getAll(context)
            } finally {
                startingCheckout = null
            }
        }
    }

    private suspend fun submitFromProfile(
        platformId: String,
        shop: String,
        lines: List<CartLine>,
        sink: PendingCheckoutSink,
    ) {
        val state = nativeCheckout.snapshot.firstOrNull { it.platformId == platformId && it.shopDomain == shop }
            ?: NativeCheckoutState(platformId, shop)
        val inputs = checkoutInputs(state) ?: return
        nativeCheckout.edit(platformId, shop, inputs)
        nativeCheckout.submit(platformId, shop, lines, sink)
    }

    fun updateQuery(value: String) { query = value }

    fun consumePendingRoute() { pendingRoute = null }

    fun clearScope() {
        scopeDomain = null
        scopeName = null
        results = emptyList()
        search()
    }

    /** Opens a merchant web link: product pages in-app, anything else as a search scoped to that store. */
    fun openLink(url: String) {
        val link = MerchantLinks.parse(url) ?: return
        viewModelScope.launch {
            AppInit.awaitReady()
            // Raw-host fallback: the link host itself scopes the search. The
            // former merchant-directory lookup is gone with the endpoint.
            val domain = link.host
            val name = domain
            when (link) {
                is MerchantLink.Product -> backend.getProductByHandle(domain, link.handle).fold(
                    onSuccess = { product ->
                        val variant = product.variants.firstOrNull()
                        if (variant == null) {
                            scopedSearch(domain, name, link.handle.replace('-', ' '))
                        } else {
                            linkedProducts = (linkedProducts + product).takeLast(LINKED_PRODUCT_LIMIT)
                            val publisher = variant.shopDomain.ifBlank { product.shopDomain }
                            pendingRoute = Route.ProductDetail(variant.id, product.platformId, publisher)
                        }
                    },
                    onFailure = { scopedSearch(domain, name, link.handle.replace('-', ' ')) },
                )
                is MerchantLink.Search -> scopedSearch(domain, name, link.query)
                is MerchantLink.Merchant -> scopedSearch(domain, name, "")
            }
        }
    }

    private fun scopedSearch(domain: String, name: String, scopedQuery: String) {
        scopeDomain = domain
        scopeName = name
        query = scopedQuery
        results = emptyList()
        pendingRoute = Route.Search
        focusSearchRequest++
        if (scopedQuery.isNotBlank()) search()
    }

    fun loadCart(context: Context) {
        viewModelScope.launch { cart = ShopCartStore.getAll(context) }
    }

    fun loadOrders(context: Context) {
        viewModelScope.launch {
            orders = OrderStore.getAll(context)
            OrderTrackingSync.schedule(context, orders)
        }
    }

    fun showOrders() { focusOrdersRequest++ }

    fun refreshTracking(context: Context) {
        if (refreshingTracking) return
        refreshingTracking = true
        viewModelScope.launch {
            try {
                AppInit.awaitReady()
                orders = OrderTrackingSync.refresh(context, backend).orders
                OrderTrackingSync.schedule(context, orders)
            } finally {
                refreshingTracking = false
            }
        }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        val shop = scopeDomain
        searching = true
        searchError = null
        results = emptyList()
        viewModelScope.launch {
            try {
                AppInit.awaitReady()
                backend.search(q, shop, limit = SEARCH_LIMIT).fold(
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
                storeName = variant.storeName.ifBlank { product.storeName },
            )
        }
        nativeCheckout.cartChanged(cart)
        ShopCartStore.save(context, cart)
    }

    fun removeLine(line: CartLine, context: Context) {
        cart = cart.filterNot {
            it.variantId == line.variantId && it.platformId == line.platformId && it.shopDomain == line.shopDomain
        }
        nativeCheckout.cartChanged(cart)
        ShopCartStore.save(context, cart)
    }

    fun dismissSearchError() { searchError = null }

    fun openEmbeddedCheckout(platformId: String, shop: String, continueUrl: String, dark: Boolean): Boolean {
        val url = EmbeddedCheckout.embeddedUrl(continueUrl, dark) ?: return false
        val storeName = cart.firstOrNull { it.platformId == platformId && it.shopDomain == shop }?.displayName ?: shop
        embeddedCheckout = EmbeddedCheckoutRequest(platformId, shop, storeName, url, continueUrl)
        return true
    }

    fun closeEmbeddedCheckout() { embeddedCheckout = null }

    /** The merchant's `ec.complete` is the only signal that an order was placed and paid. */
    fun embeddedCheckoutCompleted(orderId: String, permalinkUrl: String?, totalMinor: Long?, context: Context) {
        val request = embeddedCheckout ?: return
        val lines = cart.filter { it.platformId == request.platformId && it.shopDomain == request.shopDomain }
        val currency = lines.firstOrNull()?.unitPrice?.currency ?: "USD"
        val total = totalMinor ?: lines.sumOf { it.unitPrice.amount * it.quantity }
        val session = nativeCheckout.snapshot
            .firstOrNull { it.platformId == request.platformId && it.shopDomain == request.shopDomain }
            ?.checkout
        val sessionId = session?.checkoutId
        val order = PlacedOrder(
            checkoutId = sessionId ?: orderId, placedAtMs = System.currentTimeMillis(), total = Money(total, currency),
            itemCount = lines.sumOf { it.quantity }, continueUrl = permalinkUrl.orEmpty(), status = "completed",
            platformId = request.platformId, shopDomain = request.shopDomain, storeName = request.storeName,
            lines = lines.map { it.copy(imageUrl = "") }, orderId = orderId,
            trackingToken = session?.trackingToken.orEmpty(),
        )
        cart = cart - lines.toSet()
        ShopCartStore.save(context, cart)
        nativeCheckout.forget(request.platformId, request.shopDomain)
        nativeCheckout.cartChanged(cart)
        embeddedCheckout = null
        notificationPermissionRequest++
        viewModelScope.launch {
            orders = OrderStore.update(context) { OrderTrackingUpdates.complete(it, sessionId, order) }
            OrderTrackingSync.schedule(context, orders)
        }
    }

    private fun Throwable.toBackendMessage(): String =
        (this as? ShopBackendException)?.error?.message()
            ?: "Couldn't reach the store. Check your connection and try again."

    private companion object {
        const val SEARCH_LIMIT = 40
        const val LINKED_PRODUCT_LIMIT = 20
    }
}
