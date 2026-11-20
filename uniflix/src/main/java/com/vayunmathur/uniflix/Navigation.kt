package com.vayunmathur.uniflix

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.uniflix.platform.AddonsViewModel
import com.vayunmathur.uniflix.platform.ListsViewModel
import com.vayunmathur.uniflix.ui.AddonDetailScreen
import com.vayunmathur.uniflix.ui.AddonsScreen
import com.vayunmathur.uniflix.ui.BrowseRoute
import com.vayunmathur.uniflix.ui.DetailRoute
import com.vayunmathur.uniflix.ui.HomeRoute
import com.vayunmathur.uniflix.ui.SearchRoute
import com.vayunmathur.uniflix.ui.WatchRoute
import com.vayunmathur.uniflix.ui.ContinueWatchingScreen
import com.vayunmathur.uniflix.ui.ListsScreen

@Composable
fun Navigation() {
    val backStack = rememberNavBackStack<Route>(Route.Home)

    MainNavigation(backStack) {
        entry<Route.Home> { HomeRoute(backStack) }
        entry<Route.Movies> { BrowseRoute(backStack, movies = true) }
        entry<Route.Series> { BrowseRoute(backStack, movies = false) }
        entry<Route.Search> { SearchRoute(backStack) }
        entry<Route.Detail> { route -> DetailRoute(backStack, route) }
        entry<Route.Watch>(metadata = FullscreenPage()) { route -> WatchRoute(backStack, route) }
        entry<Route.Lists> {
            val vm: ListsViewModel = viewModel()
            ListsScreen(
                onBack = { backStack.pop() },
                onOpenDetail = { item ->
                    backStack.add(Route.Detail(item.mediaType.name.lowercase(), item.tmdbId))
                },
            )
        }
        entry<Route.ContinueWatching> {
            ContinueWatchingScreen(
                onBack = { backStack.pop() },
                onResume = { entry ->
                    backStack.add(Route.Watch(entry.mediaType, entry.tmdbId, entry.season, entry.episode))
                },
            )
        }
        entry<Route.Addons> {
            val vm: AddonsViewModel = viewModel()
            AddonsScreen(
                onBack = { backStack.pop() },
                onOpenDetail = { dbId -> backStack.add(Route.AddonDetail(dbId)) },
            )
        }
        entry<Route.AddonDetail> { route ->
            AddonDetailScreen(
                addonDbId = route.addonDbId,
                onBack = { backStack.pop() },
            )
        }
    }
}
