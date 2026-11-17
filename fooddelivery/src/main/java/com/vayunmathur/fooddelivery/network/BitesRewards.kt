package com.vayunmathur.fooddelivery.network

import com.vayunmathur.fooddelivery.data.MerchantRewards
import com.vayunmathur.fooddelivery.data.PlatformSavings
import com.vayunmathur.fooddelivery.data.Reward
import com.vayunmathur.fooddelivery.data.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Platform savings and the generic + merchant reward endpoints. */
object BitesRewards {

    suspend fun getRewards(): List<Reward> = withContext(Dispatchers.Default) {
        try {
            val resp = BitesCore.authenticatedRequest("${BitesCore.API}/rewards")
            if (resp.isSuccess) {
                BitesCore.json.decodeFromString<ApiResponse<List<Reward>>>(resp.body).data
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

    /** GET /customers/me/rewards — reward balance at every merchant, as the reference does. */
    suspend fun getCustomerMerchantRewards(): List<MerchantRewards> =
        BitesCore.decodeDataList(
            "${BitesCore.API}/customers/me/rewards",
            MerchantRewards.serializer(),
        )

    suspend fun getPlatformSavings(): PlatformSavings? =
        BitesCore.decodeData(
            "${BitesCore.API}/orders/savings/platform",
            PlatformSavings.serializer(),
        )
}
