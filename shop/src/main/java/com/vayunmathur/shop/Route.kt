package com.vayunmathur.shop

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /**
     * First-run profile gate. The graph root while the saved profile is
     * incomplete; replaced by [Search] via reset once it validates, so Back
     * can never return to the gate. Later profile edits stay in the Profile
     * tab inside [Search] and never use this route.
     */
    @Serializable
    data object ProfileSetup : Route

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

    /** Singleton holding profile state and operations. */
    @Serializable
    data object Profile : Route

    /** Request (with its secret-bearing URL) lives in ShopViewModel, never in saved state. */
    @Serializable
    data object EmbeddedCheckout : Route
}
