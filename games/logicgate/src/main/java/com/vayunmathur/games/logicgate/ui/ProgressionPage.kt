package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.games.logicgate.Route
import com.vayunmathur.games.logicgate.platform.LogicViewModel
import com.vayunmathur.library.util.NavBackStack

/** Binds [LogicViewModel] to the stateless [ProgressionScreen]. */
@Composable
fun ProgressionPage(backStack: NavBackStack<Route>, viewModel: LogicViewModel) {
    val state by viewModel.progression.collectAsState()
    ProgressionScreen(
        state = state,
        onOpenLevel = { backStack.add(Route.Game(it)) },
        onOpenGameCenter = { backStack.add(Route.GameCenter) },
    )
}
