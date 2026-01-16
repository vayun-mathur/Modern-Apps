package com.vayunmathur.crypto.api

import com.vayunmathur.crypto.token.TokenInfo
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

object PredictionMarket {

    @Serializable
    data class Event(
        val title: String,
        val category: String,
        val ticker: String,
        val seriesTicker: String,
        val imageUrl: String,
        val volume: Long,
        val rules: String,
        val markets: List<Market>
    ) {
        @Serializable
        data class Market(
            val subtitle: String,
            val chance: Double,
            val closeTime: Long,
            val yesPrice: Double,
            val noPrice: Double,
            val yesMint: String,
            val noMint: String
        ) {
            fun isOpen() = closeTime > System.currentTimeMillis() / 1000
        }

        fun anyMarketOpen() = markets.any { it.isOpen() }
    }

    suspend fun getPredictionMarkets(): List<Event> {
        val content = client.get("https://api.vayunmathur.com/crypto/prediction_market_events").body<List<Event>>()
        return content
    }

    suspend fun makeOrder(market: Event.Market, yes: Boolean, amount: Double, publicKey: String): PendingOrder {
        val outputMint = if (yes) market.yesMint else market.noMint
        val inputMint = TokenInfo.USDC.mintAddress
        val slippageBps = 50

        val res = client.get("https://api.vayunmathur.com/crypto/prediction_market/order") {
            parameter("inputMint", inputMint)
            parameter("outputMint", outputMint)
            parameter("amount", (amount*1000000).toInt().toString())
            parameter("slippageBps", slippageBps.toString())
            parameter("userPublicKey", publicKey)
        }

        return res.body<PendingOrder>()
    }
}