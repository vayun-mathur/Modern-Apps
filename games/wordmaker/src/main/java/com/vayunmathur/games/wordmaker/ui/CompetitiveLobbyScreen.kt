package com.vayunmathur.games.wordmaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.platform.CompetitiveLobbyActions
import com.vayunmathur.games.wordmaker.platform.CompetitiveLobbyUiState
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel
import com.vayunmathur.games.wordmaker.ui.components.DifficultyDropdown
import com.vayunmathur.games.wordmaker.ui.components.WordMakerModeChooser
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.GameTopBarActions

@Composable
fun CompetitiveLobbyPage(
    viewModel: WordMakerViewModel,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val gameMode by viewModel.gameMode.collectAsState()
    val difficulty by viewModel.difficulty.collectAsState()
    val score by viewModel.competitiveScore.collectAsState()
    val result by viewModel.competitiveResult.collectAsState()

    CompetitiveLobbyScreen(
        state = CompetitiveLobbyUiState(
            gameMode = gameMode,
            difficulty = difficulty,
            score = score,
            result = result
        ),
        actions = viewModel,
        onOpenGameCenter = onOpenGameCenter,
        onOpenSettings = onOpenSettings
    )
}


@Composable
fun CompetitiveLobbyScreen(
    state: CompetitiveLobbyUiState,
    actions: CompetitiveLobbyActions,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val difficulty = state.difficulty
    val result = state.result

    AppScaffold(
        title = {
            WordMakerModeChooser(
                selected = state.gameMode,
                onSelected = { actions.setGameMode(it) },
            )
        },
        actions = {
            GameTopBarActions(
                onOpenGameCenter = onOpenGameCenter,
                onOpenSettings = onOpenSettings,
            )
        },
        alignment = AppBarAlignment.Center,
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.competitive_score, state.score),
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )

            result?.let {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = if (it.won) {
                        pluralStringResource(R.plurals.competitive_solved, it.delta, it.delta)
                    } else {
                        pluralStringResource(R.plurals.competitive_timed_out, it.delta, it.delta)
                    },
                    color = if (it.won) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            }

            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.competitive_difficulty), fontWeight = FontWeight.Bold)
            DifficultyDropdown(selected = difficulty, onSelected = { actions.setDifficulty(it) })
            Text(pluralStringResource(R.plurals.competitive_time_limit, difficulty.timeLimitSeconds, difficulty.timeLimitSeconds))

            Spacer(Modifier.height(32.dp))
            Button(onClick = { actions.loadNextCompetitiveLevel() }) {
                Text(
                    stringResource(
                        if (result == null) R.string.competitive_start else UiR.string.next_level
                    )
                )
            }
        }
    }
}
