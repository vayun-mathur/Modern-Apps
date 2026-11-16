package com.vayunmathur.shop.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CatalogOptionSelection
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.MerchantListing
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderStatusQuery
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.ProductVariant
import com.vayunmathur.shop.data.ShopProfile
import com.vayunmathur.shop.data.SubmittedLine
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.network.ShopBackend
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** checkoutStore success/failure, refreshTracking, openLink search/merchant variants. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShopViewModelCoverage2Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeBackend : ShopBackend {
        var checkout: Result<UcpCheckout> = Result.success(UcpCheckout(
            checkoutId="chk1", continueUrl="https://cool.example/checkout/1",
            submittedLines=listOf(SubmittedLine("v1",1)), trackingToken="tok123"))
        var status: Result<Map<String, OrderTracking>> = Result.success(emptyMap())
        var merchant: MerchantListing? = null
        var product: Result<UcpProduct> = Result.failure(IllegalStateException("none"))
        override suspend fun search(query: String, shop: String?, limit: Int, country: String) = Result.success(emptyList<UcpProduct>())
        override suspend fun getProduct(shop: String, productId: String, variantId: String?, title: String?, selectedOptions: List<CatalogOptionSelection>) = product
        override suspend fun getProductByHandle(shop: String, handle: String) = product
        override suspend fun createCheckout(shop: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String) = checkout
        override suspend fun getCheckout(shop: String, sessionId: String) = checkout
        override suspend fun updateCheckout(shop: String, sessionId: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String) = checkout
        override suspend fun orderStatus(orders: List<OrderStatusQuery>) = status
        override suspend fun merchantFor(host: String) = Result.success(merchant)
    }

    private lateinit var backend: FakeBackend
    private lateinit var vm: ShopViewModel

    @BeforeTest fun setUp() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        AppInit.start(context)
        context.getSharedPreferences("shop_cart", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("shop_orders", Context.MODE_PRIVATE).edit().clear().commit()
        backend = FakeBackend(); vm = ShopViewModel(backend)
        setState(vm, "profile", ShopProfile(firstName="Ada", lastName="L", email="ada@example.com", phone="+14155550123"))
    }


    @Suppress("UNCHECKED_CAST")
    private fun setState(vm: ShopViewModel, name: String, value: Any?) {
        val f = ShopViewModel::class.java.declaredFields.first { it.name.startsWith(name) }
        f.isAccessible = true
        val delegate = f.get(vm)
        if (delegate is androidx.compose.runtime.MutableState<*>) {
            (delegate as androidx.compose.runtime.MutableState<Any?>).value = value
        } else { f.set(vm, value) }
    }
    private fun await(cond: () -> Boolean) {
        val d = System.currentTimeMillis() + 15_000
        while (!cond() && System.currentTimeMillis() < d) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20) }
        assertTrue(cond(), "condition not met")
    }

    private fun product() = UcpProduct(id="p1", title="Shoe", platformId="shopify", shopDomain="cool.example",
        checkoutEligible=true, storeName="Cool Store", priceRange=Money(12000,"USD"),
        variants=listOf(ProductVariant("v1","D",Money(12000,"USD"),true,storeName="Cool Store",shopDomain="cool.example")))

    @Test fun checkoutStore_successOpensEmbeddedCheckout() {
        // ShopViewModel's coordinator uses ShopHttpBackend directly; stub its transport.
        com.vayunmathur.shop.network.ShopHttpBackend.request = { _, _, _ ->
            com.vayunmathur.shop.network.BackendRawResponse(200, """
            {"checkoutId":"chk1","status":"incomplete","currency":"USD",
             "totals":{"subtotal":{"amount":12000,"currency":"USD"},"total":{"amount":12000,"currency":"USD"}},
             "totalEntries":[],"continueUrl":"https://cool.example/checkout/1","expiresAt":null,"messages":[],
             "version":"2026-08-25","review":{"shipping":[],"links":[],"browserOnlyReason":null,"availableShippingLineIds":[]},
             "submittedLines":[{"variantId":"v1","quantity":1}],"trackingToken":"tok123"}
            """.trimIndent(), emptyMap(), null)
        }
        vm.addToCart(product(), "v1", 1, context)
        var ready = false; var error: String? = null
        vm.checkoutStore("shopify","cool.example",context,dark=false,onReady={ ready=true },onError={ error=it })
        await { ready || error != null }
        assertTrue(ready, "error=$error"); assertNull(error)
        assertEquals("Cool Store", vm.embeddedCheckout?.storeName)
        assertTrue(vm.embeddedCheckout!!.url.contains("ec_version"))
        await { vm.orders.any { it.checkoutId == "chk1" } }
        // second call while? startingCheckout cleared after
        assertNull(vm.startingCheckout)
    }

    @Test fun checkoutStore_backendFailureSurfacesUncertainMessage() {
        com.vayunmathur.shop.network.ShopHttpBackend.request = { _, _, _ ->
            com.vayunmathur.shop.network.BackendRawResponse(500, "down", emptyMap(), null)
        }
        backend.checkout = Result.failure(IllegalStateException("boom"))
        vm.addToCart(product(), "v1", 1, context)
        var error: String? = null
        vm.checkoutStore("shopify","cool.example",context,dark=true,onReady={},onError={ error=it })
        await { error != null && vm.startingCheckout == null }
        assertTrue(error!!.isNotBlank())
        assertNull(vm.embeddedCheckout)
    }

    @Test fun refreshTracking_mergesAndClearsFlag() {
        vm.refreshTracking(context)
        await { !vm.refreshingTracking }
        assertTrue(vm.orders.isEmpty())
    }

    @Test fun openLink_searchQueryAndMerchantFallbacks() {
        backend.merchant = null // unknown host fallback to raw host
        vm.openLink("https://shoes.example/search?q=trail+shoes")
        await { vm.scopeDomain == "shoes.example" }
        assertEquals("trail shoes", vm.query); assertEquals("shoes.example", vm.scopeName)
        vm.openLink("https://shoes.example/products/no-variants")
        // product failure path falls back to scoped search with handle words; pendingRoute Search
        await { vm.query == "no variants" }
    }

    @Test fun openLink_productWithoutVariantsFallsBackToSearch() {
        backend.product = Result.success(product().copy(variants=emptyList()))
        vm.openLink("https://cool.example/products/empty-shoe")
        await { vm.query == "empty shoe" && vm.scopeDomain != null }
        assertTrue(vm.linkedProducts.isEmpty())
    }

    @Test fun embeddedCompletedWithExplicitTotalAndNoSession() {
        vm.addToCart(product(), "v1", 2, context)
        assertTrue(vm.openEmbeddedCheckout("shopify","cool.example","https://cool.example/checkout/x", dark=false))
        vm.embeddedCheckoutCompleted("ord-77", null, 9999, context)
        await { vm.orders.any { it.orderId == "ord-77" } }
        val order = vm.orders.first { it.orderId == "ord-77" }
        assertEquals(9999L, order.total.amount); assertEquals(2, order.itemCount)
        assertEquals("", order.continueUrl)
    }
}
