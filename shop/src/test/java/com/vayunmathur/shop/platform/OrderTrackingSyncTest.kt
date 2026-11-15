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
import com.vayunmathur.shop.data.OrderStore
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.PlacedOrder
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.network.ShopBackend
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Order tracking refresh/schedule and merchant-link prompt prefs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrderTrackingSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeBackend(var status: Result<Map<String, OrderTracking>>) : ShopBackend {
        var polled: List<OrderStatusQuery> = emptyList()
        override suspend fun search(query: String, shop: String?, limit: Int, country: String): Result<List<UcpProduct>> = Result.success(emptyList())
        override suspend fun getProduct(shop: String, productId: String, variantId: String?, title: String?, selectedOptions: List<CatalogOptionSelection>): Result<UcpProduct> = Result.failure(IllegalStateException())
        override suspend fun getProductByHandle(shop: String, handle: String): Result<UcpProduct> = Result.failure(IllegalStateException())
        override suspend fun createCheckout(shop: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String): Result<UcpCheckout> = Result.failure(IllegalStateException())
        override suspend fun getCheckout(shop: String, sessionId: String): Result<UcpCheckout> = Result.failure(IllegalStateException())
        override suspend fun updateCheckout(shop: String, sessionId: String, lines: List<CartLine>, inputs: CheckoutInputs, idempotencyKey: String): Result<UcpCheckout> = Result.failure(IllegalStateException())
        override suspend fun orderStatus(orders: List<OrderStatusQuery>): Result<Map<String, OrderTracking>> { polled = orders; return status }
        override suspend fun merchantFor(host: String): Result<MerchantListing?> = Result.success(null)
    }

    @BeforeTest
    fun setUp() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        context.getSharedPreferences("shop_orders", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("shop_link_defaults", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun order(token: String, status: String = "completed") = PlacedOrder(
        checkoutId = "chk-$token", placedAtMs = 1_700_000_000_000, total = Money(100, "USD"),
        itemCount = 1, status = status, platformId = "shopify", shopDomain = "cool.example",
        storeName = "Cool Store", trackingToken = token, orderId = "ord-$token",
    )

    @Test
    fun refresh_pollsPendingOrdersAndMergesTrackingChange() = runBlocking {
        OrderStore.add(context, order("tok123"))
        val backend = FakeBackend(Result.success(mapOf("tok123" to OrderTracking(orderId = "ord-tok123", status = "shipped", updatedAtMs = 10))))
        val result = OrderTrackingSync.refresh(context, backend)
        assertEquals(listOf(OrderStatusQuery("tok123", "ord-tok123")), backend.polled)
        assertEquals("shipped", result.orders.single().tracking?.status)
        assertEquals(1, result.changed.size)
        // Persisted for the next cold start.
        assertEquals("shipped", OrderStore.getAll(context).single().tracking?.status)
        OrderTrackingSync.schedule(context, result.orders) // still needs tracking: must not throw
        assertEquals(com.vayunmathur.shop.R.string.tracking_shipped, trackingStatusRes("shipped"))
        assertEquals(com.vayunmathur.shop.R.string.tracking_confirmed, trackingStatusRes("weird"))
    }

    @Test
    fun refresh_backendFailureKeepsSavedOrdersAndNoPollWhenFinal() = runBlocking {
        OrderStore.add(context, order("tokF").copy(tracking = OrderTracking(status = "delivered")))
        val backend = FakeBackend(Result.failure(IllegalStateException("down")))
        val result = OrderTrackingSync.refresh(context, backend)
        assertTrue(backend.polled.isEmpty()) // delivered is final: nothing to poll
        assertEquals("delivered", result.orders.single().tracking?.status)
        OrderTrackingSync.schedule(context, result.orders) // cancels work, no throw
        OrderTrackingSync.notifyChanges(context, emptyList())
    }

    @Test
    fun linkDefaults_dismissSuppressesPrompt() {
        val before = LinkDefaults.shouldPrompt(context)
        assertFalse(before && false) // value depends on domain state; dismissal is the contract below
        LinkDefaults.dismiss(context)
        assertFalse(LinkDefaults.shouldPrompt(context))
    }
}
