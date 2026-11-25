package com.vayunmathur.shop.platform

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.vayunmathur.shop.data.CheckoutIntentStore
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
import com.vayunmathur.shop.network.CatalogStore
import com.vayunmathur.shop.data.ShopCatalog
import com.vayunmathur.shop.data.ShopCatalogFile
import com.vayunmathur.shop.network.ShopHttpBackend
import com.vayunmathur.shop.network.toModel
import kotlinx.coroutines.launch

private const val MAX_QUANTITY = 99

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
    var focusSearchRequest by mutableIntStateOf(0)
        private set
    var refreshingTracking by mutableStateOf(false)
        private set
    /** Set after an order is placed so the UI can ask for the delivery-notification permission. */
    var notificationPermissionRequest by mutableIntStateOf(0)
        private set
    var linkedProducts by mutableStateOf<List<UcpProduct>>(emptyList())
        private set
    var launchLinkHandled = false
    /** Held in memory only: the URL carries a merchant session key. */
    var embeddedCheckout by mutableStateOf<EmbeddedCheckoutRequest?>(null)
        private set
    var results by mutableStateOf<List<UcpProduct>>(emptyList())
        private set
    /** True when [results] came from the offline R2 catalog, not the live API. */
    var resultsFromCatalog by mutableStateOf(false)
        private set
    /** `generated_at` of the catalog in use, for the freshness label. */
    var catalogGeneratedAt by mutableStateOf<String?>(null)
        private set
    /** Set at checkout when live merchant prices differ from cart snapshot prices. */
    var checkoutPriceNote by mutableStateOf<String?>(null)
        private set
    var catalog: ShopCatalogFile? = null
        private set
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    /** Query the current [results]/[searchError] belong to; null until a search has run. */
    var searchedQuery by mutableStateOf<String?>(null)
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
    /** App context for the coordinator's intent persistence; set by every entry point that has one. */
    private var intentContext: Context? = null
    private val nativeCheckout = NativeCheckoutCoordinator(
        intentSink = { record ->
            intentContext?.let { CheckoutIntentStore.save(it, record) }
        },
        intentDone = { platformId, shopDomain ->
            intentContext?.let { CheckoutIntentStore.clear(it, platformId, shopDomain) }
        },
    )

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
        intentContext = context.applicationContext
        val sink = PendingCheckoutSink { OrderStore.add(context, it) }
        viewModelScope.launch {
            try {
                AppInit.awaitReady()
                // A persisted in-flight checkout for this store wins over a fresh
                // session: death mid-create must not mint a second intent/session.
                val inFlight = CheckoutIntentStore.get(context)
                    ?.takeIf { it.platformId == platformId && it.shopDomain == shop }
                if (inFlight != null) {
                    nativeCheckout.restore(inFlight)
                    if (inFlight.checkoutId.isNullOrBlank()) {
                        onError(
                            "A checkout may have been started for this store. " +
                                "Check your orders or the merchant website before trying again.",
                        )
                        return@launch
                    }
                    nativeCheckout.reconcile(platformId, shop)
                    val restored = nativeCheckout.snapshot
                        .firstOrNull { it.platformId == platformId && it.shopDomain == shop }
                    val restoredUrl = restored?.checkout?.continueUrl?.let(MerchantUrls::httpsUrl)
                        ?: restored?.handoffUrl
                    if (restoredUrl != null && openEmbeddedCheckout(platformId, shop, restoredUrl, dark)) {
                        onReady()
                    } else {
                        onError(restored?.message ?: "This store didn't return a checkout. Please try again.")
                    }
                    orders = OrderStore.getAll(context)
                    return@launch
                }
                // A handoff URL may be single-use, so every tap starts a new session.
                nativeCheckout.forget(platformId, shop)
                submitFromProfile(platformId, shop, lines, sink)
                val state = nativeCheckout.snapshot.firstOrNull { it.platformId == platformId && it.shopDomain == shop }
                checkoutPriceNote = state?.checkout?.totals?.subtotal?.let {
                    ShopCatalog.priceRevalidationNote(lines, it)
                }
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

    fun clearScope() {
        scopeDomain = null
        scopeName = null
        results = emptyList()
        searchedQuery = null
        searchError = null
        search()
    }

    /**
     * Opens a merchant web link: product pages in-app, anything else as a search scoped to that store.
     * Navigation is the caller's job: [navigate] is invoked directly with the destination
     * (a product detail, or [Route.Search] for a scoped search) instead of parking a route
     * in this ViewModel for a UI effect to pick up.
     */
    fun openLink(url: String, navigate: (Route) -> Unit = {}) {
        val link = MerchantLinks.parse(url) ?: return
        viewModelScope.launch {
            AppInit.awaitReady()
            // Scoped directory lookup; unknown hosts (or lookup failures)
            // fall back to the raw link host.
            val merchant = backend.merchantFor(link.host).getOrNull()
            val domain = merchant?.domain?.takeIf { it.isNotBlank() } ?: link.host
            val name = merchant?.name?.takeIf { it.isNotBlank() } ?: domain
            when (link) {
                is MerchantLink.Product -> backend.getProductByHandle(domain, link.handle).fold(
                    onSuccess = { product ->
                        val variant = product.variants.firstOrNull()
                        if (variant == null) {
                            scopedSearch(domain, name, link.handle.replace('-', ' '), navigate)
                        } else {
                            linkedProducts = (linkedProducts + product).takeLast(LINKED_PRODUCT_LIMIT)
                            val publisher = variant.shopDomain.ifBlank { product.shopDomain }
                            navigate(Route.ProductDetail(variant.id, product.platformId, publisher))
                        }
                    },
                    onFailure = { scopedSearch(domain, name, link.handle.replace('-', ' '), navigate) },
                )
                is MerchantLink.Search -> scopedSearch(domain, name, link.query, navigate)
                is MerchantLink.Merchant -> scopedSearch(domain, name, "", navigate)
            }
        }
    }

    private fun scopedSearch(domain: String, name: String, scopedQuery: String, navigate: (Route) -> Unit) {
        scopeDomain = domain
        scopeName = name
        query = scopedQuery
        results = emptyList()
        searchedQuery = null
        searchError = null
        navigate(Route.Search)
        focusSearchRequest++
        if (scopedQuery.isNotBlank()) search()
    }

    fun loadCart(context: Context) {
        viewModelScope.launch { cart = ShopCartStore.getAll(context) }
    }

    fun loadOrders(context: Context) {
        intentContext = context.applicationContext
        viewModelScope.launch {
            orders = OrderStore.getAll(context)
            OrderTrackingSync.schedule(context, orders)
            reconcileInFlight(context)
        }
    }

    /**
     * Launch reconciliation: seed the coordinator from a persisted in-flight
     * record and, when it names a session, refresh that session with a GET —
     * never a new create. A record without a session id stays Uncertain.
     */
    private suspend fun reconcileInFlight(context: Context) {
        val record = CheckoutIntentStore.get(context) ?: return
        nativeCheckout.restore(record)
        if (!record.checkoutId.isNullOrBlank()) {
            AppInit.awaitReady()
            nativeCheckout.reconcile(record.platformId, record.shopDomain)
        }
    }

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

    /** Download/refresh the R2 catalog once; failures keep any cached copy. */
    fun loadCatalog(context: Context) {
        if (catalog != null) return
        catalog = CatalogStore.cached(context)
        catalog?.let { catalogGeneratedAt = it.generatedAt }
        viewModelScope.launch {
            AppInit.awaitReady()
            CatalogStore.refresh(context)?.let {
                catalog = it
                catalogGeneratedAt = it.generatedAt
            }
        }
    }

    /** Offline product copy for detail fallback; live detail still verifies before cart actions. */
    fun catalogProduct(variantId: String): UcpProduct? =
        catalog?.let { ShopCatalog.productByVariant(it, variantId) }?.toModel()

    fun search() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        val shop = scopeDomain
        searching = true
        searchError = null
        searchedQuery = q
        results = emptyList()
        viewModelScope.launch {
            try {
                AppInit.awaitReady()
                backend.search(q, shop, limit = SEARCH_LIMIT).fold(
                    onSuccess = { results = it; resultsFromCatalog = false },
                    onFailure = { error ->
                        val offline = catalog?.let { ShopCatalog.search(it, q, shop, SEARCH_LIMIT) }
                            ?.map { it.toModel() }
                        if (!offline.isNullOrEmpty()) {
                            results = offline
                            resultsFromCatalog = true
                            searchError = null
                        } else {
                            searchError = error.toBackendMessage()
                        }
                    },
                )
            } finally {
                searching = false
            }
        }
    }

    fun addToCart(product: com.vayunmathur.shop.data.UcpProduct, variantId: String, quantity: Int, context: Context) {
        val variant = product.variants.firstOrNull { it.id == variantId } ?: return
        if (!variant.available || !product.checkoutEligible || quantity !in 1..MAX_QUANTITY) return
        val existing = cart.indexOfFirst {
            it.variantId == variantId && it.platformId == product.platformId &&
                it.shopDomain == product.shopDomain
        }
        cart = if (existing >= 0) {
            cart.toMutableList().also {
                it[existing] = it[existing].copy(
                    quantity = (it[existing].quantity + quantity).coerceAtMost(MAX_QUANTITY),
                )
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
        intentContext = context.applicationContext
        viewModelScope.launch {
            CheckoutIntentStore.clear(context, request.platformId, request.shopDomain)
        }
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
