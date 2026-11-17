package com.vayunmathur.fooddelivery.network

import com.vayunmathur.library.log.Log
import com.vayunmathur.fooddelivery.data.CheckoutRequest
import com.vayunmathur.fooddelivery.data.CheckoutResponse
import com.vayunmathur.fooddelivery.data.Feedback
import com.vayunmathur.fooddelivery.data.FeedbackRequest
import com.vayunmathur.fooddelivery.data.Order
import com.vayunmathur.fooddelivery.data.OrderRewards
import com.vayunmathur.fooddelivery.data.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Order history, lookup, checkout, feedback and per-order rewards. */
object BitesOrders {

    suspend fun getOrders(): List<Order> = withContext(Dispatchers.Default) {
        try {
            val resp = BitesCore.authenticatedRequest("${BitesCore.API}/orders/past/all")
            if (resp.isSuccess) {
                BitesCore.json.decodeFromString<ApiResponse<List<Order>>>(resp.body).data
                    ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: java.io.IOException) {
            Log.error(TAG, "getOrders failed", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "getOrders failed", e)
            emptyList()
        }
    }

    /** GET /orders/pickUpOrder/{uuid} — marks a pickup order collected. */
    suspend fun pickUpOrder(orderUuid: String): Boolean = try {
        BitesCore.authenticatedRequest(
            "${BitesCore.API}/orders/pickUpOrder/$orderUuid",
        ).isSuccess
    } catch (e: java.io.IOException) {
        Log.error(TAG, "pickUpOrder failed", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.error(TAG, "pickUpOrder failed", e)
        false
    }

    /** GET /orders/email/{token} — look an order up by its email link. */
    suspend fun getOrderByEmail(token: String): Order? = withContext(Dispatchers.Default) {
        try {
            val resp = BitesCore.authenticatedRequest("${BitesCore.API}/orders/email/$token")
            if (!resp.isSuccess) null else BitesCore.unwrap(resp.body, Order.serializer())
        } catch (e: java.io.IOException) {
            Log.error(TAG, "getOrderByEmail failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "getOrderByEmail failed", e)
            null
        }
    }

    /**
     * GET /orders/{uuid}/rewards — the credit applied to a specific order. The reference
     * calls this right before showing the total and subtracts `rewardsAvailable` from the
     * order's component sum (bites-js-decompiled.js:1255938).
     */
    suspend fun getOrderRewards(orderUuid: String): OrderRewards? =
        withContext(Dispatchers.Default) {
            try {
                val resp = BitesCore.authenticatedRequest(
                    "${BitesCore.API}/orders/$orderUuid/rewards",
                )
                if (!resp.isSuccess) {
                    null
                } else {
                    BitesCore.unwrap(resp.body, OrderRewards.serializer())
                }
            } catch (e: java.io.IOException) {
                Log.error(TAG, "getOrderRewards failed", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "getOrderRewards failed", e)
                null
            }
        }

    suspend fun submitFeedback(
        orderUuid: String,
        request: FeedbackRequest,
    ): Boolean = withContext(Dispatchers.Default) {
        try {
            BitesCore.authenticatedRequest(
                "${BitesCore.API}/orders/$orderUuid/feedback",
                "POST",
                BitesCore.json.encodeToString(FeedbackRequest.serializer(), request),
            ).isSuccess
        } catch (e: java.io.IOException) {
            Log.error(TAG, "submitFeedback failed", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "submitFeedback failed", e)
            false
        }
    }

    suspend fun getFeedback(orderUuid: String): Feedback? =
        BitesCore.decodeData(
            "${BitesCore.API}/orders/$orderUuid/feedback",
            Feedback.serializer(),
        )

    suspend fun checkout(
        merchantId: Int,
        request: CheckoutRequest,
    ): CheckoutResponse? = withContext(Dispatchers.Default) {
        try {
            val body = BitesCore.json.encodeToString(CheckoutRequest.serializer(), request)
            val resp = BitesCore.authenticatedRequest(
                "${BitesCore.API}/merchants/$merchantId/checkout",
                "POST",
                body,
            )
            BitesCore.logd { "checkout -> ${resp.status}" }
            if (resp.isSuccess) {
                BitesCore.unwrap(resp.body, CheckoutResponse.serializer())
            } else {
                null
            }
        } catch (e: java.io.IOException) {
            Log.error(TAG, "checkout failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "checkout failed", e)
            null
        }
    }

    private const val TAG = "BitesOrders"
}
