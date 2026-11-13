package com.vayunmathur.games.arrows.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.arrows.R
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.data.STARTING_HEARTS
import com.vayunmathur.games.arrows.platform.ArrowsGameActions
import com.vayunmathur.games.arrows.platform.ArrowsUiState
import com.vayunmathur.games.arrows.ui.components.ArrowsBoard
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.DailyStreakText
import com.vayunmathur.library.ui.game.GameModeChooser
import com.vayunmathur.library.ui.game.GameTopBarActions
import com.vayunmathur.library.ui.game.HeartsRow

/** Caps the board on tablets, where a full-width grid would give absurdly large cells. */
private val BoardMaxWidth = 420.dp

/** Below this height (a landscape phone) the status moves beside the board. */
private val CompactHeight = 480.dp

/** The landscape status panel only needs room for a button and a line of text. */
private val SidePanelMaxWidth = 280.dp

/**
 * The board, with no dependency on the ViewModel so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`.
 */
@Composable
fun ArrowsGameScreen(
    state: ArrowsUiState,
    actions: ArrowsGameActions,
    onOpenSettings: () -> Unit,
    onOpenGameCenter: () -> Unit,
) {
    val daily = state.mode == GameMode.DAILY
    var confirmRestart by remember { mutableStateOf(false) }
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
        val game = state.game
        when {
            state.generationFailed || game == null -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (daily) DailyStreakText(state.dailyStreak)
                if (state.generationFailed) GenerationFailed(actions) else Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xxl),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            else -> BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = Spacing.md),
            ) {
                val board: @Composable (Modifier) -> Unit = { boardModifier ->
                    ArrowsBoard(
                        game = game,
                        showRoutes = state.showRoutes,
                        moves = state.moves,
                        onTapArrow = actions::tapArrow,
                        onMoveFinished = actions::finishMove,
                        modifier = boardModifier.widthIn(max = BoardMaxWidth),
                    )
                }
                val onRestart: (Boolean) -> Unit = { hasProgress ->
                    if (hasProgress) confirmRestart = true else actions.restartLevel()
                }
                // A landscape phone has too little height to stack the status under a tall
                // board, so the status moves to a panel beside it and the board gets the full
                // height. The board itself sizes its cells to fit both ways, so it never scrolls.
                if (maxWidth > maxHeight && maxHeight < CompactHeight) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(vertical = Spacing.sm),
                            contentAlignment = Alignment.Center,
                        ) { board(Modifier.fillMaxSize()) }
                        Column(
                            Modifier
                                .widthIn(max = SidePanelMaxWidth)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
                        ) {
                            GameHeader(state, game.hearts)
                            GameStatus(state, actions, onRestart)
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        GameHeader(state, game.hearts)
                        // Takes whatever height the header and status leave, but no more, so a
                        // board that fits keeps the status right under it.
                        Box(
                            Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) { board(Modifier.fillMaxWidth()) }
                        GameStatus(state, actions, onRestart)
                    }
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

@Composable
private fun GenerationFailed(actions: ArrowsGameActions) {
    Text(
        stringResource(R.string.generation_failed),
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    Button(onClick = { actions.retryGeneration() }) {
        Text(stringResource(R.string.try_again))
    }
}

/** The streak (daily only) and the hearts, above the board or at the top of the side panel. */
@Composable
private fun GameHeader(state: ArrowsUiState, hearts: Int) {
    if (state.mode == GameMode.DAILY) DailyStreakText(state.dailyStreak)
    HeartsRow(remaining = hearts, total = STARTING_HEARTS)
}

/**
 * What to do next: the cleared or failed message with its button, or the hint and Restart while
 * playing. [onRestart] gets whether the board has progress worth confirming before it is lost.
 */
@Composable
private fun GameStatus(state: ArrowsUiState, actions: ArrowsGameActions, onRestart: (Boolean) -> Unit) {
    val game = state.game ?: return
    val daily = state.mode == GameMode.DAILY
    when {
        game.isWon -> {
            Text(
                stringResource(R.string.board_cleared),
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
                // Disabled while the next board is loading, so a second tap
                // cannot land on a button that has already done its job.
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
            if (state.showHeartHint) {
                Text(
                    stringResource(R.string.heart_hint, STARTING_HEARTS),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            if (game.puzzle.mirrors.isNotEmpty() && !state.redirectorHintSeen) {
                RedirectorHint(onDismiss = actions::dismissRedirectorHint)
            }
            if (game.removed.isEmpty() && game.hearts == STARTING_HEARTS) {
                Text(
                    stringResource(R.string.how_to_play),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            // Restart puts every arrow back, so only ask when the
            // board has moved: a fresh board restarts into itself.
            val hasProgress = game.removed.isNotEmpty() || game.hearts != STARTING_HEARTS
            OutlinedButton(onClick = { onRestart(hasProgress) }) {
                Text(stringResource(UiR.string.restart))
            }
        }
    }
}

/**
 * The one-time explanation of redirectors, shown on the first board that has them until the
 * player dismisses it or clears that board.
 */
@Composable
private fun RedirectorHint(onDismiss: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.redirector_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) }
    }
}
