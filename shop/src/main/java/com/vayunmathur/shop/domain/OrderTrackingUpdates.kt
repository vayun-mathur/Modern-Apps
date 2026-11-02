package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.OrderStatusQuery
import com.vayunmathur.shop.data.OrderTracking
import com.vayunmathur.shop.data.PlacedOrder

/** Pure order-tracking bookkeeping shared by the Orders tab and the background worker. */
object OrderTrackingUpdates {
    fun needsTracking(order: PlacedOrder): Boolean =
        order.status == "completed" && order.trackingToken.isNotBlank() && order.tracking?.isFinal != true

    fun queriesToPoll(orders: List<PlacedOrder>): List<OrderStatusQuery> =
        orders.filter(::needsTracking)
            .distinctBy { it.trackingToken }
            .take(MAX_TOKENS)
            .map { OrderStatusQuery(it.trackingToken, it.orderId.ifBlank { null }) }

    data class Merge(val orders: List<PlacedOrder>, val changed: List<PlacedOrder>)

    /** Applies newer tracking; [Merge.changed] holds orders whose status moved past the initial confirmation. */
    fun merge(orders: List<PlacedOrder>, updates: Map<String, OrderTracking>): Merge {
        val changed = mutableListOf<PlacedOrder>()
        val merged = orders.map { order ->
            val update = updates[order.trackingToken]
            val previous = order.tracking
            if (order.trackingToken.isBlank() || update == null || (previous != null && previous.updatedAtMs > update.updatedAtMs)) {
                return@map order
            }
            val next = order.copy(tracking = update, orderId = order.orderId.ifBlank { update.orderId })
            val moved = previous?.status != update.status && !(previous == null && update.status == "confirmed")
            if (moved) changed += next
            next
        }
        return Merge(merged, changed)
    }

    /**
     * Marks the pending session record as placed; falls back to a new record when the
     * session was never persisted (e.g. a merchant handoff URL).
     */
    fun complete(orders: List<PlacedOrder>, sessionId: String?, completed: PlacedOrder): List<PlacedOrder> {
        val index = orders.indexOfFirst {
            sessionId != null && it.checkoutId == sessionId && it.shopDomain == completed.shopDomain
        }
        if (index < 0) return listOf(completed) + orders
        val pending = orders[index]
        val placed = pending.copy(
            placedAtMs = completed.placedAtMs,
            total = completed.total,
            itemCount = completed.itemCount,
            continueUrl = completed.continueUrl.ifBlank { pending.continueUrl },
            status = "completed",
            orderId = completed.orderId,
            storeName = pending.storeName.ifBlank { completed.storeName },
            trackingToken = pending.trackingToken.ifBlank { completed.trackingToken },
        )
        return listOf(placed) + orders.filterIndexed { i, _ -> i != index }
    }

    private const val MAX_TOKENS = 50
}
