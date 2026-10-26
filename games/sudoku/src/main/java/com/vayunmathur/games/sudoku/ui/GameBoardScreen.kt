package com.vayunmathur.games.sudoku.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.platform.SudokuActions
import com.vayunmathur.games.sudoku.platform.SudokuUiState
import com.vayunmathur.games.sudoku.ui.components.NumberPad
import com.vayunmathur.games.sudoku.ui.components.SudokuGrid
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.GameResultOverlay
import com.vayunmathur.library.ui.game.formatDuration
import com.vayunmathur.library.ui.isCompactHeight
import com.vayunmathur.library.ui.isExpandedWidth

/** Caps the grid on tablets, where a full-width board would put the digits absurdly far apart. */
private val BoardMaxWidth = 520.dp

/**
 * The board, with no dependency on the ViewModel so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`.
 */
@Composable
fun GameBoardScreen(
    state: SudokuUiState,
    actions: SudokuActions,
    onExit: () -> Unit,
) {
    val game = state.game
    AppScaffold(
        title = if (game == null) stringResource(R.string.app_name)
        else "${game.size.displayName()} · ${game.difficulty.displayName()}",
        onNavigateBack = onExit,
        alignment = AppBarAlignment.Center,
        actions = {
            if (game != null) {
                Text(
                    formatDuration(game.elapsedSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = Spacing.lg),
                )
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        // Short windows (landscape phones) and wide expanded windows in landscape
        // cannot fit board + pad + actions stacked, so the board sits beside the
        // controls instead of above them. Everything else keeps the stacked column.
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (game == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@BoxWithConstraints
            }

            val sideBySide = isCompactHeight() || (isExpandedWidth() && maxWidth > maxHeight)
            // Hoisted out of the Row below: RowScope would shadow the
            // BoxWithConstraintsScope receiver and the size would not resolve.
            val boardSize = minOf(maxWidth * 0.62f, maxHeight)
            if (sideBySide) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Inputs first (left in LTR): number pad and actions sit beside
                    // the board rather than under it, so neither is pushed off the
                    // short landscape height.
                    Column(
                        Modifier
                            .weight(1f)
                            // No fillMaxHeight: the column wraps its content and the
                            // parent Row's CenterVertically centers the whole block
                            // beside the board. Still scrolls if it overflows.
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        HintsUsed(game.hintsUsed)

                        NumberPad(
                            game = game,
                            onDigit = actions::enterDigit,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        ActionBar(state, actions, onExit)
                    }
                    // Height-sized square: the board is what the height allows.
                    // Capped at a share of the width so a narrow-but-short window
                    // still leaves room for the inputs.
                    SudokuGrid(
                        game = game,
                        onSelect = actions::selectCell,
                        modifier = Modifier.size(boardSize),
                    )
                }
            } else {
                Column(
                    Modifier
                        .widthIn(max = BoardMaxWidth)
                        .fillMaxWidth()
                        // Safety net for short-but-not-compact windows (an 800dp-tall
                        // desktop window still overflows the stacked 9x9 slightly).
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    SudokuGrid(
                        game = game,
                        onSelect = actions::selectCell,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    HintsUsed(game.hintsUsed)

                    NumberPad(
                        game = game,
                        onDigit = actions::enterDigit,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    ActionBar(state, actions, onExit)
                }
            }

            if (game.isWon) {
                GameResultOverlay(
                    title = stringResource(R.string.congratulations),
                    won = true,
                    onPlayAgain = { actions.restart() },
                    onBack = onExit,
                ) {
                    Text("${stringResource(UiR.string.game_time)}: ${formatDuration(game.elapsedSeconds)}")
                    if (game.hintsUsed > 0) {
                        Text(
                            "${stringResource(R.string.hints_used)}: ${game.hintsUsed}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HintsUsed(hintsUsed: Int) {
    if (hintsUsed > 0) {
        Text(
            "${stringResource(R.string.hints_used)}: $hintsUsed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionBar(
    state: SudokuUiState,
    actions: SudokuActions,
    onExit: () -> Unit,
) {
    val game = state.game ?: return
    var confirmGiveUp by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Tonal fill is the "on" state for notes mode; the label alone is too easy to miss.
            if (game.notesMode) {
                FilledTonalButton(
                    onClick = { actions.toggleNotesMode() },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.notes)) }
            } else {
                OutlinedButton(
                    onClick = { actions.toggleNotesMode() },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.notes)) }
            }
            OutlinedButton(
                onClick = { actions.clearCell() },
                modifier = Modifier.weight(1f),
                enabled = !game.isWon,
            ) { Text(stringResource(R.string.erase)) }
            OutlinedButton(
                onClick = { actions.undo() },
                modifier = Modifier.weight(1f),
                enabled = state.canUndo,
            ) { Text(stringResource(com.vayunmathur.library.ui.R.string.undo)) }
            OutlinedButton(
                onClick = { actions.hint() },
                modifier = Modifier.weight(1f),
                enabled = !game.isWon,
            ) { Text(stringResource(R.string.hint)) }
        }
        TextButton(
            onClick = {
                // A finished puzzle has nothing to lose; only confirm mid-game,
                // when giving up records a loss.
                if (!game.isWon) {
                    confirmGiveUp = true
                } else {
                    actions.giveUp()
                    onExit()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.give_up)) }
    }

    if (confirmGiveUp) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_give_up_title),
            message = stringResource(R.string.confirm_give_up_message),
            confirmLabel = stringResource(R.string.give_up),
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = {
                actions.giveUp()
                onExit()
            },
            onDismiss = { confirmGiveUp = false },
        )
    }
}
