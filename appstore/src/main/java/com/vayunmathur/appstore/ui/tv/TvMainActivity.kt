package com.vayunmathur.appstore.ui.tv
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.platform.TvStoreViewModel
import com.vayunmathur.appstore.platform.TvStoreViewModelFactory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.appstore.data.AppStoreDatabaseRepository
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.UpdateCheckWorker
import com.vayunmathur.library.tv.MaTvTheme
import com.vayunmathur.library.tv.TvAccent
import com.vayunmathur.library.tv.TvAppFrame
import com.vayunmathur.library.tv.TvRouteDestination
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconProvider
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

/**
 * TV-only destinations: the five rail tabs as sibling routes, plus pushed
 * browse and detail pages.
 *
 * A separate sealed interface from the phone `Route`, following uniflix's
 * `TvRoute`, so a TV destination can never leak into the phone back stack or
 * vice versa. Home, search, updates, library and sources are sibling rail
 * routes: selecting one resets the back stack to that route (see the
 * route-backed [TvAppFrame]), so the tab on screen is the back stack itself —
 * visible to BACK, deep links and process restore — and each tab keeps its
 * own entry so focus restores per destination. There is no `Main` wrapper:
 * [Home] is the start destination and the rail host is whichever tab entry
 * is on top.
 */
@Serializable
sealed interface TvStoreRoute : NavKey {
    /** Rail tab: store home (the start destination). */
    @Serializable
    data object Home : TvStoreRoute

    /** Rail tab: search. */
    @Serializable
    data object Search : TvStoreRoute

    /** Rail tab: available updates. */
    @Serializable
    data object Updates : TvStoreRoute

    /** Rail tab: installed-app library. */
    @Serializable
    data object Library : TvStoreRoute

    /** Rail tab: store sources. */
    @Serializable
    data object Sources : TvStoreRoute

    /** One home section's full catalogue, pushed over the rail tab that opened it. */
    @Serializable
    data class Browse(val sectionId: String) : TvStoreRoute

    /** One app's page, pushed over the rail tab that opened it. */
    @Serializable
    data class Detail(val packageName: String) : TvStoreRoute
}

/**
 * The TV entry: opened from TV launcher rows (LEANBACK_LAUNCHER). The phone MainActivity
 * stays exactly as it is — this activity hosts the D-pad store UI.
 *
 * Browse, search, updates, library and detail are nav-graph destinations. The ViewModel
 * is the phone repositories reused verbatim, so installs go through the ordinary
 * pipeline including the TV-compat gate.
 */
class TvMainActivity : MaActivity() {
    override val edgeToEdge: Boolean = false

    override fun onCreateBeforeContent() {
        // Same arming the phone does: keep the 12-hour update check scheduled from the TV
        // entry too, so a TV-driven install still gets its update notifications — which
        // route back here (see UpdateCheckWorker).
        UpdateCheckWorker.schedule(this)
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        // Always dark with the store's teal accent: a TV has no wallpaper to derive a
        // scheme from, and the accent marks this TV app at a glance.
        MaTvTheme(accent = TvAccent.Teal, content = content)
    }

    @Composable
    override fun Content() {
        val repository = remember { AppStoreDatabaseRepository.get(this) }
        val factory = remember {
            TvStoreViewModelFactory(this, repository.database)
        }
        val viewModel: TvStoreViewModel = viewModel(factory = factory)
        TvStoreNavigation(viewModel = viewModel)
    }
}

/**
 * The TV navigation host: [rememberNavBackStack] + [MainNavigation] over [TvStoreRoute].
 *
 * [TvStoreRoute.Home] is the start destination. The five rail tabs are
 * sibling entries (each [SiblingPage], so tab switches crossfade instead of
 * sliding like a push) hosted by the route-backed [TvAppFrame] in
 * [TvStoreRail]; browse and detail push over whichever tab opened them, so
 * BACK from them lands back on that tab. Their entries bind to their route
 * arguments ([TvStoreRoute.Browse.sectionId] /
 * [TvStoreRoute.Detail.packageName]) and load exactly the section/app they
 * name, so BACK and a restored stack always land on the destination that
 * opened them with no ViewModel selection behind it.
 */
@Composable
private fun TvStoreNavigation(viewModel: TvStoreViewModel) {
    val backStack = rememberNavBackStack<TvStoreRoute>(TvStoreRoute.Home)

    fun openDetail(app: UnifiedApp) {
        if (backStack.last() != TvStoreRoute.Detail(app.packageName)) {
            backStack.add(TvStoreRoute.Detail(app.packageName))
        }
    }

    MainNavigation(backStack) {
        entry<TvStoreRoute.Home>(metadata = SiblingPage()) {
            TvStoreRail(backStack = backStack, viewModel = viewModel) {
                TvStoreHome(
                    viewModel = viewModel,
                    onAppClick = ::openDetail,
                    // Cross-tab jump: a sibling switch, not a push — the
                    // phone's AppRoot does the same via backStack.reset.
                    onOpenUpdates = { backStack.reset(TvStoreRoute.Updates) },
                    onOpenBrowse = { section ->
                        backStack.add(TvStoreRoute.Browse(section.id))
                    },
                )
            }
        }
        entry<TvStoreRoute.Search>(metadata = SiblingPage()) {
            TvStoreRail(backStack = backStack, viewModel = viewModel) {
                TvStoreSearch(
                    viewModel = viewModel,
                    onAppClick = ::openDetail,
                    // The keyboard opens only on an explicit CENTER press on the field;
                    // arriving via the rail never starts editing.
                    startEditing = false,
                )
            }
        }
        entry<TvStoreRoute.Updates>(metadata = SiblingPage()) {
            TvStoreRail(backStack = backStack, viewModel = viewModel) {
                TvStoreUpdates(viewModel = viewModel, onAppClick = ::openDetail)
            }
        }
        entry<TvStoreRoute.Library>(metadata = SiblingPage()) {
            TvStoreRail(backStack = backStack, viewModel = viewModel) {
                TvStoreLibrary(viewModel = viewModel, onAppClick = ::openDetail)
            }
        }
        entry<TvStoreRoute.Sources>(metadata = SiblingPage()) {
            TvStoreRail(backStack = backStack, viewModel = viewModel) {
                TvStoreSources(viewModel = viewModel)
            }
        }
        entry<TvStoreRoute.Browse> { route ->
            TvStoreBrowse(
                viewModel = viewModel,
                sectionId = route.sectionId,
                onAppClick = ::openDetail,
                onBack = { backStack.pop() },
            )
        }
        entry<TvStoreRoute.Detail> { route ->
            TvStoreDetail(
                viewModel = viewModel,
                packageName = route.packageName,
                onBack = { backStack.pop() },
            )
        }
    }
}

/**
 * One rail tab's frame: the route-backed [TvAppFrame] rail (Home, Search,
 * Updates with count, Library, Sources — the phone's bottom bar turned
 * sideways) around [content]. Only composed while a rail tab is on top —
 * pushed browse/detail pages get no rail.
 *
 * Rail selection derives from the back stack's top route and focusing or
 * clicking a rail item resets the stack to that tab (sibling semantics, in
 * the library frame); the frame's `focusRestorer` per selected route is also
 * the library's. BACK on a root tab returns to the rail; BACK from the rail
 * exits.
 */
@Composable
private fun TvStoreRail(
    backStack: NavBackStack<TvStoreRoute>,
    viewModel: TvStoreViewModel,
    content: @Composable () -> Unit,
) {
    val updates by viewModel.updatesUi.collectAsState()
    val updateCount = updates.updates.size
    // @Composable string resolutions hoisted: the destination list below is plain data.
    val homeLabel = stringResource(R.string.nav_home)
    val searchLabel = stringResource(R.string.nav_search)
    val updatesLabel = if (updateCount > 0) {
        stringResource(R.string.tv_updates_with_count, updateCount)
    } else {
        stringResource(R.string.nav_updates)
    }
    val libraryLabel = stringResource(R.string.nav_library)
    val sourcesLabel = stringResource(R.string.nav_sources)
    val destinations: List<TvRouteDestination<TvStoreRoute>> = remember(
        homeLabel, searchLabel, updatesLabel, libraryLabel, sourcesLabel
    ) {
        listOf(
            TvRouteDestination(TvStoreRoute.Home, homeLabel) { IconHome() },
            TvRouteDestination(TvStoreRoute.Search, searchLabel) { IconSearch() },
            TvRouteDestination(TvStoreRoute.Updates, updatesLabel) { IconDownload() },
            TvRouteDestination(TvStoreRoute.Library, libraryLabel) { IconPackage() },
            TvRouteDestination(TvStoreRoute.Sources, sourcesLabel) { IconProvider() },
        )
    }

    TvAppFrame(
        backStack = backStack,
        destinations = destinations,
        backReturnsToRail = backStack.backStack.size <= 1,
    ) { content() }
}
