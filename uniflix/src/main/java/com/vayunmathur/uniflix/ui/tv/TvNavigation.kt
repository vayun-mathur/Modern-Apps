package com.vayunmathur.uniflix.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.tv.TvAppFrame
import com.vayunmathur.library.tv.TvRouteDestination
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconMovie
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconTv
import com.vayunmathur.library.ui.IconWidgets
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.domain.MediaType

/**
 * The TV navigation host: [rememberNavBackStack] + [MainNavigation] over [TvRoute].
 *
 * The six rail tabs are sibling [TvRoute]s hosted by the route-backed
 * [TvAppFrame]; selecting one resets the stack to that tab (SiblingPage
 * semantics). Detail, watch, grid and addon-detail push on top without the
 * rail, so BACK from them returns to the tab that opened them. Each tab entry
 * keeps its own screen (and ViewModel), so focus and rememberSaveable state
 * restore per tab.
 *
 * [initialTab] is the legacy rail index of the start tab; it only picks the
 * start route ([tvRouteForTab]) — after that the stack is the tab state.
 */
@Composable
fun TvNavigation(initialTab: Int = TV_TAB_HOME) {
    val backStack = rememberNavBackStack<TvRoute>(tvRouteForTab(initialTab))
    MainNavigation(backStack) {
        entry<TvRoute.Search>(metadata = SiblingPage()) {
            TvTabFrame(backStack) {
                TvSearchTab(
                    onOpenDetail = { openDetail(backStack, it.mediaType, it.tmdbId) },
                )
            }
        }
        entry<TvRoute.Home>(metadata = SiblingPage()) {
            TvTabFrame(backStack) {
                TvHomeTab(
                    onOpenDetail = { openDetail(backStack, it.mediaType, it.tmdbId) },
                    onResume = { entry ->
                        backStack.add(TvRoute.Watch(entry.mediaType, entry.tmdbId, entry.season, entry.episode))
                    },
                    onOpenGrid = { kind -> backStack.add(TvRoute.Grid(kind)) },
                    onOpenTab = { route -> backStack.reset(route) },
                )
            }
        }
        entry<TvRoute.Movies>(metadata = SiblingPage()) {
            TvTabFrame(backStack) {
                TvBrowseTab(
                    mediaType = MediaType.MOVIE,
                    onOpenDetail = { openDetail(backStack, it.mediaType, it.tmdbId) },
                )
            }
        }
        entry<TvRoute.Shows>(metadata = SiblingPage()) {
            TvTabFrame(backStack) {
                TvBrowseTab(
                    mediaType = MediaType.TV,
                    onOpenDetail = { openDetail(backStack, it.mediaType, it.tmdbId) },
                )
            }
        }
        entry<TvRoute.MyList>(metadata = SiblingPage()) {
            TvTabFrame(backStack) {
                TvMyListTab(
                    onOpenDetail = { openDetail(backStack, it.mediaType, it.tmdbId) },
                    onResume = { entry ->
                        backStack.add(TvRoute.Watch(entry.mediaType, entry.tmdbId, entry.season, entry.episode))
                    },
                )
            }
        }
        entry<TvRoute.Addons>(metadata = SiblingPage()) {
            TvTabFrame(backStack) {
                TvAddonsTab(
                    onOpenDetail = { dbId -> backStack.add(TvRoute.AddonDetail(dbId)) },
                )
            }
        }
        entry<TvRoute.Detail> { route ->
            TvDetailPage(backStack, route.mediaType, route.tmdbId)
        }
        entry<TvRoute.Grid> { route ->
            TvGridPage(backStack, route.kind)
        }
        entry<TvRoute.Watch>(metadata = FullscreenPage()) { route ->
            TvWatchPage(backStack, route.mediaType, route.tmdbId, route.season, route.episode)
        }
        entry<TvRoute.AddonDetail> { route ->
            TvAddonDetailPage(backStack, route.addonDbId)
        }
    }
}

private fun openDetail(backStack: NavBackStack<TvRoute>, mediaType: MediaType, tmdbId: Int) {
    backStack.add(TvRoute.Detail(mediaType.name.lowercase(), tmdbId))
}

/**
 * One rail tab's frame: the route-backed [TvAppFrame] (rail appearance and
 * focus behaviour unchanged from the index-based frame) around [content].
 * Only composed while a tab is on top — pushed pages get no rail.
 * BACK on a root tab returns to the rail; BACK from the rail exits.
 */
@Composable
private fun TvTabFrame(backStack: NavBackStack<TvRoute>, content: @Composable () -> Unit) {
    TvAppFrame(
        backStack = backStack,
        destinations = railDestinations(),
        backReturnsToRail = backStack.backStack.size <= 1,
    ) { content() }
}

@Composable
private fun railDestinations(): List<TvRouteDestination<TvRoute>> {
    val search = stringResource(UiR.string.search)
    val home = stringResource(UiR.string.home)
    val movies = stringResource(R.string.movies)
    val shows = stringResource(R.string.shows)
    val myList = stringResource(R.string.my_list)
    val addons = stringResource(R.string.stream_addons)
    return remember(search, home, movies, shows, myList, addons) {
        listOf(
            TvRouteDestination(TvRoute.Search, search, { IconSearch() }),
            TvRouteDestination(TvRoute.Home, home, { IconHome() }),
            TvRouteDestination(TvRoute.Movies, movies, { IconMovie() }),
            TvRouteDestination(TvRoute.Shows, shows, { IconTv() }),
            TvRouteDestination(TvRoute.MyList, myList, { IconList() }),
            TvRouteDestination(TvRoute.Addons, addons, { IconWidgets() }),
        )
    }
}
