package com.vayunmathur.uniflix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.Route
import com.vayunmathur.uniflix.platform.BrowseViewModel

/** Movies/Series destination: one browse grid over the matching catalog. */
@Composable
internal fun BrowseRoute(backStack: NavBackStack<Route>, movies: Boolean) {
    val vm: BrowseViewModel = viewModel()
    LaunchedEffect(Unit) { if (movies) vm.loadMovies() else vm.loadSeries() }
    val browse = if (movies) vm.movies else vm.series
    MediaGridScreen(
        title = if (movies) "Movies" else "Series",
        loading = browse.loading,
        items = browse.items,
        onBack = { backStack.pop() },
        onOpenDetail = { item ->
            backStack.add(Route.Detail(item.mediaType.name.lowercase(), item.tmdbId))
        },
    )
}
