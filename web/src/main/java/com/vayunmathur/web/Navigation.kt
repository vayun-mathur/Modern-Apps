package com.vayunmathur.web

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.ui.BookmarksPage
import com.vayunmathur.web.ui.BrowserPage
import com.vayunmathur.web.ui.DownloadsPage
import com.vayunmathur.web.ui.HistoryPage
import com.vayunmathur.web.ui.InstalledSitesPage
import com.vayunmathur.web.ui.SettingsPage
import com.vayunmathur.web.ui.ShieldsPage
import com.vayunmathur.web.ui.SiteDataPage

@Composable
fun Navigation(viewModel: WebViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Browser)
    backStack.openSettingsIfRequested(Route.Settings)
    // No list-detail metadata: the browser is not a list and History/Bookmarks
    // are not its details, so every destination is a full-screen push. The
    // previous wiring marked the browser as a list pane to keep it visible
    // beside History on wide windows, but that just squeezed browsing into a
    // narrow pane next to an empty void.
    MainNavigation(backStack) {
        entry<Route.Browser> { BrowserPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.History> { HistoryPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.Bookmarks> { BookmarksPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.Settings> { SettingsPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.Downloads> { DownloadsPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.SiteData> { SiteDataPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.InstalledSites> {
            InstalledSitesPage(viewModel = viewModel, backStack = backStack)
        }
        entry<Route.Shields> { ShieldsPage(viewModel = viewModel, backStack = backStack) }
    }
}
