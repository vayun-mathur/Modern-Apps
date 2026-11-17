package com.vayunmathur.fooddelivery

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for browsing, checkout, order tracking, and email verification. */
sealed interface Route : NavKey {
    /** The Home tab (restaurant browse). Sibling of the other tab routes. */
    @Serializable data object Home : Route
    /** The Cart tab. Sibling of the other tab routes. */
    @Serializable data object Cart : Route
    /** The Deals tab. Sibling of the other tab routes. */
    @Serializable data object Deals : Route
    /** The Orders tab. Sibling of the other tab routes. */
    @Serializable data object Orders : Route
    /** The Account tab. Sibling of the other tab routes. */
    @Serializable data object Account : Route
    /** The menu page for the restaurant identified by [id]. */
    @Serializable data class Restaurant(val id: Int) : Route
    /** The checkout flow for the current cart. */
    @Serializable data object Checkout : Route
    /** Live tracking for the order identified by [orderId]. */
    @Serializable data class OrderTracking(val orderId: Int) : Route
    /** Email-verification screen for [customerId] carrying its verification [token]. */
    @Serializable data class EmailVerification(val customerId: String, val token: String) : Route
}
