package com.vayunmathur.fooddelivery.ui

import com.vayunmathur.fooddelivery.Route
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CartStore
import com.vayunmathur.fooddelivery.platform.canAddFromSameMerchant
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack

/**
 * The five bottom-nav tabs as sibling routes (see the route-backed
 * [TabbedPagerScaffold] overload). Selecting a tab resets the back stack to
 * that tab's route; each tab entry renders this scaffold with its own screen
 * as [content], so detail routes (Restaurant, Checkout, OrderTracking) pushed
 * over a tab keep the tab as their list pane on wide layouts. Per-tab
 * `rememberSaveable` state lives in each route's navigation entry.
 */

/** The pushed (non-tab) destinations: restaurant, checkout, tracking, verification. */
internal fun EntryProviderScope<Route>.detailEntries(
    backStack: NavBackStack<Route>,
    cart: androidx.compose.runtime.snapshots.SnapshotStateList<CartItem>,
    context: android.content.Context,
    onPendingCartItem: (CartItem) -> Unit,
) {
    entry<Route.Restaurant>(metadata = ListDetailPage() + MorphPage()) { route ->
        RestaurantScreen(
            merchantId = route.id,
            onBack = { backStack.pop() },
            onAddToCart = { item ->
                if (cart.canAddFromSameMerchant(item)) {
                    cart.add(item)
                    CartStore.save(context, cart)
                } else {
                    onPendingCartItem(item)
                }
            },
        )
    }
    // Checkout is a flow step, not a detail of the tab list.
    entry<Route.Checkout>(metadata = ListDetailPage()) {
        CheckoutScreen(
            items = cart,
            onBack = { backStack.pop() },
            onOrderPlaced = {
                backStack.reset(Route.Orders)
            },
            onOrderCompleted = {
                cart.clear()
                CartStore.clear(context)
            },
        )
    }
    // The brief keeps order-tracking full-screen custom; metadata still marks it a detail
    // so medium widths show it two-pane instead of covering the list.
    entry<Route.OrderTracking>(metadata = ListDetailPage() + MorphPage()) { route ->
        OrderTrackingScreen(orderId = route.orderId, onBack = { backStack.pop() })
    }
    entry<Route.EmailVerification>(metadata = ListDetailPage()) { route ->
        EmailVerificationScreen(
            customerId = route.customerId,
            token = route.token,
            onDone = { backStack.pop() },
        )
    }
}
