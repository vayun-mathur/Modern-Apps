package com.vayunmathur.uniflix.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.Route
import com.vayunmathur.uniflix.platform.SearchViewModel

/** Search destination. */
@Composable
internal fun SearchRoute(backStack: NavBackStack<Route>) {
    val vm: SearchViewModel = viewModel()
    SearchScreen(
        state = vm.state,
        onQueryChange = vm::onQueryChange,
        onBack = { backStack.pop() },
        onOpenDetail = { item ->
            backStack.add(Route.Detail(item.mediaType.name.lowercase(), item.tmdbId))
        },
    )
}
