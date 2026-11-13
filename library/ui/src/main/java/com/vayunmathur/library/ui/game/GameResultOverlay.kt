package com.vayunmathur.library.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * The card shown over a finished board.
 *
 * Drawn over a scrim so it reads as modal: without one, whatever the game puts under the board — a
 * number pad, an action row — shows around the card's edges and the board looks half-live.
 *
 * The scrim is also a touch barrier: it swallows every pointer event that lands outside the card, so a
 * tap meant to dismiss nothing cannot fall through to a cell or a number-pad key on the finished board.
 *
 * [won] only picks the title colour; the caller supplies the words, because "Solved", "Field Cleared"
 * and "Congratulations" all mean the same thing in different games and none of them belongs here.
 * [stats] is a free slot for the lines a game wants to boast about, which is the one part that has
 * never been the same twice.
 *
 * @param onPlayAgain primary action. Null hides it, for a daily board where there is nothing to replay.
 */
@Composable
fun GameResultOverlay(
    title: String,
    won: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayAgain: (() -> Unit)? = null,
    playAgainLabel: String = stringResource(R.string.game_play_again),
    backLabel: String = stringResource(R.string.game_back),
    stats: @Composable ColumnScope.() -> Unit = {},
) {
    Box(modifier.fillMaxSize()) {
        // A sibling under the card rather than its parent: a parent that swallowed events would also
        // eat the moves of a press on the card's own buttons and cancel it.
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                .swallowPointerInput(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    Modifier.padding(Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (won) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    stats()
                    if (onPlayAgain != null) {
                        Button(onClick = onPlayAgain, Modifier.fillMaxWidth()) { Text(playAgainLabel) }
                    }
                    TextButton(onClick = onBack, Modifier.fillMaxWidth()) { Text(backLabel) }
                }
            }
        }
    }
}

/**
 * Makes this node a hit target that consumes everything it receives, so nothing drawn beneath it — and
 * no ancestor gesture detector — sees the event.
 */
private fun Modifier.swallowPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

/** Dark enough to mute the board behind, light enough to keep it recognisable. */
private const val SCRIM_ALPHA = 0.7f
