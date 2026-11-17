package com.vayunmathur.fooddelivery.platform

import com.vayunmathur.fooddelivery.Route
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.library.util.NavBackStack

/**
 * Re-tapping into the same destination class replaces the detail instead of stacking it.
 *
 * On a two-pane layout the list stays visible under its detail, so tapping a second
 * restaurant (or order) would otherwise pile Restaurant on Restaurant; back would then
 * step through stale details instead of leaving.
 */
internal fun NavBackStack<Route>.pushDetail(route: Route) {
    if (backStack.isNotEmpty() && last()::class == route::class) setLast(route) else add(route)
}

/**
 * Checkout posts every cart line to a single merchant's endpoint
 * (`/merchants/{id}/checkout`, keyed off the first line's merchant), so a cart may
 * only ever hold one merchant's items. A line with no merchant (0) carries no
 * evidence against the cart's merchant and is allowed through.
 */
internal fun List<CartItem>.canAddFromSameMerchant(item: CartItem): Boolean {
    val cartMerchant = firstOrNull()?.merchantId ?: return true
    return cartMerchant == 0 || item.merchantId == 0 || cartMerchant == item.merchantId
}


/**
 * The tab route for a legacy `initialTab` index (0=Home, 1=Cart, 2=Deals,
 * 3=Orders, 4=Account), coerced into range. Tabs are sibling routes now, so
 * an initial tab is simply the back stack's start route.
 */
internal fun tabRouteFor(initialTab: Int): Route = when (initialTab.coerceIn(FIRST_TAB, LAST_TAB)) {
    FIRST_TAB -> Route.Home
    CART_TAB -> Route.Cart
    DEALS_TAB -> Route.Deals
    ORDERS_TAB -> Route.Orders
    else -> Route.Account
}

/** Index of the first (Home) tab in a legacy `initialTab`. */
private const val FIRST_TAB = 0

/** Index of the Cart tab in a legacy `initialTab`. */
private const val CART_TAB = 1

/** Index of the Deals tab in a legacy `initialTab`. */
private const val DEALS_TAB = 2

/** Index of the Orders tab in a legacy `initialTab`. */
private const val ORDERS_TAB = 3

/** Index of the last (Account) tab in a legacy `initialTab`. */
private const val LAST_TAB = 4
