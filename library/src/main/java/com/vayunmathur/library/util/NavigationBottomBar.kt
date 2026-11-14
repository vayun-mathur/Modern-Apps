package com.vayunmathur.library.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One bottom-bar destination: label, route key and icon. */
data class BottomBarItem<Route: NavKey>(
    val name: String,
    val route: Route,
    val icon: @Composable () -> Unit
)

/**
 * Height of the bar's items, excluding the system navigation inset beneath it.
 *
 * Exposed so a screen that draws its own floating content above the bar can
 * reserve the right amount of room. Do not use it to build a second kind of
 * bottom bar - the point of [BottomNavBar] is that every app has exactly one
 * shape and one height.
 */
val BottomNavBarHeight = 64.dp

/**
 * The bottom navigation bar, shared by every app.
 *
 * Built on the short navigation bar so the height is the same everywhere; the
 * apps previously used a mix of `FlexibleBottomAppBar` (deliberately
 * variable), the 80dp `NavigationBar`, and this, so bars visibly changed
 * height from app to app.
 *
 * Rides above the keyboard, which is handled here rather than at each call
 * site because the bar has to work in both the places apps put it: some pass
 * it to [MainNavigation]'s `bottomBar` slot, which sits outside the content
 * and gets no inset handling of its own, and others render it inside a page.
 * That slot is why the app store's bar stayed behind the keyboard while the
 * contacts one, drawn inside the page, moved with it.
 *
 * The inset is the union of the bar's normal one and the keyboard rather than
 * the two added together. A visible keyboard already covers the navigation
 * bar, so padding for both would leave the bar floating a navigation bar's
 * height above the keyboard.
 *
 * Takes a content slot rather than a fixed item model because the apps
 * navigate in genuinely different ways - a route back stack, a tab enum, a
 * selected index. Use [BottomNavBarItem] for each entry; there is a
 * [BottomNavBar] overload below for the common back-stack case.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BottomNavBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ShortNavigationBar(
        modifier = modifier,
        windowInsets = ShortNavigationBarDefaults.windowInsets.union(WindowInsets.ime),
        content = content,
    )
}

/** One entry in a [BottomNavBar]. Pass a null [label] for an icon-only item. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BottomNavBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String? = null,
    enabled: Boolean = true,
) {
    ShortNavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = label?.let { { Text(it) } },
        enabled = enabled,
    )
}

/**
 * [BottomNavBar] for the common case: one item per destination in a back stack.
 *
 * [onSelect] defaults to resetting the stack to the tapped route, which is what
 * a tab bar usually wants. Apps that need different semantics - pushing rather
 * than replacing, or collapsing an intermediate screen first - pass their own.
 */
@Composable
fun <Route : NavKey> BottomNavBar(
    backStack: NavBackStack<Route>,
    pages: List<BottomBarItem<out Route>>,
    currentPage: Route,
    modifier: Modifier = Modifier,
    onSelect: (Route) -> Unit = { if (backStack.last() != it) backStack.reset(it) },
) {
    BottomNavBar(modifier) {
        pages.forEach { page ->
            BottomNavBarItem(
                selected = currentPage == page.route,
                onClick = { onSelect(page.route) },
                icon = page.icon,
                label = page.name,
            )
        }
    }
}
