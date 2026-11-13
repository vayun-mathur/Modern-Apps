package com.vayunmathur.games.sudoku.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.games.sudoku.Route
import com.vayunmathur.games.sudoku.data.GameConfig
import com.vayunmathur.games.sudoku.platform.SudokuViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.delay

/**
 * The only place the board touches the ViewModel.
 *
 * It deals a puzzle if there is not one already — reached by entering this route after process
 * death, when the route survived but the in-memory grid did not — and drives the 1 Hz clock.
 * Everything else is handed to the stateless [GameBoardScreen], which is what the store-listing
 * previews render.
 */
@Composable
fun GameScreen(
    backStack: NavBackStack<Route>,
    viewModel: SudokuViewModel,
    config: GameConfig,
) {
    val uiState by viewModel.uiState.collectAsState()
    // Not delegated: the State is read inside the lambda below, so a tick recomposes only the
    // clock text instead of this whole screen and the grid under it.
    val elapsedSeconds = viewModel.elapsedSeconds.collectAsState()
    val highlightMistakes by viewModel.highlightMistakes.collectAsState()

    LaunchedEffect(config) {
        if (uiState.game == null && !uiState.generating) viewModel.newGame(config)
    }

    val running = uiState.game?.isWon == false
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
        highlightMistakes = highlightMistakes,
        elapsedSeconds = { elapsedSeconds.value },
    )
}
