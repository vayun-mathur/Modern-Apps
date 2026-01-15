package com.vayunmathur.crypto.token

import kotlinx.serialization.Serializable

@Serializable
data class Token(
    val tokenInfo: TokenInfo,
    val amount: Double
) {
    val totalValue: Double
        get() = (TokenPriceRepository[tokenInfo]?.price ?: 0.0) * amount
}