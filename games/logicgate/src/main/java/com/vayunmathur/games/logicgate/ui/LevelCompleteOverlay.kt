package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.platform.GameUiState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.game.GameResultOverlay

/** Shown when an edit makes the circuit pass every test: stars, cost against par, and onwards. */
@Composable
fun LevelCompleteOverlay(
    state: GameUiState,
    onNext: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = if (state.nextLevelId == null) R.string.lg_computer_title else R.string.lg_solved_title
    GameResultOverlay(
        title = stringResource(title),
        won = true,
        onBack = onDismiss,
        modifier = modifier,
        onPlayAgain = onNext,
        playAgainLabel = stringResource(R.string.lg_next_level),
        backLabel = stringResource(R.string.lg_keep_editing),
    ) {
        StarRating(state.stars, size = 36.dp)
        Text(stringResource(R.string.lg_cost_vs_par, state.cost, state.level.par), style = MaterialTheme.typography.titleMedium)
        if (state.level.unlocks.isNotEmpty()) {
            val chips = state.level.unlocks.map { chipLabel(it) }.joinToString()
            Text(stringResource(R.string.lg_new_chips, chips), color = MaterialTheme.colorScheme.primary)
        }
    }
}
