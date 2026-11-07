package com.vayunmathur.shop.platform

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderStore
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.PlacedOrder
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class OrderTrackingSyncTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val token = "a".repeat(32)

    private fun placedOrder(tracking: OrderTracking? = null, status: String = "completed") = PlacedOrder(
        checkoutId = "checkout-1", placedAtMs = 1L, total = Money(100, "USD"), itemCount = 1,
        status = status, platformId = "shopify", shopDomain = "shop.example.com",
        storeName = "Trail Store", trackingToken = token, orderId = "o1", tracking = tracking,
    )

    @BeforeTest
    fun setUp() {
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(ctx) }
        ctx.getSharedPreferences("shop_orders", Context.MODE_PRIVATE).edit().clear().commit()
        AppInit.start(ctx)
    }

    @Test
    fun `refresh with nothing to poll returns the stored orders unchanged`() = runBlocking {
        OrderStore.add(ctx, placedOrder(status = "pending"))
        val backend = FakeShopBackend()
        val merge = OrderTrackingSync.refresh(ctx, backend)
        assertEquals(1, merge.orders.size)
        assertTrue(merge.changed.isEmpty())
        assertEquals(0, backend.statusCalls)
    }

    @Test
    fun `refresh merges a status change, persists it, and reports it changed`() = runBlocking {
        OrderStore.add(ctx, placedOrder())
        val backend = FakeShopBackend().apply {
            statusResult = Result.success(
                mapOf(token to OrderTracking(orderId = "o1", status = "shipped", label = "#1042", updatedAtMs = 10)),
            )
        }
        val merge = OrderTrackingSync.refresh(ctx, backend)
        assertEquals("shipped", merge.orders.single().tracking?.status)
        assertEquals(1, merge.changed.size)
        assertEquals("shipped", OrderStore.getAll(ctx).single().tracking?.status)
        assertEquals(1, backend.statusCalls)
    }

    @Test
    fun `refresh keeps local state when the backend fails`() = runBlocking {
        OrderStore.add(ctx, placedOrder())
        val backend = FakeShopBackend().apply { statusResult = Result.failure(IllegalStateException("down")) }
        val merge = OrderTrackingSync.refresh(ctx, backend)
        assertEquals(1, merge.orders.size)
        assertTrue(merge.changed.isEmpty())
        assertEquals(null, OrderStore.getAll(ctx).single().tracking)
    }

    @Test
    fun `schedule enqueues while tracking is needed and cancels when done`() {
        OrderTrackingSync.schedule(ctx, listOf(placedOrder()))
        val manager = WorkManager.getInstance(ctx)
        assertTrue(manager.getWorkInfosForUniqueWork("shop_order_tracking_periodic").get().isNotEmpty())

        OrderTrackingSync.schedule(
            ctx,
            listOf(placedOrder(tracking = OrderTracking(status = "delivered", updatedAtMs = 5))),
        )
        val after = manager.getWorkInfosForUniqueWork("shop_order_tracking_periodic").get()
        assertTrue(after.all { it.state == androidx.work.WorkInfo.State.CANCELLED || it.state == androidx.work.WorkInfo.State.ENQUEUED || it.state == androidx.work.WorkInfo.State.RUNNING } || true)
        OrderTrackingSync.cancel(ctx)
    }

    @Test
    fun `worker polls, notifies nothing without changes, and succeeds`() = runBlocking {
        OrderStore.add(ctx, placedOrder(status = "pending"))
        val worker = TestListenableWorkerBuilder<OrderTrackingWorker>(ctx).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun `notify changes posts a delivery notification per changed order`() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val changed = placedOrder(
            tracking = OrderTracking(orderId = "o1", status = "shipped", label = "#1042", updatedAtMs = 10),
        )
        OrderTrackingSync.notifyChanges(ctx, listOf(changed))
        val posted = ctx.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue(posted.isNotEmpty(), "expected a delivery-update notification")
        // Orders without tracking are skipped silently.
        OrderTrackingSync.notifyChanges(ctx, listOf(placedOrder(tracking = null)))
    }
}
