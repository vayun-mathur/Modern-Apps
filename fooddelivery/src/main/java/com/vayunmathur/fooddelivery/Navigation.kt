package com.vayunmathur.fooddelivery

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CartStore
import com.vayunmathur.fooddelivery.platform.pushDetail
import com.vayunmathur.fooddelivery.ui.AccountScreen
import com.vayunmathur.fooddelivery.ui.CartScreen
import com.vayunmathur.fooddelivery.ui.DealsScreen
import com.vayunmathur.fooddelivery.ui.detailEntries
import com.vayunmathur.fooddelivery.ui.FoodDeliveryTabs
import com.vayunmathur.fooddelivery.ui.HomeScreen
import com.vayunmathur.fooddelivery.ui.OrdersScreen
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
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

