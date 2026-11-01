package com.vayunmathur.fooddelivery.api

import com.vayunmathur.library.log.Log
import com.vayunmathur.fooddelivery.data.CheckoutAddress
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.data.MerchantDetail
import com.vayunmathur.fooddelivery.data.MerchantsWrapper
import com.vayunmathur.fooddelivery.data.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject

/** Merchant catalog and serviceability endpoints. */
object BitesMerchants {

    suspend fun getMerchants(
        lat: Double? = null,
        lng: Double? = null,
    ): List<Merchant> = withContext(Dispatchers.Default) {
        try {
            val params = buildString {
                val parts = mutableListOf<String>()
                if (lat != null) parts.add("lat=$lat")
                if (lng != null) parts.add("lng=$lng")
                if (parts.isNotEmpty()) append("?${parts.joinToString("&")}")
            }
            val resp = BitesCore.authenticatedRequest("${BitesCore.API}/merchants/all/stores$params")
            if (resp.isSuccess) {
                val wrapper =
                    BitesCore.json.decodeFromString<ApiResponse<MerchantsWrapper>>(resp.body)
                wrapper.data?.merchants ?: emptyList()
            } else {
                emptyList()
            }
        } catch (_: java.io.IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    suspend fun getMerchantDetail(id: Int): MerchantDetail? =
        withContext(Dispatchers.Default) {
            try {
                val resp = BitesCore.authenticatedRequest("${BitesCore.API}/merchants/$id")
                if (resp.isSuccess) {
                    val wrapper: ApiResponse<MerchantDetail> =
                        BitesCore.json.decodeFromString(resp.body)
                    wrapper.data
                } else {
                    null
                }
            } catch (_: java.io.IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    /** POST /merchants/{id}/check-serviceability — body is {address}. */
    suspend fun checkServiceability(
        merchantId: Int,
        address: CheckoutAddress,
    ): Boolean? = withContext(Dispatchers.Default) {
        try {
            val body = buildJsonObject {
                put("address", BitesCore.json.encodeToJsonElement(CheckoutAddress.serializer(), address))
            }
            val resp = BitesCore.authenticatedRequest(
                "${BitesCore.API}/merchants/$merchantId/check-serviceability",
                "POST",
                body.toString(),
            )
            if (resp.isSuccess) {
                true
            } else if (resp.status in
                BitesCore.HTTP_CLIENT_ERROR_MIN..BitesCore.HTTP_CLIENT_ERROR_MAX
            ) {
                false
            } else {
                null
            }
        } catch (e: java.io.IOException) {
            Log.error(TAG, "checkServiceability failed", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "checkServiceability failed", e)
            null
        }
    }

    suspend fun getMerchantByName(storefrontAlias: String): MerchantDetail? =
        BitesCore.decodeData(
            "${BitesCore.API}/merchants/storefront/$storefrontAlias",
            MerchantDetail.serializer(),
        )

    suspend fun getMerchantReporting(merchantId: Int): String? = try {
        BitesCore.authenticatedRequest(
            "${BitesCore.API}/merchants/reporting/$merchantId",
        ).takeIf { it.isSuccess }?.body
    } catch (e: java.io.IOException) {
        Log.error(TAG, "getMerchantReporting failed", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.error(TAG, "getMerchantReporting failed", e)
        null
    }

    private const val TAG = "BitesMerchants"
}
