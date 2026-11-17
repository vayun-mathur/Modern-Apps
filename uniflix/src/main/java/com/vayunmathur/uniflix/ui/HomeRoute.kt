package com.vayunmathur.uniflix.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.Route
import com.vayunmathur.uniflix.platform.HomeViewModel

/** Home destination: trending rows and hub shortcuts. */
@Composable
internal fun HomeRoute(backStack: NavBackStack<Route>) {
    val vm: HomeViewModel = viewModel()
    HomeScreen(
        state = vm.state,
        onOpenDetail = { item ->
            backStack.add(Route.Detail(item.mediaType.name.lowercase(), item.tmdbId))
        },
        onOpenSearch = { backStack.add(Route.Search) },
        onOpenMovies = { backStack.add(Route.Movies) },
        onOpenSeries = { backStack.add(Route.Series) },
        onOpenLists = { backStack.add(Route.Lists) },
        onOpenContinue = { backStack.add(Route.ContinueWatching) },
        onOpenAddons = { backStack.add(Route.Addons) },
    )
}
