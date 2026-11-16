package com.vayunmathur.appstore

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.appstore.data.AppStoreDatabaseRepository
import com.vayunmathur.appstore.data.ExternalLinks
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.UpdateCheckWorker
import com.vayunmathur.appstore.ui.AppDetailPage
import com.vayunmathur.appstore.ui.BrowsePage
import com.vayunmathur.appstore.ui.HomePage
import com.vayunmathur.appstore.ui.LibraryPage
import com.vayunmathur.appstore.ui.ScreenshotViewerScreen
import com.vayunmathur.appstore.ui.SearchPage
import com.vayunmathur.appstore.ui.SourcesPage
import com.vayunmathur.appstore.ui.TrustPage
import com.vayunmathur.appstore.ui.UpdatesPage
import com.vayunmathur.appstore.util.AppSection
import com.vayunmathur.appstore.util.AppStoreViewModel
import com.vayunmathur.appstore.util.AppStoreViewModelFactory
import com.vayunmathur.appstore.util.RemoteImageFetchers
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconProvider
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch

/** Entry activity that wires the store UI, navigation and catalog startup. */
class MainActivity : MaActivity() {

    private var factoryState by mutableStateOf<AppStoreViewModelFactory?>(null)

    /** Package named by a `market://` or listing URL we were launched with. */
    private var externalPkg by mutableStateOf<String?>(null)

    private var vmRef: AppStoreViewModel? = null

    override fun onCreateBeforeContent() {
        // STANDARD includes GTS R1-R4 (needed for Play Store -> play.google.com / android.clients.google.com)
        // + ISRG X1/X2 (F-Droid) + DigiCert G2/G3 (GitHub). Covers all appstore hosts without full system ~140 roots.
        NetworkClient.init(this, TrustBundle.STANDARD)
        UpdateCheckWorker.schedule(this)
        // Listing artwork (iconUrl) loads over HTTP(S) through the shared image
        // pipeline; the fetch function is built here from NetworkClient so no
        // library-to-library edge is needed (#884).
        ImageLoader.setDefault(
            ImageLoader.Builder(this).remote(RemoteImageFetchers::network).build(),
        )

        val repository = AppStoreDatabaseRepository.get(this)
        factoryState = AppStoreViewModelFactory(applicationContext, repository.database)
        handleIntentUrl(intent)
    }

    @Composable
    override fun Content() {
        OfflineAware {
            val factory = factoryState
            if (factory == null) {
                Box(Modifier.fillMaxSize())
            } else {
                val vm: AppStoreViewModel = viewModel(factory = factory)
                vmRef = vm
                AppRoot(vm, externalPkg) { externalPkg = null }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Catch installs and uninstalls that completed in the system dialog.
        vmRef?.refreshInstalled()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentUrl(intent)
    }

    private fun handleIntentUrl(intent: Intent?) {
        val pkg = ExternalLinks.parseExternalPackage(intent?.dataString)
        if (!pkg.isNullOrBlank()) externalPkg = pkg
    }
}

@Serializable
/** Navigation destinations for the phone store UI. */
sealed interface Route : NavKey {
    /** Destination showing the store home page. */
    @Serializable data object Main : Route
    /** Destination showing source trust information. */
    @Serializable data object Trust : Route
    /** Destination showing one app's detail page, named by its package. */
    @Serializable data class Detail(val packageName: String) : Route
    /** Destination browsing one home-page section by its ID. */
    @Serializable data class Browse(val sectionId: String) : Route

    /**
     * Full-screen screenshot viewer (#918). Carries its own URL list rather than reading the
     * selected app back out of the view model, so a restored back stack still has its images.
     */
    @Serializable
    data class Screenshots(val appName: String, val urls: List<String>, val index: Int) : Route
}

/**
 * Five tabs and two pushed screens.
 *
 * Sources used to be behind the gear on the home bar, which spent a settings icon on a
 * screen opened perhaps once. It is now a tab of its own, and search — previously a text
 * field wedged above the browse list — has its own tab so browsing and searching no
 * longer fight over the same screen.
 */
@Composable
private fun AppRoot(
    viewModel: AppStoreViewModel,
    externalPackage: String?,
    onExternalPackageHandled: () -> Unit,
) {
    val backStack = rememberNavBackStack<Route>(Route.Main)

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

    MainNavigation(backStack) {
        entry<Route.Main> {
            AppTabs(
                viewModel = viewModel,
                onAppClick = ::openDetail,
                onOpenTrust = { backStack.add(Route.Trust) },
                onOpenBrowse = ::openBrowse,
            )
        }
        entry<Route.Browse> { route ->
            BrowsePage(
                viewModel = viewModel,
                sectionId = route.sectionId,
                onAppClick = ::openDetail,
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

/**
 * The five bottom-nav tabs, hosted in a swipeable pager (see [TabbedPagerScaffold]).
 * Trust and Detail are pushed on top of this host as ordinary routes.
 */
@Composable
private fun AppTabs(
    viewModel: AppStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    onOpenTrust: () -> Unit,
    onOpenBrowse: (AppSection) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 5 })
    val scope = rememberCoroutineScope()
    val tabs = listOf(
        PagerTab(stringResource(R.string.nav_home), { IconHome() }) {
            HomePage(
                viewModel = viewModel,
                onAppClick = onAppClick,
                onOpenUpdates = { scope.launch { pagerState.animateScrollToPage(PAGE_UPDATES) } },
                onOpenBrowse = onOpenBrowse,
            )
        },
        PagerTab(stringResource(R.string.nav_search), { IconSearch() }) {
            // settledPage, not currentPage: currentPage flips to the nearest page part-way
            // through a swipe or an animated jump, and search grabbing focus at that moment
            // pulls the pager onto itself — so going home -> updates would land on search
            // with the keyboard up.
            SearchPage(
                viewModel = viewModel,
                onAppClick = onAppClick,
                isActive = pagerState.settledPage == PAGE_SEARCH,
            )
        },
        PagerTab(stringResource(R.string.nav_updates), { IconDownload() }) {
            UpdatesPage(viewModel = viewModel, onAppClick = onAppClick)
        },
        PagerTab(stringResource(R.string.nav_library), { IconPackage() }) {
            LibraryPage(viewModel = viewModel, onAppClick = onAppClick)
        },
        PagerTab(stringResource(R.string.nav_sources), { IconProvider() }) {
            SourcesPage(viewModel = viewModel, onOpenTrust = onOpenTrust)
        },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}

private const val PAGE_SEARCH = 1
private const val PAGE_UPDATES = 2
