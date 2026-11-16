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
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.shop.platform.ShopViewModel
import com.vayunmathur.shop.ui.CartScreen
import com.vayunmathur.shop.ui.EmbeddedCheckoutScreen
import com.vayunmathur.shop.ui.OrdersScreen
import com.vayunmathur.shop.ui.ProductScreen
import com.vayunmathur.shop.ui.ProfileScreen
import com.vayunmathur.shop.ui.SearchScreen

/** Graph root for the current profile state: the gate until the profile validates, then Search. */
internal fun shopStartRoute(profileComplete: Boolean): Route =
    if (profileComplete) Route.Search else Route.ProfileSetup

/**
 * Lifts the profile gate. Only fires once the profile has loaded and validates,
 * and only while [Route.ProfileSetup] is still on the stack — later edits via
 * the Profile tab (which also keep the profile complete) must not reset the
 * user's stack. reset() rather than add(): there is no going back to the gate.
 * Returns true when the stack was reset.
 */
internal fun applyShopProfileGate(
    backStack: NavBackStack<Route>,
    profileLoaded: Boolean,
    profileComplete: Boolean,
): Boolean {
    if (!profileLoaded || !profileComplete) return false
    if (Route.ProfileSetup !in backStack.backStack) return false
    backStack.reset(Route.Search)
    return true
}


/**
 * Applies a resolved deep-link route. While the profile is incomplete the
 * route is left unconsumed (returns false) so it still applies after the gate
 * lifts; the caller re-runs this when completeness changes. Returns true when
 * the route was applied and can be consumed.
 */
internal fun applyShopPendingRoute(
    backStack: NavBackStack<Route>,
    pendingRoute: Route?,
    profileComplete: Boolean,
): Boolean {
    val route = pendingRoute ?: return false
    if (!profileComplete) return false
    if (route == Route.Search) backStack.reset(Route.Search) else backStack.reset(Route.Search, route)
    return true
}

@Composable
fun Navigation(
    viewModel: ShopViewModel,
    ordersRequest: Int = 0,
    cartRequest: Int = 0,
    linkRequest: Pair<Int, String>? = null,
) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.observeProfile(context) }
    // The gate is a graph root, not an early return: before the profile loads
    // it is not known to be complete, so the stack starts at ProfileSetup and
    // the gate effect below lifts it to Search once a complete profile loads
    // (or is saved from the gate itself). A user whose profile is already
    // complete at first composition starts at Search directly.
    val backStack = rememberNavBackStack<Route>(shopStartRoute(viewModel.profileComplete))
    LaunchedEffect(viewModel.profileLoaded, viewModel.profileComplete) {
        applyShopProfileGate(backStack, viewModel.profileLoaded, viewModel.profileComplete)
    }
    // A later Orders link: direct sibling reset (no ViewModel route handoff).
    // While the gate is up the reset is deferred — the gate effect lifts to
    // Search first and this effect re-runs on the stack change.
    LaunchedEffect(ordersRequest, backStack.backStack.lastOrNull()) {
        if (ordersRequest > 0 && backStack.last() != Route.Orders &&
            backStack.backStack.lastOrNull() != Route.ProfileSetup
        ) {
            backStack.reset(Route.Orders)
        }
    }
    // Assistant cart handoff (shop://cart): same deferred-until-ungated reset.
    LaunchedEffect(cartRequest, backStack.backStack.lastOrNull()) {
        if (cartRequest > 0 && backStack.last() != Route.Cart &&
            backStack.backStack.lastOrNull() != Route.ProfileSetup
        ) {
            backStack.reset(Route.Cart)
        }
    }
    // Merchant links resolve in the ViewModel, which calls back with the destination;
    // this is the direct backStack call that replaces the old pendingRoute effect.
    // While gated, the link is left unconsumed and applies after the gate lifts.
    LaunchedEffect(linkRequest, backStack.backStack.lastOrNull()) {
        val url = linkRequest?.second ?: return@LaunchedEffect
        if (backStack.backStack.lastOrNull() == Route.ProfileSetup) return@LaunchedEffect
        viewModel.openLink(url) { route ->
            if (route is Route.ProductDetail || route == Route.Search) {
                applyShopPendingRoute(backStack, route, profileComplete = true)
            } else {
                backStack.reset(route)
            }
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
    if (backStack.backStack.lastOrNull() == Route.ProfileSetup) {
        // Gate up: no tab bar — the required profile form is the whole graph.
        MainNavigation(backStack) { shopEntries(backStack, viewModel, context) }
        return
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
        MainNavigation(backStack) { shopEntries(backStack, viewModel, context) }
    }
}

private fun com.vayunmathur.library.util.EntryProviderScope<Route>.shopEntries(
    backStack: NavBackStack<Route>,
    viewModel: ShopViewModel,
    context: android.content.Context,
) {
    entry<Route.ProfileSetup> {
        // Still inside the graph while loading: nothing renders until the
        // repository emits, matching the previous blank-until-loaded gate,
        // then the same required profile form as before.
        if (viewModel.profileLoaded) ProfileScreen(viewModel, required = true)
    }
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
