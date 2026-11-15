package com.vayunmathur.shop

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding search state and operations. */
    @Serializable
    data object Search : Route

    /** Immutable value describing product detail. */
    @Serializable
    data class ProductDetail(
        val variantId: String,
        val platformId: String,
        val shopDomain: String,
    ) : Route

    /** Singleton holding cart state and operations. */
    @Serializable
    data object Cart : Route

    /** Singleton holding orders state and operations. */
    @Serializable
    data object Orders : Route

    /** Request (with its secret-bearing URL) lives in ShopViewModel, never in saved state. */
    @Serializable
    data object EmbeddedCheckout : Route
}
