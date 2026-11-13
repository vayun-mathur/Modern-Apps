package com.vayunmathur.games.nonogram.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.nonogram.R
import com.vayunmathur.games.nonogram.data.GameMode
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.data.STARTING_HEARTS
import com.vayunmathur.games.nonogram.platform.NonogramGameActions
import com.vayunmathur.games.nonogram.platform.NonogramUiState
import com.vayunmathur.games.nonogram.ui.components.NonogramBoard
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.DailyStreakText
import com.vayunmathur.library.ui.game.GameModeChooser
import com.vayunmathur.library.ui.game.GameTopBarActions
import com.vayunmathur.library.ui.game.HeartsRow
import com.vayunmathur.library.ui.isCompactHeight
import com.vayunmathur.library.ui.isExpandedWidth

/** Caps the board on tablets, where a full-width grid would give absurdly large cells. */
private val BoardMaxWidth = 460.dp

/** Width of the hearts-and-controls panel beside the board on expanded windows. */
private val NonogramSidePanelWidth = 280.dp

/**
 * Chooses what a plain tap does.
 *
 * Two segments rather than a single switch, because filling and crossing are peers: neither is the off
 * state of the other. The active one is filled in so the current mode is obvious at a glance, since
 * getting it wrong in fill mode costs a heart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkModeToggle(mode: MarkMode, onSelect: (MarkMode) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        val options = listOf(
            MarkMode.FILL to R.string.mode_fill,
            MarkMode.CROSS to R.string.mode_cross,
        )
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                onClick = { onSelect(value) },
                selected = mode == value,
            ) { Text(stringResource(label)) }
        }
    }
}

/**
 * The board, with no dependency on the ViewModel so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NonogramGameScreen(
    state: NonogramUiState,
    actions: NonogramGameActions,
    onOpenSettings: () -> Unit,
    onOpenGameCenter: () -> Unit,
) {
    val daily = state.mode == GameMode.DAILY
    // Wide windows put the hearts and controls beside the board instead of
    // stretching the grid across the window; the board itself never splits.
    // Short windows (a landscape phone) split too: stacking board over controls
    // cannot fit in ~360dp of height, and pushed Next level off-screen.
    val sidePanel = isExpandedWidth() || isCompactHeight()
    AppScaffold(
        title = {
            GameModeChooser(
                selected = state.mode,
                options = GameMode.entries,
                // The ladder mode folds the level into its label, so the bar needs no second title.
                label = { mode ->
                    when (mode) {
                        GameMode.CASUAL -> stringResource(R.string.level_title, state.level)
                        GameMode.DAILY -> stringResource(R.string.mode_daily)
                    }
                },
                menuLabel = { mode ->
                    stringResource(
                        when (mode) {
                            GameMode.CASUAL -> R.string.mode_casual
                            GameMode.DAILY -> R.string.mode_daily
                        }
                    )
                },
                onSelect = actions::setGameMode,
            )
        },
        alignment = AppBarAlignment.Center,
        actions = {
            GameTopBarActions(
                onOpenGameCenter = onOpenGameCenter,
                onOpenSettings = onOpenSettings,
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (daily) {
                DailyStreakText(state.dailyStreak)
            }

            val game = state.game
            when {
                state.generationFailed -> Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text(
                        stringResource(R.string.generation_failed),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { actions.retryGeneration() }) {
                        Text(stringResource(R.string.try_again))
                    }
                }

                game == null -> Box(
                    Modifier.fillMaxWidth().padding(Spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                sidePanel -> {
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            NonogramBoard(
                                game = game,
                                actions = actions,
                                markMode = state.markMode,
                                modifier = Modifier
                                    .widthIn(max = BoardMaxWidth)
                                    .fillMaxWidth(),
                            )
                        }
                        Column(
                            Modifier
                                .width(NonogramSidePanelWidth)
                                .fillMaxHeight()
                                // A last resort for very short windows or huge fonts:
                                // the controls scroll rather than clip.
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(
                                Spacing.md,
                                Alignment.CenterVertically,
                            ),
                        ) {
                            HeartsRow(remaining = game.hearts, total = STARTING_HEARTS)
                            NonogramControls(
                                game = game,
                                state = state,
                                actions = actions,
                            )
                        }
                    }
                }

                else -> {
                    HeartsRow(remaining = game.hearts, total = STARTING_HEARTS)

                    // The board takes whatever height the hearts and controls leave and
                    // sizes itself to fit it, so the controls are never pushed off-screen.
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        NonogramBoard(
                            game = game,
                            actions = actions,
                            markMode = state.markMode,
                            modifier = Modifier
                                .widthIn(max = BoardMaxWidth)
                                .fillMaxWidth(),
                        )
                    }

                    NonogramControls(
                        game = game,
                        state = state,
                        actions = actions,
                    )
                }
            }
        }
    }
}

/**
 * Win/lose banner plus the mark-mode toggle, restart and next-level buttons.
 *
 * Shared by the stacked phone layout and the expanded side panel so the two
 * never drift apart; emits items directly into the caller's column.
 */
@Composable
private fun NonogramControls(
    game: NonogramGameState,
    state: NonogramUiState,
    actions: NonogramGameActions,
) {
    val daily = state.mode == GameMode.DAILY
    var confirmRestart by remember { mutableStateOf(false) }
    // Restart clears every mark, so only ask when there is something to lose:
    // an untouched board restarts into itself.
    val hasProgress = game.filled.isNotEmpty() || game.crossed.isNotEmpty() ||
        game.revealedBlanks.isNotEmpty()
    when {
        game.isWon -> {
            Text(
                stringResource(R.string.solved),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (daily) {
                Text(
                    stringResource(R.string.daily_come_back_tomorrow),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            } else {
                // Disabled while the next puzzle generates, so a double tap cannot advance twice.
                Button(
                    onClick = { actions.nextLevel() },
                    enabled = !state.generating,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(UiR.string.next_level)) }
            }
        }

        game.isFailed -> {
            Text(
                stringResource(R.string.out_of_hearts),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = { actions.restartLevel() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.try_again)) }
        }

        else -> {
            MarkModeToggle(state.markMode, actions::setMarkMode)
            if (game.filled.isEmpty() && game.crossed.isEmpty() &&
                game.revealedBlanks.isEmpty()
            ) {
                Text(
                    stringResource(R.string.how_to_play),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                // Undo reverts the last stroke's marks; spent hearts and revealed mistakes stay.
                OutlinedButton(onClick = { actions.undo() }, enabled = state.canUndo) {
                    Text(stringResource(UiR.string.undo))
                }
                OutlinedButton(onClick = { if (hasProgress) confirmRestart = true else actions.restartLevel() }) {
                    Text(stringResource(UiR.string.restart))
                }
            }
        }
    }

    if (confirmRestart) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_restart_title),
            message = stringResource(R.string.confirm_restart_message),
            confirmLabel = stringResource(UiR.string.restart),
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = { actions.restartLevel() },
            onDismiss = { confirmRestart = false },
        )
    }
}
