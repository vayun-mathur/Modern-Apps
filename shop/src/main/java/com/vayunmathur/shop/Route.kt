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
    data object Checkout : Route

    @Serializable
    data object Orders : Route
}
