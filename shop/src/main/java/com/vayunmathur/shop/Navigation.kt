package com.vayunmathur.shop

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.shop.platform.ShopViewModel
import com.vayunmathur.shop.ui.CartScreen
import com.vayunmathur.shop.ui.CheckoutScreen
import com.vayunmathur.shop.ui.OrdersScreen
import com.vayunmathur.shop.ui.ProductScreen
import com.vayunmathur.shop.ui.ShopTabs

@Composable
fun Navigation(viewModel: ShopViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Search)
    MainNavigation(backStack) {
        entry<Route.Search> {
            ShopTabs(
                viewModel = viewModel,
                onProductClick = { variantId, platformId, shopDomain ->
                    backStack.add(Route.ProductDetail(variantId, platformId, shopDomain))
                },
                onCheckout = { backStack.add(Route.Checkout) },
            )
        }
        entry<Route.ProductDetail> { route ->
            ProductScreen(
                viewModel = viewModel,
                variantId = route.variantId,
                platformId = route.platformId,
                shopDomain = route.shopDomain,
                onBack = { backStack.pop() },
                onCheckout = { backStack.add(Route.Checkout) },
            )
        }
        entry<Route.Cart> {
            CartScreen(viewModel, onBack = { backStack.pop() }, onCheckout = { backStack.add(Route.Checkout) })
        }
        entry<Route.Checkout> {
            CheckoutScreen(viewModel = viewModel, onBack = { backStack.pop() })
        }
        entry<Route.Orders> { OrdersScreen(viewModel = viewModel) }
    }
}
