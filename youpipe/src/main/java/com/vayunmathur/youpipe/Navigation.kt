package com.vayunmathur.youpipe

import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.ui.detailEntries
import com.vayunmathur.youpipe.ui.navigationTabs
import com.vayunmathur.youpipe.ui.settingsEntries
import com.vayunmathur.youpipe.ui.siblingEntries

@Composable
fun Navigation(initialBackStack: List<Route>, ypvm: YouPipeViewModel) {
    val backStack = rememberNavBackStack(initialBackStack)
    val tabs = navigationTabs()
    // Route-backed tabs: selecting a tab resets the stack to that sibling
    // (see TabbedPagerScaffold), and MainNavigation renders the top route —
    // a tab with SiblingPage() crossfade, or a detail/settings sub-route
    // pushed over it with no tab selected.
    TabbedPagerScaffold(backStack = backStack, tabs = tabs) {
        MainNavigation(backStack) {
            siblingEntries(backStack, ypvm)
            detailEntries(backStack, ypvm)
            settingsEntries(backStack, ypvm)
        }
    }
}
