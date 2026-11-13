package com.vayunmathur.games.sudoku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.games.sudoku.data.sudokuSymbol
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.res.stringResource

/**
 * The playing grid.
 *
 * Cells are laid out as plain composables and every line is painted once by [drawGridLines] on top,
 * rather than each cell drawing its own border. Per-cell borders would double up on shared edges,
 * so a box boundary would read as two adjacent thick lines instead of one.
 */
@Composable
fun SudokuGrid(
    game: SudokuGameState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    highlightMistakes: Boolean = true,
) {
    val size = game.size
    val side = size.side
    val scheme = MaterialTheme.colorScheme
    val selected = game.selected
    val selectedValue = if (selected >= 0) game.valueAt(selected) else 0
    // Digits repeated within a row, column or box. Computable from the board alone, so
    // marking them shows the player a mistake without revealing the solution. The scan is
    // quadratic in the cell count, so it reruns only when the digits change — not on every
    // selection move.
    val conflicts = remember(game.size, game.givens, game.entries) { game.conflictingIndices() }

    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val cell: Dp = maxWidth / side
        Column {
            for (row in 0 until side) {
                Row {
                    for (col in 0 until side) {
                        val index = row * side + col
                        SudokuCell(
                            game = game,
                            index = index,
                            isSelected = index == selected,
                            // Shared row, column or box: the lines the player is reasoning along.
                            isPeer = selected >= 0 && index != selected && arePeers(size, index, selected),
                            // Every copy of the digit under the cursor, wherever it sits.
                            isSameValue = selectedValue != 0 &&
                                index != selected &&
                                game.valueAt(index) == selectedValue,
                            isConflict = index in conflicts,
                            highlightMistakes = highlightMistakes,
                            modifier = Modifier
                                .size(cell)
                                .clickable { onSelect(index) },
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawGridLines(size, scheme.outlineVariant, scheme.onSurface)
        )
    }
}

/** True when [a] and [b] share a row, a column or a box. */
private fun arePeers(size: BoardSize, a: Int, b: Int): Boolean {
    val side = size.side
    return a / side == b / side || a % side == b % side || size.boxOf(a) == size.boxOf(b)
}

@Composable
private fun SudokuCell(
    game: SudokuGameState,
    index: Int,
    isSelected: Boolean,
    isPeer: Boolean,
    isSameValue: Boolean,
    isConflict: Boolean,
    highlightMistakes: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val value = game.valueAt(index)
    val given = game.isGiven(index)

    val background = when {
        isSelected -> scheme.primary.copy(alpha = 0.40f)
        // A duplicated digit, visible from the board alone: marked in the error palette
        // without saying which copy is wrong, let alone what belongs there instead.
        isConflict -> scheme.errorContainer.copy(alpha = 0.55f)
        isSameValue -> scheme.tertiary.copy(alpha = 0.30f)
        isPeer -> scheme.primary.copy(alpha = 0.13f)
        else -> Color.Transparent
    }

    // Givens are the puzzle, entries are the player's work, so they read differently. A
    // conflicting entry turns error-coloured — duplicates are derivable from the board alone,
    // so marking them cannot hand out the answer, and the highlight-mistakes setting does not
    // apply. A conflicting given keeps its own colour, because the given is never the copy
    // that is misplaced. Answer mode never has wrong entries to draw, since a wrong digit is
    // refused rather than written.
    val digitColor = when {
        isConflict && !given -> scheme.error
        !given && highlightMistakes && game.isWrong(index) -> scheme.error
        given -> scheme.onSurface
        else -> scheme.primary
    }

    val spoken = if (value == 0) stringResource(R.string.cd_empty) else sudokuSymbol(value)
    val side = game.size.side
    val description =
        stringResource(R.string.cd_cell, index / side + 1, index % side + 1, spoken)
    Box(
        modifier
            .background(background)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when {
            value != 0 -> Text(
                text = sudokuSymbol(value),
                color = digitColor,
                // Sized from the cell so a 4x4 does not render tiny digits in large squares.
                fontSize = (game.size.digitScale * 22).sp,
                fontWeight = if (given) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
            )

            game.notes[index] != 0 -> NoteMarks(game.size, game.notes[index])
        }
    }
}

/**
 * Pencil marks, arranged in the board's own box shape.
 *
 * Reusing [BoardSize.boxRows] x [BoardSize.boxCols] means digit `d` always sits in the same corner
 * of a cell that it does in a box, so the marks read as a miniature of the grid: 3x3 for 9x9, two
 * rows of three for 6x6, 2x2 for 4x4.
 */
@Composable
private fun NoteMarks(size: BoardSize, mask: Int) {
    // Weighted rows and columns rather than laid-out text: a space is narrower than a digit, so
    // padding absent marks with " " would let the columns drift out of line.
    Column(Modifier.fillMaxSize()) {
        for (row in 0 until size.boxRows) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                for (col in 0 until size.boxCols) {
                    val digit = row * size.boxCols + col + 1
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (mask and (1 shl (digit - 1)) != 0) {
                            Text(
                                text = sudokuSymbol(digit),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = (size.digitScale * 8).sp,
                                lineHeight = (size.digitScale * 9).sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Every cell edge, with box boundaries and the outer frame drawn heavier.
 *
 * Drawn in one pass over the whole grid so shared edges are painted exactly once.
 */
private fun Modifier.drawGridLines(
    size: BoardSize,
    thin: Color,
    thick: Color,
) = drawBehind {
    val side = size.side
    val step = this.size.width / side
    val thinPx = 1.dp.toPx()
    val thickPx = 2.5f * thinPx

    for (i in 0..side) {
        val offset = i * step
        // The outer frame and the box seams carry the heavy stroke.
        val vertical = i % size.boxCols == 0 || i == side
        val horizontal = i % size.boxRows == 0 || i == side
        drawLine(
            color = if (vertical) thick else thin,
            start = Offset(offset, 0f),
            end = Offset(offset, this.size.height),
            strokeWidth = if (vertical) thickPx else thinPx,
        )
        drawLine(
            color = if (horizontal) thick else thin,
            start = Offset(0f, offset),
            end = Offset(this.size.width, offset),
            strokeWidth = if (horizontal) thickPx else thinPx,
        )
    }
}

/** Scales type with the board: a 6x6 cell is twice the width of a 12x12 cell. */
private val BoardSize.digitScale: Float
    get() = when (this) {
        BoardSize.SIX -> 1.25f
        BoardSize.NINE -> 1f
        BoardSize.TWELVE -> 0.78f
    }
