package com.vayunmathur.games.pipes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.pipes.R
import com.vayunmathur.games.pipes.platform.GameBoardUiState
import com.vayunmathur.games.pipes.platform.PipesActions
import com.vayunmathur.library.ui.game.LevelPickerBox
import com.vayunmathur.library.ui.game.MovesBox
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.R as UiR

/**
 * The game screen. [onBackToPacks] is offered after solving a pack's last level, where
 * there is no next level to go to; it defaults to plain back navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBoardScreen(
    state: GameBoardUiState,
    actions: PipesActions,
    onBack: () -> Unit,
    onLevelChange: (Int) -> Unit,
    onBackToPacks: () -> Unit = onBack,
) {
    var showRestartConfirm by remember { mutableStateOf(false) }
    if (showRestartConfirm) { RestartConfirmDialog(onConfirm = { actions.onRestart() }, onDismiss = { showRestartConfirm = false }) }
    AppScaffold(title = "", onNavigateBack = onBack, scrollBehavior = appBarScrollBehavior()) { innerPadding ->
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val infoBoxes = @Composable { LevelPickerBox(levelIndex = state.levelIndex, onLevelChange = onLevelChange, isCompleted = state.isCompleted, maxLevelIndex = state.maxLevelIndex); MovesBox(moves = state.moves, bestScore = state.bestScore, optimalMoves = state.levelData.optimalMoves) }
            val actionButtons = @Composable {
                if (!state.isLevelWon) {
                    Button(onClick = { actions.onUndo() }, enabled = state.canUndo) { Text(stringResource(UiR.string.undo)) }
                    Button(onClick = { showRestartConfirm = true }, enabled = state.canUndo) { Text(stringResource(UiR.string.restart)) }
                } else if (state.levelIndex < state.maxLevelIndex) {
                    Button(onClick = { onLevelChange(state.levelIndex + 1) }) { Text(stringResource(UiR.string.next_level)) }
                } else {
                    // Last level of the pack: Undo/Restart are gone, so never leave this row empty.
                    Button(onClick = onBackToPacks) { Text(stringResource(R.string.back_to_packs)) }
                }
            }
            val status = @Composable { StatusLine(state) }
            val board = @Composable { boardModifier: Modifier -> GameBoard(levelData = state.levelData, gameState = state.gameState, activeColor = state.activeColor, activePath = state.activePath, onStartDraw = actions::startDraw, onExtendPath = actions::extendPath, onCommitDraw = actions::commitDraw, isLevelWon = state.isLevelWon, colorblind = state.colorblind, modifier = boardModifier) }
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
                if (maxWidth > maxHeight) {
                    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { board(Modifier.fillMaxSize()) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            infoBoxes()
                            status()
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actionButtons() }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) { infoBoxes() }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { board(Modifier.fillMaxSize()) }
                        status()
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { actionButtons() }
                    }
                }
            }
        }
    }
}

/** The solved message once the level is won; announced by TalkBack as it appears. */
@Composable
private fun StatusLine(state: GameBoardUiState) {
    if (!state.isLevelWon) return
    Text(
        text = pluralStringResource(R.plurals.level_solved_moves, state.moves, state.moves),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun RestartConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(title = stringResource(R.string.restart_confirm_title), message = stringResource(R.string.restart_confirm_message), confirmLabel = stringResource(UiR.string.restart), dismissLabel = stringResource(UiR.string.cancel), destructive = true, onConfirm = onConfirm, onDismiss = onDismiss)
}
