package com.vayunmathur.fooddelivery

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CartStore
import com.vayunmathur.fooddelivery.platform.canAddFromSameMerchant
import com.vayunmathur.fooddelivery.platform.pushDetail
import com.vayunmathur.fooddelivery.ui.AccountScreen
import com.vayunmathur.fooddelivery.ui.CartScreen
import com.vayunmathur.fooddelivery.ui.CheckoutScreen
import com.vayunmathur.fooddelivery.ui.DealsScreen
import com.vayunmathur.fooddelivery.ui.EmailVerificationScreen
import com.vayunmathur.fooddelivery.ui.FoodDeliveryTabs
import com.vayunmathur.fooddelivery.ui.HomeScreen
import com.vayunmathur.fooddelivery.ui.OrderTrackingScreen
import com.vayunmathur.fooddelivery.ui.OrdersScreen
import com.vayunmathur.fooddelivery.ui.RestaurantScreen
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage

@Composable
fun Navigation(
    backStack: NavBackStack<Route>,
    cart: androidx.compose.runtime.snapshots.SnapshotStateList<CartItem>,
    onPendingCartItem: (CartItem) -> Unit,
) {
    val context = LocalContext.current
    MainNavigation(backStack) {
        entry<Route.Home>(metadata = ListPage() + SiblingPage()) {
            FoodDeliveryTabs(backStack) {
                HomeScreen(onMerchantClick = { id -> backStack.pushDetail(Route.Restaurant(id)) })
            }
        }
        entry<Route.Cart>(metadata = ListPage() + SiblingPage()) {
            FoodDeliveryTabs(backStack) {
                CartScreen(
                    items = cart,
                    onRemoveItem = {
                        cart.removeAt(it)
                        CartStore.save(context, cart)
                    },
                    onCheckout = { backStack.add(Route.Checkout) },
                    onEditModifiers = { index, modifiers ->
                        cart[index] = cart[index].copy(selectedModifiers = modifiers)
                        CartStore.save(context, cart)
                    },
                )
            }
        }
        entry<Route.Deals>(metadata = ListPage() + SiblingPage()) {
            FoodDeliveryTabs(backStack) {
                DealsScreen(onMerchantClick = { id -> backStack.pushDetail(Route.Restaurant(id)) })
            }
        }
        entry<Route.Orders>(metadata = ListPage() + SiblingPage()) {
            FoodDeliveryTabs(backStack) {
                OrdersScreen(onTrackOrder = { id -> backStack.pushDetail(Route.OrderTracking(id)) })
            }
        }
        entry<Route.Account>(metadata = ListPage() + SiblingPage()) {
            FoodDeliveryTabs(backStack) {
                AccountScreen()
            }
        }
        detailEntries(backStack, cart, context, onPendingCartItem)
    }
}

/**
 * The five bottom-nav tabs as sibling routes (see the route-backed
 * [TabbedPagerScaffold] overload). Selecting a tab resets the back stack to
 * that tab's route; each tab entry renders this scaffold with its own screen
 * as [content], so detail routes (Restaurant, Checkout, OrderTracking) pushed
 * over a tab keep the tab as their list pane on wide layouts. Per-tab
 * `rememberSaveable` state lives in each route's navigation entry.
 */

/** The pushed (non-tab) destinations: restaurant, checkout, tracking, verification. */
private fun com.vayunmathur.library.util.EntryProviderScope<Route>.detailEntries(
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
