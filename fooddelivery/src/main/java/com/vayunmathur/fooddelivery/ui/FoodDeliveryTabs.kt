package com.vayunmathur.fooddelivery.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.vayunmathur.fooddelivery.Route
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconLocalOffer
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.NavBackStack

@Composable
internal fun FoodDeliveryTabs(
    backStack: NavBackStack<Route>,
    content: @Composable () -> Unit,
) {
    val tabs = remember {
        listOf<RouteTab<Route>>(
            RouteTab(Route.Home, "Home", { IconHome() }),
            RouteTab(Route.Cart, "Cart", { IconShoppingCart() }),
            RouteTab(Route.Deals, "Deals", { IconLocalOffer() }),
            RouteTab(Route.Orders, "Orders", { IconPackage() }),
            RouteTab(Route.Account, "Account", { IconPerson() }),
        )
    }
    TabbedPagerScaffold(backStack = backStack, tabs = tabs) { content() }
}
