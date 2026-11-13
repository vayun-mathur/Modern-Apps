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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
        // The smaller side, not the width: if a caller's constraints ever win over the aspect
        // ratio (a squashed landscape slot), width-sized cells would overflow the height and the
        // bottom row would be clipped. Sized this way the whole grid always stays visible.
        val cell: Dp = minOf(maxWidth, maxHeight) / side
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
                            cell = cell,
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
    cell: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
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
    val shownWrong = !given && highlightMistakes && game.isWrong(index)
    val flagged = (isConflict && !given) || shownWrong
    val digitColor = when {
        flagged -> scheme.error
        given -> scheme.onSurface
        else -> scheme.primary
    }

    val spoken = if (value == 0) stringResource(R.string.cd_empty) else sudokuSymbol(value)
    val side = game.size.side
    val description =
        stringResource(R.string.cd_cell, index / side + 1, index % side + 1, spoken)
    val state = cellStateDescription(
        given = given,
        hasValue = value != 0,
        shownWrong = shownWrong,
        isConflict = isConflict,
        notes = if (value == 0) game.notes[index] else 0,
        size = game.size,
    )
    Box(
        modifier
            .background(background)
            .clearAndSetSemantics {
                contentDescription = description
                // TalkBack hears what sighted players see from colour and weight alone: the
                // cursor, given vs entered, the mistake markers, and the pencil marks.
                selected = isSelected
                if (state.isNotEmpty()) stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            value != 0 -> Text(
                text = sudokuSymbol(value),
                color = digitColor,
                // Scaled per board so a 6x6 does not render tiny digits in large squares, and
                // capped by the cell so a large font scale cannot push it past the cell edges.
                fontSize = density.fitSp((game.size.digitScale * 22).sp, cell * DIGIT_CELL_FRACTION),
                lineHeight = LINE_HEIGHT_EM.em,
                fontWeight = if (given) FontWeight.Bold else FontWeight.Medium,
                // A shape cue as well as the error colour, so a wrong or duplicated entry is
                // visible to colour-blind players and in greyscale, not by hue alone.
                textDecoration = if (flagged) TextDecoration.Underline else null,
                textAlign = TextAlign.Center,
            )

            game.notes[index] != 0 -> NoteMarks(game.size, game.notes[index], cell)
        }
    }
}

/**
 * The spoken state of one cell, beyond its position and digit.
 *
 * "Incorrect" follows the highlight-mistakes setting, exactly like the red digit, so TalkBack never
 * reveals a wrong entry the board is hiding; a duplicate is derivable from the board, so it is always
 * announced.
 */
@Composable
private fun cellStateDescription(
    given: Boolean,
    hasValue: Boolean,
    shownWrong: Boolean,
    isConflict: Boolean,
    notes: Int,
    size: BoardSize,
): String {
    val parts = buildList {
        if (hasValue) add(stringResource(if (given) R.string.cd_given else R.string.cd_entered))
        if (shownWrong) add(stringResource(R.string.cd_wrong))
        if (isConflict) add(stringResource(R.string.cd_conflict))
        if (notes != 0) {
            val marks = (1..size.side).filter { notes and (1 shl (it - 1)) != 0 }
                .joinToString(" ") { sudokuSymbol(it) }
            add(stringResource(R.string.cd_notes, marks))
        }
    }
    return parts.joinToString(", ")
}

/**
 * Pencil marks, arranged in the board's own box shape.
 *
 * Reusing [BoardSize.boxRows] x [BoardSize.boxCols] means digit `d` always sits in the same corner
 * of a cell that it does in a box, so the marks read as a miniature of the grid: 3x3 for 9x9, two
 * rows of three for 6x6, 2x2 for 4x4.
 */
@Composable
private fun NoteMarks(size: BoardSize, mask: Int, cell: Dp) {
    // Each mark's slot is a fixed fraction of the cell, so the cap is too: the narrower of
    // the slot's height (one line) and its width (one glyph, 12x12 slots are tall and thin).
    val slotHeight = cell / size.boxRows
    val slotWidth = cell / size.boxCols
    val density = LocalDensity.current
    val markSize = density.fitSp(
        (size.digitScale * 8).sp,
        minOf(slotHeight * MARK_SLOT_HEIGHT_FRACTION, slotWidth * MARK_SLOT_WIDTH_FRACTION),
    )
    // The slot height in non-scaling sp: an sp line height grows with the font setting and
    // overflowed the slot at 200%, clipping the bottom row of marks.
    val markLine = with(density) { slotHeight.toSp() }
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
                                fontSize = markSize,
                                lineHeight = markLine,
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

/** A cell digit never renders taller than this share of the cell. */
private const val DIGIT_CELL_FRACTION = 0.62f

/** Line box of a cell digit, relative to its size, so the line cannot outgrow the cell. */
private const val LINE_HEIGHT_EM = 1.15f

/** A pencil mark's size cap, as shares of its slot's height and width. */
private const val MARK_SLOT_HEIGHT_FRACTION = 0.8f
private const val MARK_SLOT_WIDTH_FRACTION = 1.1f

/** Scales type with the board: a 6x6 cell is twice the width of a 12x12 cell. */
private val BoardSize.digitScale: Float
    get() = when (this) {
        BoardSize.SIX -> 1.25f
        BoardSize.NINE -> 1f
        BoardSize.TWELVE -> 0.78f
    }
