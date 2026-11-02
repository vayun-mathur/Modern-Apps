package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.OrderStatusQuery
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.PlacedOrder
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderTrackingUpdatesTest {
    private val token = "a".repeat(32)
    private val placed = PlacedOrder(checkoutId = "chk-1", shopDomain = "shop.example", status = "completed", trackingToken = token)

    private fun tracking(status: String, at: Long) = OrderTracking(orderId = "o1", status = status, updatedAtMs = at)

    @Test
    fun pollsOnlyPlacedUndeliveredOrdersWithTokens() {
        val orders = listOf(
            placed.copy(orderId = "gid://shopify/Order/1"),
            placed.copy(checkoutId = "chk-2", status = "pending", trackingToken = "b".repeat(32)),
            placed.copy(checkoutId = "chk-3", trackingToken = ""),
            placed.copy(checkoutId = "chk-4", trackingToken = "d".repeat(32), tracking = tracking("delivered", 1)),
            placed.copy(checkoutId = "chk-5", trackingToken = "e".repeat(32)),
        )
        assertEquals(
            listOf(OrderStatusQuery(token, "gid://shopify/Order/1"), OrderStatusQuery("e".repeat(32), null)),
            OrderTrackingUpdates.queriesToPoll(orders),
        )
    }

    @Test
    fun mergeReportsStatusChangesButNotInitialConfirmation() {
        val first = OrderTrackingUpdates.merge(listOf(placed), mapOf(token to tracking("confirmed", 1)))
        assertTrue(first.changed.isEmpty())
        assertEquals("o1", first.orders.single().orderId)
        val shipped = OrderTrackingUpdates.merge(first.orders, mapOf(token to tracking("shipped", 2)))
        assertEquals("shipped", shipped.changed.single().tracking?.status)
        val same = OrderTrackingUpdates.merge(shipped.orders, mapOf(token to tracking("shipped", 3)))
        assertTrue(same.changed.isEmpty())
    }

    @Test
    fun mergeIgnoresOlderSnapshots() {
        val current = placed.copy(tracking = tracking("delivered", 10))
        val merged = OrderTrackingUpdates.merge(listOf(current), mapOf(token to tracking("in_transit", 5)))
        assertEquals("delivered", merged.orders.single().tracking?.status)
        assertTrue(merged.changed.isEmpty())
    }

    @Test
    fun completeUpdatesThePendingRecordInsteadOfAddingOne() {
        val pending = placed.copy(status = "pending", continueUrl = "https://shop.example/checkout")
        val other = placed.copy(checkoutId = "chk-9", trackingToken = "f".repeat(32))
        val done = PlacedOrder(checkoutId = "chk-1", shopDomain = "shop.example", status = "completed",
            orderId = "gid://shopify/Order/1", total = Money(500), placedAtMs = 99, continueUrl = "https://shop.example/orders/1")
        val result = OrderTrackingUpdates.complete(listOf(other, pending), "chk-1", done)
        assertEquals(2, result.size)
        val first = result.first()
        assertEquals("completed", first.status)
        assertEquals(token, first.trackingToken)
        assertEquals("gid://shopify/Order/1", first.orderId)
        assertEquals("https://shop.example/orders/1", first.continueUrl)
    }

    @Test
    fun completeWithoutPendingRecordPrepends() {
        val done = PlacedOrder(checkoutId = "o-1", shopDomain = "shop.example", status = "completed")
        assertEquals(listOf(done, placed), OrderTrackingUpdates.complete(listOf(placed), null, done))
    }

    @Test
    fun legacyStoredOrdersStillDecode() {
        val legacy = """[{"checkoutId":"chk-1","placedAtMs":1,"total":{"amount":100,"currency":"USD"},"status":"completed"}]"""
        val order = Json { ignoreUnknownKeys = true }.decodeFromString<List<PlacedOrder>>(legacy).single()
        assertEquals("", order.trackingToken)
        assertNull(order.tracking)
    }
}
