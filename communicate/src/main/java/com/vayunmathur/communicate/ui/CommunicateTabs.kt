package com.vayunmathur.communicate.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.Route
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.NavBackStack

/**
 * Route-backed tab host for the three top-level tabs ([Route.Messages],
 * [Route.Dialer], [Route.CallLogs]).
 *
 * The tabs are sibling routes: [TabbedPagerScaffold] selects one by
 * `backStack.reset(tab.route)` (see its [RouteTab] overload), and each tab's
 * screen is the `SiblingPage()` entry for that route in [CommunicateNavGraph],
 * hosted inside the scaffold's content slot. A destination pushed over a tab
 * (a conversation, accounts, a registration flow) renders in the same slot
 * with no tab selected, and picking a tab resets back to that tab.
 * There is no pager: the old host declared `pageCount = { 4 }` for only
 * three tabs, and tab selection lived in pager state instead of the stack.
 */
@Composable
fun CommunicateTabs(backStack: NavBackStack<Route>) {
    val tabs = listOf<RouteTab<Route>>(
        RouteTab(Route.Messages, stringResource(R.string.messages_title), { IconSms() }),
        RouteTab(Route.Dialer, stringResource(R.string.dialer_title), { IconCall() }),
        RouteTab(Route.CallLogs, stringResource(R.string.call_logs_title), { IconHistory() }),
    )
    TabbedPagerScaffold(backStack = backStack, tabs = tabs) {
        CommunicateNavGraph(backStack)
    }
}
