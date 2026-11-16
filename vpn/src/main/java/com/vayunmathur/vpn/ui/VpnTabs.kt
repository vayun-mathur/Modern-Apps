package com.vayunmathur.vpn.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.platform.VpnViewModel

/**
 * The three top-level tabs as sibling routes. The route-backed
 * [TabbedPagerScaffold] owns the tab bar / rail chrome and switches tabs with
 * `backStack.reset`, so each tab is a real destination (deep link, restore and
 * back see it) instead of a pager page inside one `Route.Main`. Detail and
 * BypassList push on top of whichever tab is showing.
 *
 * The Logging leaderboard tab is in-screen state of the Logging destination;
 * it is mirrored into [Route.Logging] (and remembered here for the tab's
 * target route) so returning to Logging restores the leaderboard last viewed.
 */
@Composable
fun VpnTabs(backStack: NavBackStack<Route>, vm: VpnViewModel) {
    var loggingTab by rememberSaveable {
        mutableIntStateOf((backStack.last() as? Route.Logging)?.tab ?: 0)
    }
    // A restored or deep-linked Logging route carries its tab; keep the tab
    // target in sync when the stack top is Logging with a different tab.
    val topLoggingTab = (backStack.last() as? Route.Logging)?.tab
    LaunchedEffect(topLoggingTab) {
        if (topLoggingTab != null && topLoggingTab != loggingTab) loggingTab = topLoggingTab
    }
    val tabs = listOf<RouteTab<Route>>(
        RouteTab(Route.Tunnels, "Tunnels") { IconDashboard() },
        RouteTab(Route.Logging(loggingTab), "Logging") { IconHistory() },
        RouteTab(Route.Settings, "Settings") { IconSettings() },
    )
    TabbedPagerScaffold(backStack = backStack, tabs = tabs) {
        MainNavigation(backStack) {
            entry<Route.Tunnels>(metadata = SiblingPage() + ListPage()) {
                ConfigListPage(backStack, vm)
            }
            entry<Route.Logging>(metadata = SiblingPage()) {
                LoggingPage(
                    vm,
                    initialTab = it.tab,
                    onTabChange = { tab ->
                        loggingTab = tab
                        if (backStack.last() != Route.Logging(tab)) {
                            backStack.setLast(Route.Logging(tab))
                        }
                    },
                )
            }
            entry<Route.Settings>(metadata = SiblingPage()) { SettingsPage(backStack) }
            entry<Route.Detail>(metadata = ListDetailPage() + MorphPage()) {
                ConfigDetailPage(backStack, vm, it.id)
            }
            entry<Route.BypassList>(metadata = ListDetailPage()) { BypassListPage(backStack) }
        }
    }
}
