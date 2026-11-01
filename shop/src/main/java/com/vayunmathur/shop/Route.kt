package com.vayunmathur.shop

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Search : Route

    @Serializable
    data class ProductDetail(
        val variantId: String,
        val platformId: String,
        val shopDomain: String,
    ) : Route

    @Serializable
    data object Cart : Route

    @Serializable
    data object Orders : Route

    /** Request (with its secret-bearing URL) lives in ShopViewModel, never in saved state. */
    @Serializable
    data object EmbeddedCheckout : Route
}
