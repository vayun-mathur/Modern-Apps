package com.vayunmathur.games.minesweeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.TapMode
import com.vayunmathur.games.minesweeper.platform.MinesweeperActions
import com.vayunmathur.games.minesweeper.platform.MinesweeperUiState
import com.vayunmathur.games.minesweeper.ui.components.MineFieldGrid
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconFlag
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.GameResultOverlay
import com.vayunmathur.library.ui.game.formatDuration
import com.vayunmathur.library.ui.isCompactHeight
import com.vayunmathur.library.ui.isExpandedWidth

/** Caps the field on tablets, where a full-width board would give absurdly large cells. */
private val FieldMaxWidth = 480.dp

/** The field's share of a side-by-side row, against 1 for the controls. */
private const val SIDE_FIELD_WEIGHT = 1.6f

/**
 * The board, with no dependency on the ViewModel so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`.
 *
 * @param elapsedSeconds the clock, read lazily so a 1 Hz tick recomposes only the text that
 * shows it rather than the field.
 * @param onDialogShownChange told whenever a confirmation dialog opens or closes, so the stateful
 * caller can pause the clock while the field is covered.
 */
@Composable
fun GameBoardScreen(
    state: MinesweeperUiState,
    actions: MinesweeperActions,
    onExit: () -> Unit,
    elapsedSeconds: () -> Int = { 0 },
    onDialogShownChange: (Boolean) -> Unit = {},
) {
    val game = state.game
    val mineCount = state.config.difficulty.mineCount(state.config.size)
    // The game is only worth confirming over once the first dig has laid the
    // mines: a fresh field restarts into an identical blank one, and giving up
    // on it records no loss.
    val hasProgress = game?.let { it.started && !it.isOver } == true
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmGiveUp by remember { mutableStateOf(false) }
    val dialogShownChange by rememberUpdatedState(onDialogShownChange)
    val dialogShown = confirmRestart || confirmGiveUp
    LaunchedEffect(dialogShown) { dialogShownChange(dialogShown) }
    AppScaffold(
        // Size name plus the mine count rather than size plus density name: the two enums share
        // labels ("Medium" and "Medium"), and the raw count is what a player actually wants to know.
        title = "${state.config.size.displayName()} · " +
            pluralStringResource(R.plurals.mine_count, mineCount, mineCount),
        onNavigateBack = onExit,
        alignment = AppBarAlignment.Center,
        actions = {
            if (game != null) ClockText(elapsedSeconds)
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
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

            val onNewField: () -> Unit = {
                if (hasProgress) confirmRestart = true else actions.restart()
            }
            val onGiveUp: () -> Unit = {
                if (hasProgress) {
                    confirmGiveUp = true
                } else {
                    actions.giveUp()
                    onExit()
                }
            }
            // The field is never inside a scroll container: cells are fitted to whichever of the
            // width or height runs out first (see MineFieldGrid), so the whole field is always on
            // screen. In the old scrolling column a landscape field was ~670dp tall, and a slow
            // scroll that rested on a cell long enough fired the long press and planted a flag.
            // Short windows (landscape phones) and wide landscape windows put the controls beside
            // the field rather than under it, so the field gets the full height.
            val sideBySide = isCompactHeight() || (isExpandedWidth() && maxWidth > maxHeight)
            if (sideBySide) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Controls first (start side), as in sudoku; they scroll on their own if a
                    // large font makes them taller than the window.
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        MinesRemaining(game.minesRemaining)
                        FieldControls(state, actions, onNewField, onGiveUp)
                    }
                    MineFieldGrid(
                        game = game,
                        onTap = actions::tapCell,
                        onLongPress = actions::flagCell,
                        modifier = Modifier
                            .weight(SIDE_FIELD_WEIGHT)
                            .fillMaxHeight(),
                    )
                }
            } else {
                Column(
                    Modifier
                        .widthIn(max = FieldMaxWidth)
                        .fillMaxSize()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    MinesRemaining(game.minesRemaining)
                    // Whatever height the counter and controls leave; fill = false so a field
                    // that fits by width wraps to its own height and the controls sit right under
                    // it, exactly as before on fields that already fitted.
                    MineFieldGrid(
                        game = game,
                        onTap = actions::tapCell,
                        onLongPress = actions::flagCell,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth(),
                    )
                    FieldControls(state, actions, onNewField, onGiveUp)
                }
            }

            if (confirmRestart) {
                ConfirmDialog(
                    title = stringResource(R.string.confirm_new_field_title),
                    message = stringResource(R.string.confirm_new_field_message),
                    confirmLabel = stringResource(R.string.new_field),
                    dismissLabel = stringResource(UiR.string.cancel),
                    destructive = true,
                    onConfirm = { actions.restart() },
                    onDismiss = { confirmRestart = false },
                )
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

            if (game.isOver) {
                val won = game.outcome == GameOutcome.WON
                GameResultOverlay(
                    title = stringResource(if (won) R.string.cleared else R.string.boom),
                    won = won,
                    onPlayAgain = { actions.restart() },
                    onBack = onExit,
                ) {
                    // A losing time is not an achievement, so it is only shown on a win.
                    if (won) {
                        Text(
                            "${stringResource(UiR.string.game_time)}: " +
                                formatDuration(elapsedSeconds())
                        )
                    }
                }
            }
        }
    }
}

/** The flag counter above the field: mines left to find, negative when over-flagged. */
@Composable
private fun MinesRemaining(count: Int) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconFlag(tint = MaterialTheme.colorScheme.error)
        Text(" $count", style = MaterialTheme.typography.titleMedium)
    }
}

/** The hint (before the first move), the tap-mode toggle, and the New Field / Give Up buttons. */
@Composable
private fun FieldControls(
    state: MinesweeperUiState,
    actions: MinesweeperActions,
    onNewField: () -> Unit,
    onGiveUp: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (state.game?.started == false) {
            // Follows the mode, so the hint never describes a gesture the toggle has swapped.
            Text(
                stringResource(
                    if (state.tapMode == TapMode.FLAG) R.string.how_to_play_flag
                    else R.string.how_to_play
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TapModeToggle(state.tapMode, actions::setTapMode)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Button(onClick = onNewField, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.new_field))
            }
            TextButton(onClick = onGiveUp, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.give_up))
            }
        }
    }
}

/** Its own restart scope, so the per-second tick recomposes this text and nothing else. */
@Composable
private fun ClockText(elapsedSeconds: () -> Int) {
    Text(
        formatDuration(elapsedSeconds()),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(end = Spacing.lg),
    )
}

/**
 * Chooses what a plain tap does.
 *
 * Two segments rather than a switch, because digging and flagging are peers: neither is the off state of
 * the other. Flag mode matters most on a dense field, where placing a run of flags by long press is
 * slow and one slip on a mine ends the game outright.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TapModeToggle(mode: TapMode, onSelect: (TapMode) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        val options = listOf(TapMode.DIG to R.string.mode_dig, TapMode.FLAG to R.string.mode_flag)
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                onClick = { onSelect(value) },
                selected = mode == value,
            ) { Text(stringResource(label)) }
        }
    }
}
