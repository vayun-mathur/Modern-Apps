package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vayunmathur.games.logicgate.Route
import com.vayunmathur.games.logicgate.platform.LogicViewModel
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.util.NavBackStack

/** Opens [levelId] in [LogicViewModel] and binds it to the stateless [GameScreen]. */
@Composable
fun GamePage(backStack: NavBackStack<Route>, viewModel: LogicViewModel, levelId: String) {
    LaunchedEffect(levelId) { viewModel.openLevel(levelId) }
    val game by viewModel.game.collectAsState()
    val state = game
    if (state == null || state.level?.id != levelId) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    GameScreen(
        state = state,
        actions = viewModel.editor,
        onBack = { backStack.pop() },
        onNext = { backStack.setLast(Route.Game(it)) },
    )
}
