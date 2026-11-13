package com.vayunmathur.games.minesweeper.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.games.minesweeper.Route
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.platform.MinesweeperViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.delay

/**
 * The only place the board touches the ViewModel.
 *
 * It deals a field if there is not one already — reached by entering this route after process death,
 * when the route survived but the in-memory field did not — and drives the 1 Hz clock. Everything
 * else is handed to the stateless [GameBoardScreen], which is what the store-listing previews render.
 */
@Composable
fun GameScreen(
    backStack: NavBackStack<Route>,
    viewModel: MinesweeperViewModel,
    config: GameConfig,
) {
    val uiState by viewModel.uiState.collectAsState()
    // Not delegated: the State is read inside the lambda below, so a tick recomposes only the
    // clock text instead of this whole screen and the field under it.
    val elapsedSeconds = viewModel.elapsedSeconds.collectAsState()

    LaunchedEffect(config) {
        if (uiState.game == null) viewModel.newGame(config)
    }

    // The clock starts on the first reveal, not on entering the screen.
    val running = uiState.game?.let { it.started && !it.isOver } == true
    LaunchedEffect(running) {
        while (running) {
            delay(1000)
            viewModel.incrementTimer()
        }
    }

    GameBoardScreen(
        state = uiState,
        actions = viewModel,
        onExit = { backStack.pop() },
        elapsedSeconds = { elapsedSeconds.value },
    )
}
