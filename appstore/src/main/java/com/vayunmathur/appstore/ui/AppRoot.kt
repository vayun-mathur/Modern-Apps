package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.Route

import com.vayunmathur.appstore.platform.AppSection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.ui.AppDetailPage
import com.vayunmathur.appstore.ui.BrowsePage
import com.vayunmathur.appstore.ui.HomePage
import com.vayunmathur.appstore.ui.LibraryPage
import com.vayunmathur.appstore.ui.ScreenshotViewerScreen
import com.vayunmathur.appstore.ui.SearchPage
import com.vayunmathur.appstore.ui.SourcesPage
import com.vayunmathur.appstore.ui.TrustPage
import com.vayunmathur.appstore.ui.UpdatesPage
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconProvider
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun AppRoot(
    viewModel: AppStoreViewModel,
    externalPackage: String?,
    onExternalPackageHandled: () -> Unit,
) {
    // Home is the start (and deep-link base) tab: an external package pushes
    // Detail over it, so popping Detail lands back on Home.
    val backStack = rememberNavBackStack<Route>(Route.Home)

    LaunchedEffect(externalPackage) {
        val pkg = externalPackage ?: return@LaunchedEffect
        // The route carries the package; the Detail entry loads it. No
        // ViewModel selection is set here, so a restored stack cannot show
        // a different app from the one its top entry names.
        if (backStack.last() != Route.Detail(pkg)) backStack.add(Route.Detail(pkg))
        onExternalPackageHandled()
    }

    fun openDetail(app: UnifiedApp) {
        if (backStack.last() != Route.Detail(app.packageName)) {
            backStack.add(Route.Detail(app.packageName))
        }
    }

    fun openBrowse(section: AppSection) {
        backStack.add(Route.Browse(section.id))
    }

    AppTabs(
        backStack = backStack,
        viewModel = viewModel,
        onAppClick = ::openDetail,
        onOpenBrowse = ::openBrowse,
    )
}

/**
 * The five bottom-nav tabs as sibling routes (see [TabbedPagerScaffold]'s
 * route-backed overload). Selecting a tab resets the stack to that tab, so
 * tab state is the back stack itself — visible to back, deep links and
 * process restore — and each tab entry below is registered [SiblingPage]
 * so switches crossfade instead of sliding like a push. Browse, Trust,
 * Detail and Screenshots are pushed over the tab host as ordinary routes;
 * while one is on top no tab is selected. Per-tab screen state stays in
 * the shared ViewModel (and each entry's saveable state), as with the pager.
 */
@Composable
private fun AppTabs(
    backStack: NavBackStack<Route>,
    viewModel: AppStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    onOpenBrowse: (AppSection) -> Unit,
) {
    val tabs = listOf<RouteTab<Route>>(
        RouteTab(Route.Home, stringResource(R.string.nav_home), { IconHome() }),
        RouteTab(Route.Search, stringResource(R.string.nav_search), { IconSearch() }),
        RouteTab(Route.Updates, stringResource(R.string.nav_updates), { IconDownload() }),
        RouteTab(Route.Library, stringResource(R.string.nav_library), { IconPackage() }),
        RouteTab(Route.Sources, stringResource(R.string.nav_sources), { IconProvider() }),
    )
    TabbedPagerScaffold(backStack = backStack, tabs = tabs) {
        MainNavigation(backStack) {
            entry<Route.Home>(metadata = SiblingPage()) {
                HomePage(
                    viewModel = viewModel,
                    onAppClick = onAppClick,
                    // Cross-tab jump: a sibling switch, not a push.
                    onOpenUpdates = { backStack.reset(Route.Updates) },
                    onOpenBrowse = onOpenBrowse,
                )
            }
            entry<Route.Search>(metadata = SiblingPage()) {
                // This entry only renders while Search is on top, so the
                // page is active by construction: opening it may autofocus,
                // and a neighbour can no longer grab focus mid-swipe.
                SearchPage(
                    viewModel = viewModel,
                    onAppClick = onAppClick,
                    isActive = true,
                )
            }
            entry<Route.Updates>(metadata = SiblingPage()) {
                UpdatesPage(viewModel = viewModel, onAppClick = onAppClick)
            }
            entry<Route.Library>(metadata = SiblingPage()) {
                LibraryPage(viewModel = viewModel, onAppClick = onAppClick)
            }
            entry<Route.Sources>(metadata = SiblingPage()) {
                SourcesPage(
                    viewModel = viewModel,
                    onOpenTrust = { backStack.add(Route.Trust) },
                )
            }
            entry<Route.Browse> { route ->
                BrowsePage(
                    viewModel = viewModel,
                    sectionId = route.sectionId,
                    onAppClick = onAppClick,
                    onBack = { backStack.pop() },
                )
            }
            entry<Route.Trust> {
                TrustPage(
                    ownSigningCertificates = viewModel.ownSigningCertificates,
                    onBack = { backStack.pop() },
                )
            }
            entry<Route.Detail>(metadata = MorphPage()) { route ->
                AppDetailPage(
                    viewModel = viewModel,
                    packageName = route.packageName,
                    onBack = { backStack.pop() },
                    onOpenTrust = { backStack.add(Route.Trust) },
                    onOpenScreenshot = { index ->
                        val app = viewModel.detail.value.app
                        if (app != null && app.screenshots.isNotEmpty()) {
                            backStack.add(Route.Screenshots(app.name, app.screenshots, index))
                        }
                    },
                )
            }
            entry<Route.Screenshots> { route ->
                ScreenshotViewerScreen(
                    appName = route.appName,
                    urls = route.urls,
                    initialIndex = route.index,
                    onClose = { backStack.pop() },
                )
            }
        }
    }
}
