package com.vayunmathur.shop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconReceipt
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.shop.platform.ShopViewModel
import com.vayunmathur.shop.ui.CartScreen
import com.vayunmathur.shop.ui.EmbeddedCheckoutScreen
import com.vayunmathur.shop.ui.OrdersScreen
import com.vayunmathur.shop.ui.ProductScreen
import com.vayunmathur.shop.ui.ProfileScreen
import com.vayunmathur.shop.ui.SearchScreen

@Composable
fun Navigation(
    viewModel: ShopViewModel,
    ordersRequest: Int = 0,
    linkRequest: Pair<Int, String>? = null,
) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.observeProfile(context) }
    if (!viewModel.profileLoaded) return
    if (!viewModel.profileComplete) {
        ProfileScreen(viewModel, required = true)
        return
    }
    // Deep-link initial tab: a cold-start Orders link starts on Orders, not Search.
    val backStack = rememberNavBackStack<Route>(if (ordersRequest > 0) Route.Orders else Route.Search)
    // A later Orders link: direct sibling reset (no ViewModel route handoff).
    LaunchedEffect(ordersRequest) {
        if (ordersRequest > 0 && backStack.last() != Route.Orders) backStack.reset(Route.Orders)
    }
    // Merchant links resolve in the ViewModel, which calls back with the destination;
    // this is the direct backStack call that replaces the old pendingRoute effect.
    LaunchedEffect(linkRequest) {
        val url = linkRequest?.second ?: return@LaunchedEffect
        viewModel.openLink(url) { route ->
            if (route is Route.ProductDetail) backStack.reset(Route.Search, route) else backStack.reset(route)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.loadCart(context)
        viewModel.loadOrders(context)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(viewModel.notificationPermissionRequest) {
        if (viewModel.notificationPermissionRequest > 0 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val tabs = listOf<RouteTab<Route>>(
        RouteTab(Route.Search, stringResource(R.string.search)) { IconSearch() },
        RouteTab(Route.Cart, stringResource(R.string.cart)) { IconShoppingCart() },
        RouteTab(Route.Orders, stringResource(R.string.orders)) { IconReceipt() },
        RouteTab(Route.Profile, "Profile") { IconPerson() },
    )
    // Route-backed tabs: selecting a tab resets the stack to that sibling route.
    // MainNavigation is hosted in the content slot, so detail/checkout destinations
    // pushed over a tab render here with no tab selected, and each entry keeps its
    // own rememberSaveable state under its route.
    TabbedPagerScaffold(backStack = backStack, tabs = tabs) {
        MainNavigation(backStack) {
            entry<Route.Search>(metadata = SiblingPage()) {
                SearchScreen(
                    viewModel = viewModel,
                    onProductClick = { variantId, platformId, shopDomain ->
                        backStack.add(Route.ProductDetail(variantId, platformId, shopDomain))
                    },
                )
            }
            entry<Route.Cart>(metadata = SiblingPage()) {
                CartScreen(viewModel, onPayInApp = { backStack.add(Route.EmbeddedCheckout) })
            }
            entry<Route.Orders>(metadata = SiblingPage()) {
                LaunchedEffect(Unit) { viewModel.refreshTracking(context) }
                OrdersScreen(viewModel = viewModel)
            }
            entry<Route.Profile>(metadata = SiblingPage()) {
                ProfileScreen(viewModel, required = false)
            }
            entry<Route.ProductDetail> { route ->
                ProductScreen(
                    viewModel = viewModel,
                    variantId = route.variantId,
                    platformId = route.platformId,
                    shopDomain = route.shopDomain,
                    onBack = { backStack.pop() },
                    onPayInApp = { backStack.add(Route.EmbeddedCheckout) },
                )
            }
            entry<Route.EmbeddedCheckout> {
                EmbeddedCheckoutScreen(viewModel = viewModel, onClose = {
                    if (backStack.backStack.lastOrNull() == Route.EmbeddedCheckout) backStack.pop()
                })
            }
        }
    }
}
