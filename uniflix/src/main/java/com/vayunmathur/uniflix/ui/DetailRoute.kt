package com.vayunmathur.uniflix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.Route
import com.vayunmathur.uniflix.domain.mediaTypeOf
import com.vayunmathur.uniflix.platform.DetailViewModel

/** Detail destination for one title. */
@Composable
internal fun DetailRoute(backStack: NavBackStack<Route>, route: Route.Detail) {
    val vm: DetailViewModel = viewModel()
    val type = mediaTypeOf(route.mediaType)
    LaunchedEffect(route.tmdbId, route.mediaType) { vm.load(type, route.tmdbId) }
    DetailScreen(
        state = vm.state,
        onBack = { backStack.pop() },
        onSeasonChange = { season -> vm.selectSeason(route.tmdbId, season) },
        onToggleWatchlist = vm::toggleWatchlist,
        onPlay = { season, episode ->
            backStack.add(Route.Watch(route.mediaType, route.tmdbId, season, episode))
        },
        onResolve = { season, episode -> vm.resolve(type, route.tmdbId, season, episode) },
        onAddNote = { body, score -> vm.addNote(type, route.tmdbId, body, score) },
        onOpenDetail = { item ->
            backStack.add(Route.Detail(item.mediaType.name.lowercase(), item.tmdbId))
        },
        onOpenAddons = { backStack.add(Route.Addons) },
    )
}
