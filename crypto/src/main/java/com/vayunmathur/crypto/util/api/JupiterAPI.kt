package com.vayunmathur.crypto.util.api
import com.vayunmathur.crypto.data.TokenInfo
import com.vayunmathur.library.network.NetworkClient
import org.sol4k.Keypair
import kotlin.math.pow

object JupiterAPI {
    private val API_KEY get() = com.vayunmathur.crypto.BuildConfig.JUPITER_API_KEY

    suspend fun getPrices(mints: List<String>): PriceResponse {
        return mints.chunked(20).map { try {
            NetworkClient.getJson<PriceResponse>(
                url = "https://api.jup.ag/price/v3?ids=${it.joinToString(",")}",
                headers = mapOf("x-api-key" to API_KEY)
            )
        } catch(_: Exception) {
            emptyMap()
        } }.fold(emptyMap()) { acc, map -> acc + map }
    }

    suspend fun createOrder(
        inputToken: TokenInfo,
        outputToken: TokenInfo,
        amount: Double,
        taker: Keypair
    ): PendingOrder? {
        return try {
            val url = "https://api.jup.ag/ultra/v1/order?" + 
                "inputMint=${inputToken.mintAddress}&" +
                "outputMint=${outputToken.mintAddress}&" +
                "amount=${(amount * 10.0.pow(inputToken.decimals)).toLong()}&" +
                "taker=${taker.publicKey.toBase58()}"
            
            NetworkClient.getJson<PendingOrder>(
                url = url,
                headers = mapOf("x-api-key" to API_KEY)
            )
        } catch(e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
