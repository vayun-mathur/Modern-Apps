package com.vayunmathur.crypto.data
import com.vayunmathur.crypto.util.api.JupiterAPI
import kotlinx.serialization.Serializable

object TokenPriceRepository : Repository<TokenPriceRepository.PriceData>(PriceData.serializer()) {
    @Serializable
    data class PriceData(val price: Double, val change: Double)

    override val sharedPreferencesName = "token_price_repo"

    override suspend fun getData(): Map<String, PriceData> {
        val prices = mutableMapOf(TokenInfo.SOL.mintAddress to PriceData(130.15, -2.56))
        val prices2 = JupiterAPI.getPrices(TokenInfo.TOKEN_LIST.map { it.mintAddress })
        prices2.forEach { (mint, price) ->
            if(price.usdPrice == null) return@forEach
            prices[mint] = PriceData(price.usdPrice, price.priceChange24h)
        }
        return prices
    }
}
