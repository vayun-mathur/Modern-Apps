package com.vayunmathur.crypto.api

import com.vayunmathur.crypto.token.TokenInfo
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import org.sol4k.Keypair
import kotlin.math.pow

object JupiterAPI {
    private const val API_KEY = "873a272b-baf7-4a7a-b0e6-e689a33430c9"

    suspend fun getPrices(mints: List<String>): PriceResponse {
        return mints.chunked(20).map { try {
            val response: HttpResponse = client.get("https://api.jup.ag/price/v3") {
                header("x-api-key", API_KEY)
                parameter("ids", it.joinToString(","))
            }
            response.body<PriceResponse>()
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
            client.get("https://api.jup.ag/ultra/v1/order") {
                header("x-api-key", API_KEY)
                parameter("inputMint", inputToken.mintAddress)
                parameter("outputMint", outputToken.mintAddress)
                parameter("amount", (amount * 10.0.pow(inputToken.decimals)).toLong())
                parameter("taker", taker.publicKey.toBase58())
            }.body()
        } catch(e: Exception) {
            e.printStackTrace()
            null
        }
    }
}