package com.vayunmathur.library.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R
import com.vayunmathur.library.ui.Text

/**
 * The level readout, with arrows for stepping to the neighbouring level.
 *
 * [levelIndex] is 0-based because that is what a caller indexes its level list with; the number shown is
 * one higher. Arrows disable at the ends rather than wrapping, so the player can tell where the pack
 * stops.
 *
 * A finished level is marked "Completed" in the primary colour.
 *
 * [title] is a parameter because the games do not agree on the noun — unblockjam calls them puzzles — and
 * sharing the mechanism should not flatten their wording.
 */
@Composable
fun LevelPickerBox(
    levelIndex: Int,
    maxLevelIndex: Int,
    isCompleted: Boolean,
    onLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.game_level),
) {
    GameInfoBox(title = title, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(
                onClick = { onLevelChange(levelIndex - 1) },
                enabled = levelIndex > 0,
            ) { IconBack() }
            Text(text = "${levelIndex + 1}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(
                onClick = { onLevelChange(levelIndex + 1) },
                enabled = levelIndex < maxLevelIndex,
            ) { IconArrowForward() }
        }
        if (isCompleted) {
            Text(
                text = stringResource(R.string.game_completed),
                // An accomplishment, so the accent colour - never error red, which reads as a failure.
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The move counter, with the player's best against the known optimum, labelled as such:
 * "Best 12 · Par 10". A bare "12 / 10" read as a fraction or a score out of ten.
 *
 * [bestScore] is null until the level has been finished once, and shows as a dash rather than a zero —
 * "no best yet" and "a best of nothing" are different facts. TalkBack hears the line spelled out
 * ("Best 12 moves, par 10" / "No best yet, par 10") rather than the dash and the middle dot.
 */
@Composable
fun MovesBox(
    moves: Int,
    bestScore: Int?,
    optimalMoves: Int,
    modifier: Modifier = Modifier,
) {
    GameInfoBox(title = stringResource(R.string.game_moves), modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "$moves", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            val spoken = if (bestScore == null) {
                stringResource(R.string.game_moves_no_best_par_description, optimalMoves)
            } else {
                pluralStringResource(R.plurals.game_moves_best_par_description, bestScore, bestScore, optimalMoves)
            }
            // Material's Text directly, for autoSize: the box is a fixed 150 dp and a translated
            // "Best 120 · Par 98" must shrink to fit rather than wrap or clip.
            androidx.compose.material3.Text(
                text = stringResource(R.string.game_moves_best_par, bestScore?.toString() ?: NO_BEST, optimalMoves),
                modifier = Modifier.semantics { contentDescription = spoken },
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 18.sp),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** Shown for a best score that does not exist yet. */
private const val NO_BEST = "–"
