package com.vayunmathur.games.nonogram.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.nonogram.R
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.games.nonogram.platform.NonogramGameActions
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.rememberHaptics

/**
 * The grid with its clues.
 *
 * Laid out as a two-by-two arrangement: an empty corner, the column clues across the top, the row
 * clues down the left, and the grid itself. The clue gutters are sized from the longest clue on that
 * axis so nothing is ever clipped, and everything scales off the cell size so a 5x5 and a 15x15 both
 * fill the available width.
 *
 * Touch goes through one handler on the whole grid ([strokeGestures]): taps, drags and long presses
 * all become strokes on [actions]. TalkBack's per-cell actions call [NonogramGameActions.tapCell] and
 * [NonogramGameActions.crossCell]; [markMode] only labels those actions.
 */
@Composable
fun NonogramBoard(
    game: NonogramGameState,
    actions: NonogramGameActions,
    modifier: Modifier = Modifier,
    markMode: MarkMode = MarkMode.FILL,
) {
    val size = game.size
    // Widest clue list decides the gutter, so a row needing "3 1 2" is not cut off.
    val rowGutterCells = game.puzzle.rowClues.maxOf { it.size }.coerceAtLeast(1)
    val colGutterCells = game.puzzle.colClues.maxOf { it.size }.coerceAtLeast(1)

    BoxWithConstraints(modifier) {
        // The clue gutters are narrower than a cell: they only ever hold a one or two digit number.
        val cell: Dp = maxWidth / (size + rowGutterCells * CLUE_CELL_FRACTION)
        val clueCell = cell * CLUE_CELL_FRACTION
        // Sized from the clue slot, not the grid cell, and paired with a line height equal to the
        // slot. Without that a 15x15's slots are shorter than the text's natural line box, the clue
        // column overflows its fixed height, and the row nearest the grid is clipped in half.
        //
        // The slot is laid out in dp, so the text is too: the user's font scale raises the preferred
        // size (ClueTextPreferred * fontScale) but never past what the slot can hold. Plain `.sp`
        // values here were multiplied by the font scale *after* fitting, so at 200% text the digits
        // were twice the height of their slot and clipped. Converting with toSp() divides the font
        // scale back out, which is what pins the rendered height to the slot.
        val density = LocalDensity.current
        val textSize = minOf(clueCell * CLUE_TEXT_FRACTION, ClueTextPreferred * density.fontScale)
        val fontSize = with(density) { textSize.toSp() }
        val lineHeight = with(density) { clueCell.toSp() }

        Column {
            Row {
                // Empty corner where the two gutters meet.
                Box(Modifier.size(clueCell * rowGutterCells, clueCell * colGutterCells))
                ColumnClues(game, colGutterCells, cell, clueCell, fontSize, lineHeight)
            }
            Row {
                RowClues(game, rowGutterCells, cell, clueCell, fontSize, lineHeight)
                Grid(game, markMode, cell, actions)
            }
        }
    }
}

@Composable
private fun Grid(
    game: NonogramGameState,
    markMode: MarkMode,
    cell: Dp,
    actions: NonogramGameActions,
) {
    val scheme = MaterialTheme.colorScheme
    // Resolved once per grid rather than once per cell per tap.
    val labels = CellLabels(
        filled = stringResource(R.string.cd_filled),
        crossed = stringResource(R.string.cd_crossed),
        revealed = stringResource(R.string.cd_revealed),
        blank = stringResource(R.string.cd_blank),
        fill = stringResource(R.string.action_fill),
        cross = stringResource(R.string.action_cross),
        uncross = stringResource(R.string.action_uncross),
    )
    val over = game.isOver
    val n = game.size
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptics = rememberHaptics()
    // Stable callbacks: the cells get the same lambda instance every recomposition, so a cell whose
    // mark did not change is skipped outright instead of being recomposed for a new closure.
    val currentActions by rememberUpdatedState(actions)
    val tap = remember { { index: Int -> currentActions.tapCell(index) } }
    val longPress = remember { { index: Int -> currentActions.crossCell(index) } }
    Box(
        Modifier
            .size(cell * n)
            .strokeGestures(n, rtl, actions = { currentActions }, onLongPress = { haptics.longPress() })
    ) {
        Column {
            for (row in 0 until n) {
                Row {
                    for (col in 0 until n) {
                        val index = row * n + col
                        // Each cell sees only its own mark, so one tap recomposes one cell, not all
                        // 225 of a 15x15.
                        val mark = game.markAt(index)
                        // Settled cells and finished boards take no input, so TalkBack offers no
                        // actions there rather than ones that silently do nothing.
                        val actionable = !over && !game.isLocked(index)
                        NonogramCell(
                            mark = mark,
                            index = index,
                            position = CellPosition(row, col),
                            stateWord = labels.wordFor(mark),
                            tapLabel = labels.tapLabel(mark, markMode).takeIf { actionable },
                            longPressLabel = labels.longPressLabel(mark, markMode).takeIf { actionable },
                            onTap = tap,
                            onLongPress = longPress,
                            modifier = Modifier.size(cell),
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .size(cell * n)
                .drawGridLines(n, scheme.outlineVariant, scheme.onSurface)
        )
    }
}

/** Clue digit height as a fraction of its slot, leaving room for the side bearings of "15". */
private const val CLUE_TEXT_FRACTION = 0.78f

/** Clue size at 100% font scale on a board roomy enough not to cap it. */
private val ClueTextPreferred = 13.dp

/** A clue slot's width as a fraction of a grid cell. Clues are digits, so they need less room. */
private const val CLUE_CELL_FRACTION = 0.62f
