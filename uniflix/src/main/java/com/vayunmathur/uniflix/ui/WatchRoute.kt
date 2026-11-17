package com.vayunmathur.uniflix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.Route
import com.vayunmathur.uniflix.domain.mediaTypeOf
import com.vayunmathur.uniflix.platform.DetailViewModel

/** Watch destination: player for one episode or movie. */
@Composable
internal fun WatchRoute(backStack: NavBackStack<Route>, route: Route.Watch) {
    val vm: DetailViewModel = viewModel()
    val type = mediaTypeOf(route.mediaType)
    var startPositionMs by remember(route.tmdbId, route.season, route.episode) {
        mutableLongStateOf(0L)
    }
    LaunchedEffect(route.tmdbId, route.season, route.episode) {
        vm.load(type, route.tmdbId)
        vm.loadServers()
        startPositionMs = vm.startPosition(type, route.tmdbId, route.season, route.episode)
        vm.resolve(type, route.tmdbId, route.season, route.episode)
    }
    WatchScreen(
        state = vm.state.copy(startPositionMs = startPositionMs),
        season = route.season,
        episode = route.episode,
        onBack = { backStack.pop() },
        onRetry = { vm.resolve(type, route.tmdbId, route.season, route.episode) },
        onOpenAddons = { backStack.add(Route.Addons) },
        onSelectServer = { server ->
            vm.selectServer(type, route.tmdbId, route.season, route.episode, server)
        },
        onSavePosition = { positionMs, durationMs ->
            val detail = vm.state.detail ?: return@WatchScreen
            vm.savePosition(
                type, route.tmdbId, detail.title, detail.posterPath,
                route.season, route.episode, positionMs, durationMs,
            )
        },
    )
}
