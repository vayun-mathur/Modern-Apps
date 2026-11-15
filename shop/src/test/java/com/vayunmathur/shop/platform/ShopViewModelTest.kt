package com.vayunmathur.shop.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.shop.Route
import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CatalogOptionSelection
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.MerchantListing
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderStatusQuery
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.ProductVariant
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.data.UcpError
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.network.ShopBackend
import com.vayunmathur.shop.network.ShopBackendException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** ShopViewModel over a fake backend: search, cart, links and checkout handoff. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShopViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeBackend : ShopBackend {
        var searchResult: Result<List<UcpProduct>> = Result.success(emptyList())
        var productResult: Result<UcpProduct> = Result.failure(IllegalStateException("none"))
        var merchant: MerchantListing? = null
        val searches = mutableListOf<Triple<String, String?, Int>>()
        var checkoutCalls = 0

        override suspend fun search(query: String, shop: String?, limit: Int, country: String): Result<List<UcpProduct>> {
            searches += Triple(query, shop, limit)
            return searchResult
        }

        override suspend fun getProduct(
            shop: String, productId: String, variantId: String?,
            title: String?, selectedOptions: List<CatalogOptionSelection>,
        ): Result<UcpProduct> = productResult

        override suspend fun getProductByHandle(shop: String, handle: String): Result<UcpProduct> = productResult

        override suspend fun createCheckout(
            shop: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String,
        ): Result<UcpCheckout> {
            checkoutCalls++
            return Result.failure(IllegalStateException("no checkout in fake"))
        }

        override suspend fun getCheckout(shop: String, sessionId: String): Result<UcpCheckout> =
            Result.failure(IllegalStateException("none"))

        override suspend fun updateCheckout(
            shop: String, sessionId: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String,
        ): Result<UcpCheckout> = Result.failure(IllegalStateException("none"))

        override suspend fun orderStatus(orders: List<OrderStatusQuery>): Result<Map<String, OrderTracking>> =
            Result.success(emptyMap())

        override suspend fun merchantFor(host: String): Result<MerchantListing?> = Result.success(merchant)
    }

    private lateinit var backend: FakeBackend
    private lateinit var vm: ShopViewModel

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        AppInit.start(context)
        context.getSharedPreferences("shop_cart", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("shop_orders", Context.MODE_PRIVATE).edit().clear().commit()
        backend = FakeBackend()
        vm = ShopViewModel(backend)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(condition(), "condition not met")
    }

    private fun product(
        id: String = "p1", title: String = "Trail Shoes", eligible: Boolean = true,
        available: Boolean = true, storeName: String = "Cool Store",
    ) = UcpProduct(
        id = id, title = title, platformId = "shopify", shopDomain = "cool.example",
        checkoutEligible = eligible, storeName = storeName,
        priceRange = Money(12_000, "USD"),
        variants = listOf(
            ProductVariant(
                id = "v1", title = "Default", price = Money(12_000, "USD"), available = available,
                storeName = storeName, shopDomain = "cool.example",
            ),
        ),
    )

    @Test
    fun search_successStoresResultsAndRecordsQuery() {
        backend.searchResult = Result.success(listOf(product()))
        vm.updateQuery("  shoes  ")
        vm.search()
        await { vm.results.size == 1 }
        assertEquals("Trail Shoes", vm.results[0].title)
        assertFalse(vm.searching)
        assertNull(vm.searchError)
        assertEquals(Triple("shoes", null, 40), backend.searches.last())
    }

    @Test
    fun search_blankQueryDoesNotCallBackend() {
        vm.updateQuery("   ")
        vm.search()
        assertTrue(backend.searches.isEmpty())
        assertTrue(vm.results.isEmpty())
    }

    @Test
    fun search_failureSetsErrorAndDismissClearsIt() {
        backend.searchResult = Result.failure(ShopBackendException(UcpError.Transport("down")))
        vm.updateQuery("shoes")
        vm.search()
        await { vm.searchError != null }
        assertTrue(vm.searchError!!.contains("reach the store"))
        vm.dismissSearchError()
        assertNull(vm.searchError)
    }

    @Test
    fun addToCart_mergesSameVariantCapsAt99AndRejectsInvalid() {
        val p = product()
        vm.addToCart(p, "v1", 2, context)
        assertEquals(1, vm.cart.size)
        assertEquals(2, vm.cart[0].quantity)
        assertEquals("Cool Store", vm.cart[0].storeName)
        assertEquals(12_000L, vm.cart[0].unitPrice.amount)
        vm.addToCart(p, "v1", 98, context)
        assertEquals(99, vm.cart[0].quantity)
        // Rejections: unknown variant, zero qty, unavailable, not eligible.
        vm.addToCart(p, "missing", 1, context)
        vm.addToCart(p, "v1", 0, context)
        vm.addToCart(product(available = false), "v1", 1, context)
        vm.addToCart(product(eligible = false), "v1", 1, context)
        assertEquals(1, vm.cart.size)
        assertEquals(99, vm.cart[0].quantity)
    }

    @Test
    fun removeLine_removesOnlyMatchingStoreLine() {
        val p = product()
        vm.addToCart(p, "v1", 1, context)
        val line = vm.cart.single()
        vm.removeLine(line.copy(shopDomain = "other.example"), context)
        assertEquals(1, vm.cart.size)
        vm.removeLine(line, context)
        assertTrue(vm.cart.isEmpty())
    }

    @Test
    fun openLink_productSuccessRoutesToDetailAndCapsLinked() {
        backend.merchant = MerchantListing("m1", "Cool Store", "cool.example", "https://cool.example")
        backend.productResult = Result.success(product())
        vm.openLink("https://cool.example/products/trail-shoes")
        await { vm.pendingRoute != null }
        assertEquals(Route.ProductDetail("v1", "shopify", "cool.example"), vm.pendingRoute)
        assertEquals(1, vm.linkedProducts.size)
        vm.consumePendingRoute()
        assertNull(vm.pendingRoute)
    }

    @Test
    fun openLink_productFailureFallsBackToScopedSearch() {
        backend.productResult = Result.failure(IllegalStateException("404"))
        vm.openLink("https://unknown.example/products/red-shoes")
        await { vm.pendingRoute == Route.Search }
        assertEquals("unknown.example", vm.scopeDomain)
        assertEquals("red shoes", vm.query)
        assertTrue(vm.focusSearchRequest > 0)
    }

    @Test
    fun openLink_merchantAndInvalidUrls() {
        vm.openLink("not a url")
        assertNull(vm.pendingRoute)
        backend.merchant = MerchantListing("m1", "Kitchen Co", "kitchen.example", "https://kitchen.example")
        vm.openLink("https://kitchen.example/collections/all")
        await { vm.pendingRoute == Route.Search }
        assertEquals("kitchen.example", vm.scopeDomain)
        assertEquals("Kitchen Co", vm.scopeName)
    }

    @Test
    fun clearScope_clearsDomainAndResults() {
        backend.merchant = MerchantListing("m1", "Kitchen Co", "kitchen.example", "https://kitchen.example")
        vm.openLink("https://kitchen.example/collections/all")
        await { vm.scopeDomain != null }
        vm.clearScope()
        assertNull(vm.scopeDomain)
        assertNull(vm.scopeName)
        assertTrue(vm.results.isEmpty())
    }

    @Test
    fun embeddedCheckout_openValidatesUrlAndCompleteMovesCartToOrder() {
        vm.addToCart(product(), "v1", 2, context)
        assertFalse(vm.openEmbeddedCheckout("shopify", "cool.example", "http://insecure.example/checkout", dark = false))
        assertNull(vm.embeddedCheckout)
        assertTrue(vm.openEmbeddedCheckout("shopify", "cool.example", "https://cool.example/checkout/abc", dark = true))
        assertEquals("Cool Store", vm.embeddedCheckout?.storeName)
        assertTrue(vm.embeddedCheckout!!.url.contains("ec_version"))
        vm.embeddedCheckoutCompleted("order-9", "https://cool.example/orders/9", null, context)
        assertTrue(vm.cart.isEmpty())
        assertNull(vm.embeddedCheckout)
        assertTrue(vm.notificationPermissionRequest > 0)
        await { vm.orders.any { it.orderId == "order-9" } }
        assertEquals(24_000L, vm.orders.first { it.orderId == "order-9" }.total.amount)
        assertEquals(2, vm.orders.first { it.orderId == "order-9" }.itemCount)
    }

    @Test
    fun checkoutStore_withoutProfileReportsErrorAndDoesNotCreateSession() {
        vm.addToCart(product(), "v1", 1, context)
        var ready = false
        var error: String? = null
        vm.checkoutStore("shopify", "cool.example", context, dark = false, onReady = { ready = true }, onError = { error = it })
        await { error != null && vm.startingCheckout == null }
        assertFalse(ready)
        assertTrue(error!!.contains("didn't return a checkout"))
        assertEquals(0, backend.checkoutCalls)
        assertNull(vm.startingCheckout)
        // Empty store: no-op, no error callback.
        var error2: String? = null
        vm.checkoutStore("shopify", "empty.example", context, dark = false, onReady = {}, onError = { error2 = it })
        assertNull(error2)
    }

    @Test
    fun loadCartAndOrders_readPersistedStores() {
        vm.addToCart(product(), "v1", 3, context)
        val vm2 = ShopViewModel(backend)
        vm2.loadCart(context)
        await { vm2.cart.size == 1 }
        assertEquals(3, vm2.cart[0].quantity)
        vm2.loadOrders(context)
        await { !vm2.refreshingTracking }
        assertTrue(vm2.orders.isEmpty())
        vm2.showOrders()
        assertTrue(vm2.focusOrdersRequest > 0)
    }

    @Test
    fun checkoutInputs_nullWithoutCompleteProfile() {
        assertNull(vm.checkoutInputs(com.vayunmathur.shop.data.NativeCheckoutState("shopify", "cool.example")))
        assertFalse(vm.profileComplete)
    }
}
