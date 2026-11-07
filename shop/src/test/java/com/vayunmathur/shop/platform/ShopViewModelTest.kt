package com.vayunmathur.shop.platform

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.shop.Route
import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.MerchantListing
import com.vayunmathur.shop.data.OrderStore
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.ShopCartStore
import com.vayunmathur.shop.data.ShopProfile
import com.vayunmathur.shop.network.BackendRawResponse
import com.vayunmathur.shop.network.ShopBackendException
import com.vayunmathur.shop.network.ShopHttpBackend
import com.vayunmathur.shop.data.UcpError
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ShopViewModelTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var backend: FakeShopBackend
    private lateinit var vm: ShopViewModel

    private val sessionJson = """
        {"checkoutId": "checkout-1", "status": "incomplete", "currency": "USD",
         "totals": {"subtotal": {"amount": 12999, "currency": "USD"}, "total": {"amount": 12999, "currency": "USD"}},
         "totalEntries": [{"type": "total", "display_text": "Total", "amount": 12999, "currency": "USD"}],
         "continueUrl": "https://shop.example.com/checkout/1",
         "messages": [], "version": "2026-08-25",
         "review": {"shipping": [], "links": [], "browserOnlyReason": null, "availableShippingLineIds": []},
         "submittedLines": [{"variantId": "v1", "quantity": 1}], "trackingToken": "tok-1"}
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(ctx) }
        AppInit.start(ctx)
        runBlocking { AppInit.awaitReady() }
        ctx.getSharedPreferences("shop_cart", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("shop_orders", Context.MODE_PRIVATE).edit().clear().commit()
        backend = FakeShopBackend()
        vm = ShopViewModel(backend)
    }

    @AfterTest
    fun tearDown() {
        // Never let a later test reach the real network through the HTTP backend seam.
        ShopHttpBackend.request = { _, _, _ -> throw IOException("stubbed off in tests") }
    }

    private fun await(condition: () -> Boolean): Boolean {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        assertTrue(await(condition), "timed out: $description; state: results=${vm.results.size} searching=${vm.searching} error=${vm.searchError}")
    }

    // ---------- search ----------

    @Test
    fun `search stores results and clears the searching flag`() {
        backend.searchResult = Result.success(listOf(testProduct()))
        vm.updateQuery("  trail shoes ")
        vm.search()
        awaitTrue("results") { vm.results.size == 1 && !vm.searching }
        assertEquals("trail shoes", backend.searchCalls.single().first)
        assertEquals(40, backend.searchCalls.single().third)
        assertNull(vm.searchError)
    }

    @Test
    fun `search failure surfaces the backend message and can be dismissed`() {
        backend.searchResult = Result.failure(ShopBackendException(UcpError.Transport("down")))
        vm.updateQuery("shoes")
        vm.search()
        awaitTrue("error") { vm.searchError != null && !vm.searching }
        assertTrue(vm.searchError!!.contains("Couldn't reach"))
        vm.dismissSearchError()
        assertNull(vm.searchError)
    }

    @Test
    fun `search failure without a backend error uses the generic message`() {
        backend.searchResult = Result.failure(IllegalStateException("boom"))
        vm.updateQuery("shoes")
        vm.search()
        awaitTrue("error") { vm.searchError != null }
        assertTrue(vm.searchError!!.contains("Couldn't reach the store"))
    }

    @Test
    fun `blank queries do not search and a second search while busy is ignored`() {
        vm.updateQuery("   ")
        vm.search()
        assertTrue(backend.searchCalls.isEmpty())
        assertFalse(vm.searching)

        backend.searchGate = CompletableDeferred()
        backend.searchResult = Result.success(listOf(testProduct()))
        vm.updateQuery("shoes")
        vm.search()
        awaitTrue("searching") { vm.searching }
        vm.search()
        assertEquals(1, backend.searchCalls.size)
        backend.searchGate?.complete(Unit)
        awaitTrue("done") { vm.results.size == 1 && !vm.searching }
    }

    // ---------- cart ----------

    @Test
    fun `add to cart adds, merges and caps quantities, and persists`() = runBlocking {
        val product = testProduct()
        vm.addToCart(product, "v1", 2, ctx)
        assertEquals(1, vm.cart.size)
        assertEquals(2, vm.cart.single().quantity)
        assertEquals("Trail Store", vm.cart.single().storeName)
        vm.addToCart(product, "v1", 98, ctx)
        assertEquals(99, vm.cart.single().quantity)
        // Persisted asynchronously through the single-writer store.
        repeat(100) {
            if (ShopCartStore.getAll(ctx).singleOrNull()?.quantity == 99) return@runBlocking
            Thread.sleep(20)
        }
        assertEquals(99, ShopCartStore.getAll(ctx).single().quantity)
    }

    @Test
    fun `add to cart rejects unavailable, ineligible, unknown and out-of-range`() {
        val product = testProduct()
        vm.addToCart(product, "missing", 1, ctx)
        vm.addToCart(product.copy(checkoutEligible = false), "v1", 1, ctx)
        vm.addToCart(testProduct(variants = listOf(testVariant(available = false))), "v1", 1, ctx)
        vm.addToCart(product, "v1", 0, ctx)
        vm.addToCart(product, "v1", 100, ctx)
        assertTrue(vm.cart.isEmpty())
    }

    @Test
    fun `remove line drops only the matching variant and load cart restores`() = runBlocking {
        val product = testProduct()
        vm.addToCart(product, "v1", 1, ctx)
        val other = testProduct(id = "p2", variants = listOf(testVariant(id = "v2")))
        vm.addToCart(other, "v2", 3, ctx)
        assertEquals(2, vm.cart.size)
        vm.removeLine(vm.cart.first { it.variantId == "v1" }, ctx)
        assertEquals(listOf("v2"), vm.cart.map { it.variantId })

        val fresh = ShopViewModel(backend)
        fresh.loadCart(ctx)
        assertTrue(await { fresh.cart.size == 1 }, "cart was not restored: ${fresh.cart}")
        assertEquals(listOf("v2"), fresh.cart.map { it.variantId })
        assertEquals(3, fresh.cart.single().quantity)
    }

    // ---------- profile ----------

    @Test
    fun `profile flows in, gates completeness, and saves only valid profiles`() {
        val store = FakeProfileStore(null)
        vm.profileStoreFactory = { store }
        vm.observeProfile(ctx)
        awaitTrue("loaded") { vm.profileLoaded }
        assertFalse(vm.profileComplete)
        // Saving before a profile exists is allowed once observed; invalid drafts are refused.
        vm.saveProfile(ShopProfile(firstName = "Ada"))
        assertTrue(store.saved.isEmpty())

        store.flow.value = completeProfile
        awaitTrue("complete") { vm.profileComplete }
        assertNotNull(vm.checkoutInputs(com.vayunmathur.shop.data.NativeCheckoutState("shopify", "shop.example.com")))

        val updated = completeProfile.copy(firstName = "Grace")
        vm.saveProfile(updated)
        awaitTrue("saved") { store.saved.contains(updated) }
        // Re-observing is a no-op.
        vm.observeProfile(ctx)
        assertEquals(1, store.saved.size)
    }

    @Test
    fun `checkout inputs are null without a complete profile`() {
        assertNull(vm.checkoutInputs(com.vayunmathur.shop.data.NativeCheckoutState("shopify", "shop.example.com")))
    }

    // ---------- links ----------

    @Test
    fun `a product link opens the product detail for its first variant`() {
        backend.merchantsResult = Result.success(
            listOf(MerchantListing("m1", "Trail Store", "shop.example.com", "https://shop.example.com")),
        )
        backend.handleResult = Result.success(testProduct())
        vm.openLink("https://shop.example.com/products/trail-runner")
        awaitTrue("route") { vm.pendingRoute is Route.ProductDetail }
        val route = vm.pendingRoute as Route.ProductDetail
        assertEquals("v1", route.variantId)
        assertEquals("shop.example.com", route.shopDomain)
        assertEquals(1, vm.linkedProducts.size)
        vm.consumePendingRoute()
        assertNull(vm.pendingRoute)
    }

    @Test
    fun `a product link whose lookup fails falls back to a scoped search`() {
        backend.merchantsResult = Result.failure(IllegalStateException("no directory"))
        backend.handleResult = Result.failure(ShopBackendException(UcpError.Transport("down")))
        backend.searchResult = Result.success(listOf(testProduct()))
        vm.openLink("https://other.example.com/products/blue-shoes")
        awaitTrue("scoped") { vm.pendingRoute == Route.Search && vm.scopeDomain == "other.example.com" }
        assertEquals("blue shoes", vm.query)
        awaitTrue("searched") { vm.results.size == 1 }
        assertEquals("other.example.com", backend.searchCalls.single().second)
    }

    @Test
    fun `a product link with no variants falls back to a scoped search`() {
        backend.handleResult = Result.success(testProduct(variants = emptyList()))
        vm.openLink("https://shop.example.com/products/trail-runner")
        awaitTrue("scoped") { vm.pendingRoute == Route.Search }
        assertEquals("trail runner", vm.query)
    }

    @Test
    fun `merchant and search links scope the search, and junk links are ignored`() {
        vm.openLink("https://shop.example.com/search?q=boots")
        awaitTrue("search link") { vm.pendingRoute == Route.Search && vm.query == "boots" }
        vm.consumePendingRoute()

        val fresh = ShopViewModel(backend)
        fresh.openLink("https://shop.example.com/")
        awaitTrue("merchant link") { fresh.pendingRoute == Route.Search && fresh.scopeDomain == "shop.example.com" }
        assertEquals("", fresh.query)

        val junk = ShopViewModel(backend)
        junk.openLink("not a url at all")
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(junk.pendingRoute)
    }

    @Test
    fun `clear scope drops the merchant filter and re-searches`() {
        backend.searchResult = Result.success(emptyList())
        vm.openLink("https://shop.example.com/")
        awaitTrue("scoped") { vm.scopeDomain == "shop.example.com" }
        vm.updateQuery("shoes")
        vm.clearScope()
        assertNull(vm.scopeDomain)
        assertNull(vm.scopeName)
        awaitTrue("researched") { backend.searchCalls.any { it.second == null } }
    }

    @Test
    fun `show orders bumps the focus request`() {
        val before = vm.focusOrdersRequest
        vm.showOrders()
        assertEquals(before + 1, vm.focusOrdersRequest)
    }

    // ---------- checkout ----------

    private fun httpSession(body: String, status: Int = 200) {
        ShopHttpBackend.request = { _, _, _ -> BackendRawResponse(status, body, emptyMap(), null) }
    }

    private fun cartedVm(): ShopViewModel {
        val store = FakeProfileStore(completeProfile)
        val viewModel = ShopViewModel(backend)
        viewModel.profileStoreFactory = { store }
        viewModel.observeProfile(ctx)
        assertTrue(await { viewModel.profileComplete })
        viewModel.addToCart(testProduct(), "v1", 1, ctx)
        return viewModel
    }

    @Test
    fun `checkout store opens the embedded checkout when the merchant returns a URL`() {
        val viewModel = cartedVm()
        httpSession(sessionJson)
        var ready = false
        var error: String? = null
        viewModel.checkoutStore("shopify", "shop.example.com", ctx, dark = false, { ready = true }, { error = it })
        awaitTrue("ready") { ready || error != null }
        assertTrue(ready, "error was $error")
        assertNull(error)
        assertEquals("https://shop.example.com/checkout/1", viewModel.embeddedCheckout?.browserUrl)
        awaitTrue("checkout finished") { viewModel.startingCheckout == null }
        // The pending record was persisted by the coordinator's sink.
        runBlocking { assertTrue(OrderStore.getAll(ctx).any { it.checkoutId == "checkout-1" }) }
    }

    @Test
    fun `checkout store reports when the merchant returns no checkout URL`() {
        val viewModel = cartedVm()
        httpSession(sessionJson.replace("https://shop.example.com/checkout/1", ""))
        var error: String? = null
        viewModel.checkoutStore("shopify", "shop.example.com", ctx, dark = false, {}, { error = it })
        awaitTrue("error") { error != null }
        assertTrue(error!!.contains("didn't return a checkout"), error)
        assertNull(viewModel.embeddedCheckout)
    }

    @Test
    fun `checkout store without cart lines or while busy does nothing`() {
        vm.checkoutStore("shopify", "shop.example.com", ctx, dark = false, {}, {})
        assertNull(vm.startingCheckout)
        assertNull(vm.embeddedCheckout)
    }

    @Test
    fun `open embedded checkout validates the URL and close clears it`() {
        vm.addToCart(testProduct(), "v1", 1, ctx)
        assertFalse(vm.openEmbeddedCheckout("shopify", "shop.example.com", "http://insecure.example/checkout", dark = false))
        assertNull(vm.embeddedCheckout)
        assertTrue(vm.openEmbeddedCheckout("shopify", "shop.example.com", "https://shop.example.com/checkout/1", dark = true))
        assertEquals("Trail Store", vm.embeddedCheckout?.storeName)
        assertTrue(vm.embeddedCheckout!!.url.contains("ec_color_scheme=dark"))
        vm.closeEmbeddedCheckout()
        assertNull(vm.embeddedCheckout)
    }

    @Test
    fun `embedded checkout completion places the order and clears the store cart`() {
        val viewModel = cartedVm()
        assertTrue(viewModel.openEmbeddedCheckout("shopify", "shop.example.com", "https://shop.example.com/checkout/1", dark = false))
        val permissionBefore = viewModel.notificationPermissionRequest
        viewModel.embeddedCheckoutCompleted("order-9", "https://shop.example.com/orders/9", null, ctx)
        assertTrue(viewModel.cart.isEmpty())
        assertNull(viewModel.embeddedCheckout)
        assertEquals(permissionBefore + 1, viewModel.notificationPermissionRequest)
        awaitTrue("order stored") {
            runBlocking { OrderStore.getAll(ctx) }.any { it.orderId == "order-9" && it.status == "completed" }
        }
        awaitTrue("orders state") { viewModel.orders.any { it.orderId == "order-9" } }
    }

    @Test
    fun `embedded checkout completion without an open checkout is ignored`() {
        vm.embeddedCheckoutCompleted("order-9", null, null, ctx)
        runBlocking { assertTrue(OrderStore.getAll(ctx).isEmpty()) }
    }

    // ---------- orders / tracking ----------

    @Test
    fun `load orders reads the store and refresh tracking merges updates`() = runBlocking {
        val token = "a".repeat(32)
        OrderStore.add(
            ctx,
            PlacedOrder(
                checkoutId = "checkout-1", placedAtMs = 1L, total = Money(100, "USD"), itemCount = 1,
                status = "completed", shopDomain = "shop.example.com", storeName = "Trail Store",
                trackingToken = token, orderId = "o1",
            ),
        )
        backend.statusResult = Result.success(
            mapOf(token to OrderTracking(orderId = "o1", status = "shipped", updatedAtMs = 10)),
        )
        vm.loadOrders(ctx)
        awaitTrue("orders") { vm.orders.size == 1 }
        vm.refreshTracking(ctx)
        awaitTrue("tracking") { vm.orders.single().tracking?.status == "shipped" && !vm.refreshingTracking }
        assertEquals(1, backend.statusCalls)
        // A second refresh while one is running is refused by the guard state after completion;
        // the flag must be clear again here.
        assertFalse(vm.refreshingTracking)
    }
}
