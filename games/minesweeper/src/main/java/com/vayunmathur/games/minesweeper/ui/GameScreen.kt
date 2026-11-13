package com.vayunmathur.games.minesweeper.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.games.minesweeper.Route
import com.vayunmathur.games.minesweeper.data.GameConfig
import com.vayunmathur.games.minesweeper.platform.MinesweeperViewModel
import com.vayunmathur.library.util.NavBackStack

/**
 * The only place the board touches the ViewModel.
 *
 * It deals a field if there is not one already — reached by entering this route after process death,
 * when the route survived but the in-memory field did not — and drives the 1 Hz clock, which
 * runs only while the screen is resumed and the field is actually playable. Everything
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

    // The clock starts on the first reveal, not on entering the screen, and stops while a
    // confirmation dialog or the result overlay (isOver) covers the field and whenever the screen
    // is not resumed — see GameClock.
    var dialogShown by remember { mutableStateOf(false) }
    val running = uiState.game?.let { it.started && !it.isOver } == true && !dialogShown
    GameClock(running = running, onTick = viewModel::incrementTimer)

    GameBoardScreen(
        state = uiState,
        actions = viewModel,
        onExit = { backStack.pop() },
        elapsedSeconds = { elapsedSeconds.value },
        onDialogShownChange = { dialogShown = it },
    )
}
