package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.solitaire.R
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.platform.SolitaireActions
import com.vayunmathur.games.solitaire.platform.canKlondikeAutoComplete
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.ui.game.formatDuration

private val SolitaireCompactBoardMaxWidth = 640.dp
private val SolitaireExpandedBoardMaxWidth = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBoardScreen(state: SolitaireUiState, mode: GameMode, actions: SolitaireActions, onExit: () -> Unit) {
    val activeGame = when (mode) {
        GameMode.KLONDIKE -> state.klondike?.let { Triple(it.isWon, it.moveCount, it.elapsedSeconds) }
        GameMode.SPIDER -> state.spider?.let { Triple(it.isWon, it.moveCount, it.elapsedSeconds) }
        GameMode.FREECELL -> state.freeCell?.let { Triple(it.isWon, it.moveCount, it.elapsedSeconds) }
        GameMode.PYRAMID -> state.pyramid?.let { Triple(it.isWon, it.moveCount, it.elapsedSeconds) }
    }
    val isWon = activeGame?.first == true
    val moveCount = activeGame?.second ?: 0
    val elapsed = activeGame?.third ?: 0
    val modeName = mode.displayName()
    val timeText = formatDuration(elapsed)
    // Wide windows get a slightly larger readable board; the container below
    // letterboxes anything past 720dp so cards never stretch absurdly wide.
    val boardMaxWidth = if (isExpandedWidth()) SolitaireExpandedBoardMaxWidth else SolitaireCompactBoardMaxWidth
    AppScaffold(
        title = modeName,
        actions = {
            Text(timeText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 16.dp))
            Text("${stringResource(R.string.moves)}: $moveCount", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 16.dp))
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        var confirmGiveUp by remember { mutableStateOf(false) }
        val giveUp = {
            // Giving up records a loss, so confirm while the game is still
            // winnable; a finished board has nothing left to lose.
            if (!isWon) confirmGiveUp = true else { actions.giveUp(); onExit() }
        }
        Box(Modifier.fillMaxSize()) {
            DesktopMaxWidthContainer(modifier = Modifier.padding(innerPadding)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GameActionBar(onUndo = { actions.undo() }, onGiveUp = giveUp, undoEnabled = state.history.isNotEmpty() && !isWon)
                when (mode) {
                    GameMode.KLONDIKE -> state.klondike?.let {
                        KlondikeBoard(it, actions, Modifier.align(Alignment.CenterHorizontally).widthIn(max = boardMaxWidth).fillMaxWidth())
                        // Offering the button locks nothing: the board stays fully
                        // playable. It stays up mid-run as the skip switch (draining
                        // foundations can otherwise hide it under the player's tap),
                        // and any tap on the board mid-run stops the run instead.
                        val autoCompleteRunning by actions.autoCompleteRunning.collectAsState()
                        if (canKlondikeAutoComplete(it) || autoCompleteRunning) {
                            Button(onClick = { actions.klondikeAutoComplete() }, Modifier.align(Alignment.CenterHorizontally)) {
                                Text(stringResource(if (autoCompleteRunning) R.string.skip else R.string.auto_complete))
                            }
                        }
                    }
                    GameMode.SPIDER -> state.spider?.let { SpiderBoard(it, actions, Modifier.align(Alignment.CenterHorizontally).widthIn(max = boardMaxWidth).fillMaxWidth()) }
                    GameMode.FREECELL -> state.freeCell?.let { FreeCellBoard(it, actions, Modifier.align(Alignment.CenterHorizontally).widthIn(max = boardMaxWidth).fillMaxWidth()) }
                    GameMode.PYRAMID -> state.pyramid?.let { PyramidBoard(it, actions, Modifier.align(Alignment.CenterHorizontally).widthIn(max = boardMaxWidth).fillMaxWidth()) }
                }
            }
            }
            // Cards in motion draw above the board and outside its scrolling column.
            CardMotionLayer(actions)
            if (isWon) { WinOverlay(elapsedSeconds = elapsed, moveCount = moveCount, onNewGame = { actions.restart() }, onBack = onExit) }
            if (confirmGiveUp) {
                ConfirmDialog(
                    title = stringResource(R.string.confirm_give_up_title),
                    message = stringResource(R.string.confirm_give_up_message),
                    confirmLabel = stringResource(R.string.give_up),
                    dismissLabel = stringResource(UiR.string.cancel),
                    destructive = true,
                    onConfirm = { actions.giveUp(); onExit() },
                    onDismiss = { confirmGiveUp = false },
                )
            }
        }
    }
}
