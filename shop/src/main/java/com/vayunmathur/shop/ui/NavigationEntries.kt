package com.vayunmathur.shop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.shop.R
import com.vayunmathur.shop.Route
import com.vayunmathur.shop.platform.ShopViewModel

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

internal fun EntryProviderScope<Route>.shopEntries(
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
    entry<Route.Search>(metadata = ListPage() + SiblingPage()) {
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
    entry<Route.ProductDetail>(metadata = ListDetailPage()) { route ->
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
