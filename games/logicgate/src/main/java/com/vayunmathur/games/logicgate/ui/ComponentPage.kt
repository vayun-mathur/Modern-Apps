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

/** Opens sandbox component [componentId] in [LogicViewModel] and binds it to [GameScreen]. */
@Composable
fun ComponentPage(backStack: NavBackStack<Route>, viewModel: LogicViewModel, componentId: String) {
    LaunchedEffect(componentId) { viewModel.openComponent(componentId) }
    val game by viewModel.game.collectAsState()
    val state = game
    if (state == null || state.sandbox?.id != componentId) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    GameScreen(
        state = state,
        actions = viewModel.editor,
        onBack = { backStack.pop() },
        onNext = {},
        onDelete = {
            backStack.pop()
            viewModel.deleteComponent(componentId)
        },
    )
}
