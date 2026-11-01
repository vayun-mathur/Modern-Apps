package com.vayunmathur.shop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.shop.platform.ShopViewModel
import com.vayunmathur.shop.ui.CartScreen
import com.vayunmathur.shop.ui.CheckoutScreen
import com.vayunmathur.shop.ui.EmbeddedCheckoutScreen
import com.vayunmathur.shop.ui.OrdersScreen
import com.vayunmathur.shop.ui.ProductScreen
import com.vayunmathur.shop.ui.ProfileScreen
import com.vayunmathur.shop.ui.ShopTabs

@Composable
fun Navigation(viewModel: ShopViewModel) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.observeProfile(context) }
    if (!viewModel.profileLoaded) return
    if (!viewModel.profileComplete) {
        ProfileScreen(viewModel, required = true)
        return
    }
    val backStack = rememberNavBackStack<Route>(Route.Search)
    LaunchedEffect(viewModel.pendingRoute) {
        val route = viewModel.pendingRoute ?: return@LaunchedEffect
        if (route == Route.Search) backStack.reset(Route.Search) else backStack.reset(Route.Search, route)
        viewModel.consumePendingRoute()
    }
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
            CartScreen(viewModel, onCheckout = { backStack.add(Route.Checkout) })
        }
        entry<Route.Checkout> {
            CheckoutScreen(
                viewModel = viewModel,
                onBack = { backStack.pop() },
                onPayInApp = { backStack.add(Route.EmbeddedCheckout) },
            )
        }
        entry<Route.EmbeddedCheckout> {
            EmbeddedCheckoutScreen(viewModel = viewModel, onClose = {
                if (backStack.backStack.lastOrNull() == Route.EmbeddedCheckout) backStack.pop()
            })
        }
        entry<Route.Orders> { OrdersScreen(viewModel = viewModel) }
    }
}
