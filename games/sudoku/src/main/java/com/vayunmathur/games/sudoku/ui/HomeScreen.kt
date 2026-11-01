package com.vayunmathur.games.sudoku.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.Route
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.GameConfig
import com.vayunmathur.games.sudoku.platform.SudokuViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.GameTopBarActions
import com.vayunmathur.library.ui.game.formatDuration
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    backStack: NavBackStack<Route>,
    viewModel: SudokuViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val answerMode by viewModel.answerMode.collectAsState()
    var size by rememberSaveable { mutableStateOf(GameConfig().size) }
    var difficulty by rememberSaveable { mutableStateOf(GameConfig().difficulty) }

    AppScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            GameTopBarActions(onOpenGameCenter = { backStack.add(Route.GameCenter) })
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        // Menu column; letterboxed on expanded windows so buttons and stat
        // cards keep a readable measure on desktop.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val active = uiState.game?.takeIf { !it.isWon }
            if (active != null) {
                Button(
                    onClick = {
                        backStack.add(
                            Route.Game(GameConfig(active.size, active.difficulty, active.answerMode))
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(UiR.string.continue_label)) }
            }
            Text(
                stringResource(R.string.board_size),
                style = MaterialTheme.typography.labelLarge,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                BoardSize.entries.forEachIndexed { index, value ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, BoardSize.entries.size),
                        onClick = { size = value },
                        selected = size == value,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            value.displayName(),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.difficulty),
                style = MaterialTheme.typography.labelLarge,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Difficulty.entries.forEachIndexed { index, value ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, Difficulty.entries.size),
                        onClick = { difficulty = value },
                        selected = difficulty == value,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            value.displayName(),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            SettingsSwitchRow(
                title = stringResource(R.string.answer_mode),
                supportingText = stringResource(R.string.answer_mode_description),
                checked = answerMode,
                onCheckedChange = { viewModel.setAnswerMode(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val config = GameConfig(size, difficulty, answerMode)
                    viewModel.newGame(config)
                    backStack.add(Route.Game(config))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(UiR.string.new_game)) }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                BoardSize.entries.forEach { boardSize ->
                    SizeStatsCard(boardSize, viewModel, Modifier.weight(1f))
                }
            }
        }
        }
    }
}

@Composable
private fun SizeStatsCard(
    size: BoardSize,
    viewModel: SudokuViewModel,
    modifier: Modifier = Modifier,
) {
    val stats = viewModel.getSizeStats(size)
    Card(modifier) {
        Column(
            Modifier
                .padding(Spacing.md)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                size.displayName(),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "${stats.gamesWon}/${stats.gamesPlayed}",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.won),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (stats.bestTimeSeconds < Int.MAX_VALUE) {
                Text(
                    formatDuration(stats.bestTimeSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
