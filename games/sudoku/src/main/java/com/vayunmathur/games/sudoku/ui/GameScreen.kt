package com.vayunmathur.games.sudoku.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.games.sudoku.Route
import com.vayunmathur.games.sudoku.data.GameConfig
import com.vayunmathur.games.sudoku.platform.SudokuViewModel
import com.vayunmathur.library.util.NavBackStack

/**
 * The only place the board touches the ViewModel.
 *
 * It deals a puzzle if there is not one already — reached by entering this route after process
 * death, when the route survived but the in-memory grid did not — and drives the 1 Hz clock,
 * which runs only while the screen is resumed and the player can actually play.
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

    // A confirmation dialog or the result overlay covers the board, so the clock stops for them
    // too: time spent deciding whether to give up is not time spent solving.
    var dialogShown by remember { mutableStateOf(false) }
    val game = uiState.game
    val running = game != null && !game.isWon && !game.isGameOver && !dialogShown
    GameClock(running = running, onTick = viewModel::incrementTimer)

    GameBoardScreen(
        state = uiState,
        actions = viewModel,
        onExit = { backStack.pop() },
        highlightMistakes = highlightMistakes,
        elapsedSeconds = { elapsedSeconds.value },
        onDialogShownChange = { dialogShown = it },
    )
}
