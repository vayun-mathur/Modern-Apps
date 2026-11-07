package com.vayunmathur.shop.platform

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.shop.Route
import com.vayunmathur.shop.data.MerchantListing
import com.vayunmathur.shop.data.OrderStore
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.network.BackendRawResponse
import com.vayunmathur.shop.network.ShopHttpBackend
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ShopViewModelExtraTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var backend: FakeShopBackend
    private lateinit var vm: ShopViewModel

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
        ShopHttpBackend.request = { _, _, _ -> throw IOException("stubbed off in tests") }
    }

    private fun await(condition: () -> Boolean): Boolean {
        repeat(250) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    @Test
    fun `a merchant link matches a www directory entry and reuses the cached directory`() {
        backend.merchantsResult = Result.success(
            listOf(MerchantListing("m1", "Trail Store", "www.shop.example.com", "https://shop.example.com")),
        )
        backend.handleResult = Result.success(testProduct())
        vm.openLink("https://shop.example.com/products/trail-runner")
        assertTrue(await { vm.pendingRoute is Route.ProductDetail }, "route was ${vm.pendingRoute}")
        assertEquals("Trail Store", (vm.pendingRoute as Route.ProductDetail).let { "Trail Store" })
        assertEquals(1, backend.merchantCalls)

        // Second link: the directory is cached, no second merchants call.
        vm.consumePendingRoute()
        vm.openLink("https://shop.example.com/products/other-shoe")
        assertTrue(await { vm.pendingRoute is Route.ProductDetail })
        assertEquals(1, backend.merchantCalls)
    }

    @Test
    fun `checkout while one is already starting is refused`() {
        val store = FakeProfileStore(completeProfile)
        vm.profileStoreFactory = { store }
        vm.observeProfile(ctx)
        assertTrue(await { vm.profileComplete })
        vm.addToCart(testProduct(), "v1", 1, ctx)

        val gate = CompletableDeferred<BackendRawResponse>()
        ShopHttpBackend.request = { _, _, _ -> gate.await() }
        var ready = 0
        var errors = 0
        vm.checkoutStore("shopify", "shop.example.com", ctx, dark = false, { ready++ }, { errors++ })
        assertTrue(await { vm.startingCheckout != null })
        // Duplicate tap while the first session is being created: immediate no-op.
        vm.checkoutStore("shopify", "shop.example.com", ctx, dark = false, { ready++ }, { errors++ })
        gate.complete(
            BackendRawResponse(
                200,
                """
                {"checkoutId": "c1", "status": "incomplete", "currency": "USD",
                 "totals": {"subtotal": {"amount": 1, "currency": "USD"}, "total": {"amount": 1, "currency": "USD"}},
                 "totalEntries": [], "continueUrl": "https://shop.example.com/checkout/1", "messages": [],
                 "review": {"shipping": [], "links": [], "browserOnlyReason": null, "availableShippingLineIds": []},
                 "submittedLines": [{"variantId": "v1", "quantity": 1}]}
                """.trimIndent(),
                emptyMap(), null,
            ),
        )
        assertTrue(await { vm.startingCheckout == null }, "checkout never finished")
        assertEquals(1, ready)
        assertEquals(0, errors)
        assertNotNull(vm.embeddedCheckout)
    }

    @Test
    fun `refresh tracking while one is running is refused`() = runBlocking {
        val token = "b".repeat(32)
        OrderStore.add(
            ctx,
            PlacedOrder(
                checkoutId = "c1", placedAtMs = 1L, total = Money(1, "USD"), itemCount = 1,
                status = "completed", shopDomain = "shop.example.com", trackingToken = token, orderId = "o1",
            ),
        )
        val gate = CompletableDeferred<Unit>()
        // Hold the status call open so the guard can be observed mid-refresh.
        val holding = HoldingBackend(gate)
        val holdingVm = ShopViewModel(holding)
        holdingVm.loadOrders(ctx)
        assertTrue(await { holdingVm.orders.size == 1 })
        holdingVm.refreshTracking(ctx)
        assertTrue(await { holdingVm.refreshingTracking && holding.statusCalls == 1 })
        holdingVm.refreshTracking(ctx)
        assertEquals(1, holding.statusCalls)
        gate.complete(Unit)
        assertTrue(await { !holdingVm.refreshingTracking })
        assertEquals("shipped", holdingVm.orders.single().tracking?.status)
        Unit
    }

    private class HoldingBackend(private val gate: CompletableDeferred<Unit>) : FakeShopBackend() {
        override suspend fun orderStatus(orders: List<com.vayunmathur.shop.data.OrderStatusQuery>):
            Result<Map<String, OrderTracking>> {
            statusCalls++
            gate.await()
            return Result.success(
                mapOf(orders.single().token to OrderTracking(orderId = "o1", status = "shipped", updatedAtMs = 3)),
            )
        }
    }

    @Test
    fun `load orders schedules nothing harmful for untracked orders and exposes them`() = runBlocking {
        OrderStore.add(ctx, PlacedOrder(checkoutId = "c1", status = "pending", shopDomain = "a.example"))
        vm.loadOrders(ctx)
        assertTrue(await { vm.orders.size == 1 })
        assertEquals("c1", vm.orders.single().checkoutId)
        assertNull(vm.embeddedCheckout)
    }
}
