package com.vayunmathur.fooddelivery.api

import com.vayunmathur.fooddelivery.data.Deal
import com.vayunmathur.fooddelivery.data.DealProgress
import com.vayunmathur.fooddelivery.data.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Deal listing, lookup and progress endpoints. */
object BitesDeals {

    suspend fun getDeals(
        lat: Double? = null,
        lng: Double? = null,
        radius: Int = DEFAULT_RADIUS,
    ): List<Deal> = withContext(Dispatchers.Default) {
        try {
            val params = buildString {
                val parts = mutableListOf<String>()
                if (lat != null) parts.add("lat=$lat")
                if (lng != null) parts.add("lng=$lng")
                parts.add("radius=$radius")
                if (parts.isNotEmpty()) append("?${parts.joinToString("&")}")
            }
            val resp = BitesCore.transport(
                "${BitesCore.API}/deals/active$params",
                "GET",
                BitesCore.authHeaders(),
                null,
            )
            if (resp.isSuccess) {
                BitesCore.json.decodeFromString<ApiResponse<List<Deal>>>(resp.body).data
                    ?: emptyList()
            } else {
                emptyList()
            }
        } catch (_: java.io.IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    suspend fun getAllDeals(): List<Deal> =
        BitesCore.decodeDataList("${BitesCore.API}/deals", Deal.serializer())

    suspend fun getDealById(dealId: Int): Deal? =
        BitesCore.decodeData("${BitesCore.API}/deals/$dealId", Deal.serializer())

    suspend fun getDealsByMerchant(merchantId: Int): List<Deal> =
        BitesCore.decodeDataList(
            "${BitesCore.API}/deals/merchant/$merchantId",
            Deal.serializer(),
        )

    suspend fun getActiveDealsByMerchant(merchantId: Int): List<Deal> =
        BitesCore.decodeDataList(
            "${BitesCore.API}/deals/merchant/$merchantId/active",
            Deal.serializer(),
        )

    suspend fun getDealProgress(dealId: Int): DealProgress? =
        BitesCore.decodeData(
            "${BitesCore.API}/deals/$dealId/progress",
            DealProgress.serializer(),
        )

    /** The reference always asks for deals within 8 (miles). */
    private const val DEFAULT_RADIUS = 8
}
