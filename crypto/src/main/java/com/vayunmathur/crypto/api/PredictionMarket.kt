package com.vayunmathur.crypto.api

import com.vayunmathur.crypto.token.TokenInfo
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable

object PredictionMarket {

    @Serializable
    data class ResponseEvent(
        val ticker: String,
        val seriesTicker: String,
        val strikeDate: String?,
        val strikePeriod: String?,
        val title: String,
        val subtitle: String,
        val imageUrl: String,
        val settlementSources: List<SettlementSource>,
        val volume: Long,
        val volume24h: Long,
        val liquidity: ULong,
        val openInterest: Long,
        val markets: List<Market>
    ) {
        @Serializable
        data class SettlementSource(val name: String, val url: String)

        @Serializable
        data class Market(
            val ticker: String,
            val eventTicker: String,
            val marketType: String,
            val title: String,
            val subtitle: String,
            val yesSubTitle: String,
            val noSubTitle: String,
            val openTime: Long,
            val closeTime: Long,
            val expirationTime: Long,
            val status: String,
            val volume: Long,
            val result: String,
            val openInterest: Long,
            val canCloseEarly: Boolean,
            val earlyCloseCondition: String?,
            val rulesPrimary: String,
            val rulesSecondary: String,
            val yesBid: String?,
            val noBid: String?,
            val yesAsk: String?,
            val noAsk: String?,
            val accounts: Map<String, Account>
        ) {
            val percentage: Double
                get() = (yesBid?.toDouble() ?: yesAsk?.toDouble())!!

            @Serializable
            data class Account(
                val marketLedger: String,
                val yesMint: String,
                val noMint: String,
                val isInitialized: Boolean,
                val redemptionStatus: String?,
            )
        }

        fun toRealEvent(): Event {
            return Event(
                title = title,
                category = "",
                ticker = ticker,
                seriesTicker = seriesTicker,
                imageUrl = imageUrl,
                volume = volume,
                rules = "",
                markets = markets.filter {it.closeTime > System.currentTimeMillis() / 1000}.map {
                    Event.Market(
                        subtitle = it.yesSubTitle,
                        chance = it.percentage,
                        closeTime = it.closeTime,
                        yesPrice = it.yesBid?.toDouble() ?: it.yesAsk?.toDouble() ?: 0.0,
                        noPrice = it.noBid?.toDouble() ?: it.noAsk?.toDouble() ?: 0.0,
                        yesMint = it.accounts[TokenInfo.USDC.mintAddress]!!.yesMint,
                        noMint = it.accounts[TokenInfo.USDC.mintAddress]!!.noMint
                    )
                }
            )
        }
    }

    @Serializable
    data class PredictionMarketPage(val cursor: Int, val events: List<ResponseEvent>)

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

    suspend fun getPredictionMarketsPartial(cursor: Int): Pair<List<Event>, Int> {
        try {
            val content = client.get("https://dev-prediction-markets-api.dflow.net/api/v1/events") {
                parameter("withNestedMarkets", true)
                parameter("sort", "volume")
                parameter("cursor", cursor)
            }.body<PredictionMarketPage>()
            return Pair(content.events.map { it.toRealEvent() }, content.cursor)
        } catch(_: Exception) {
            return Pair(emptyList(), 0)
        }
    }

    suspend fun getPredictionMarkets(): List<Event> {
        val allEvents = mutableListOf<Event>()
        var prevCursor = 0
        while (true) {
            val (events, cursor) = getPredictionMarketsPartial(prevCursor)
            if(cursor == prevCursor || cursor == 0) {
                return allEvents
            }
            prevCursor = cursor
            allEvents += events
        }
    }

    suspend fun makeOrder(market: Event.Market, yes: Boolean, amount: Double, publicKey: String): PendingOrder {
        val outputMint = if (yes) market.yesMint else market.noMint
        val inputMint = TokenInfo.USDC.mintAddress
        val slippageBps = 50

        val res = client.get("https://dev-quote-api.dflow.net/order") {
            parameter("inputMint", inputMint)
            parameter("outputMint", outputMint)
            parameter("amount", (amount*1000000).toInt().toString())
            parameter("slippageBps", slippageBps.toString())
            parameter("userPublicKey", publicKey)
        }

        return res.body<PendingOrder>()
    }
}