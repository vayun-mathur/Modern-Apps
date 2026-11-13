package com.vayunmathur.games.nonogram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.nonogram.R
import com.vayunmathur.games.nonogram.data.CellMark
import com.vayunmathur.games.nonogram.data.NonogramGameState
import com.vayunmathur.library.ui.ColorScheme
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * The grid with its clues.
 *
 * Laid out as a two-by-two arrangement: an empty corner, the column clues across the top, the row
 * clues down the left, and the grid itself. The clue gutters are sized from the longest clue on that
 * axis so nothing is ever clipped, and everything scales off the cell size so a 5x5 and a 15x15 both
 * fill the available width.
 */
@Composable
fun NonogramBoard(
    game: NonogramGameState,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier,
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
                Grid(game, cell, onTap, onLongPress)
            }
        }
    }
}

@Composable
private fun ColumnClues(
    game: NonogramGameState,
    gutterCells: Int,
    cell: Dp,
    clueCell: Dp,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    Row {
        for (col in 0 until game.size) {
            val clues = game.puzzle.colClues[col]
            Column(
                Modifier.size(cell, clueCell * gutterCells),
                // Bottom-aligned so the last clue sits against the grid it describes.
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (clue in clues.ifEmpty { listOf(0) }) {
                    ClueText(clue, fontSize, lineHeight, Modifier.size(cell, clueCell))
                }
            }
        }
    }
}

@Composable
private fun RowClues(
    game: NonogramGameState,
    gutterCells: Int,
    cell: Dp,
    clueCell: Dp,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    Column {
        for (row in 0 until game.size) {
            val clues = game.puzzle.rowClues[row]
            Row(
                Modifier.size(clueCell * gutterCells, cell),
                // End-aligned for the same reason: the final clue abuts the grid.
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (clue in clues.ifEmpty { listOf(0) }) {
                    ClueText(clue, fontSize, lineHeight, Modifier.size(clueCell, cell))
                }
            }
        }
    }
}

@Composable
private fun ClueText(
    clue: Int,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = clue.toString(),
            fontSize = fontSize,
            lineHeight = lineHeight,
            maxLines = 1,
            softWrap = false,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = if (clue == 0) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Grid(
    game: NonogramGameState,
    cell: Dp,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // Resolved once per grid rather than once per cell per tap.
    val labels = CellLabels(
        filled = stringResource(R.string.cd_filled),
        crossed = stringResource(R.string.cd_crossed),
        revealed = stringResource(R.string.cd_revealed),
        blank = stringResource(R.string.cd_blank),
    )
    // Stable callbacks: the cells get the same lambda instance every recomposition, so a cell whose
    // mark did not change is skipped outright instead of being recomposed for a new closure.
    val currentTap by rememberUpdatedState(onTap)
    val currentLongPress by rememberUpdatedState(onLongPress)
    val tap = remember { { index: Int -> currentTap(index) } }
    val longPress = remember { { index: Int -> currentLongPress(index) } }
    Box {
        Column {
            for (row in 0 until game.size) {
                Row {
                    for (col in 0 until game.size) {
                        val index = row * game.size + col
                        // Each cell sees only its own mark, so one tap recomposes one cell, not all
                        // 225 of a 15x15.
                        val mark = game.markAt(index)
                        NonogramCell(
                            mark = mark,
                            index = index,
                            row = row,
                            col = col,
                            stateWord = labels.wordFor(mark),
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
                .size(cell * game.size)
                .drawGridLines(game.size, scheme.outlineVariant, scheme.onSurface)
        )
    }
}

/** The four state words TalkBack reads, looked up once per grid. */
@Immutable
private data class CellLabels(
    val filled: String,
    val crossed: String,
    val revealed: String,
    val blank: String,
) {
    fun wordFor(mark: CellMark): String = when (mark) {
        CellMark.FILLED -> filled
        CellMark.CROSSED -> crossed
        CellMark.REVEALED -> revealed
        CellMark.BLANK -> blank
    }
}

@Composable
private fun NonogramCell(
    mark: CellMark,
    index: Int,
    row: Int,
    col: Int,
    stateWord: String,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current

    // A fill only ever lands on a cell that belongs to the picture, because a wrong guess is turned
    // into a revealed blank instead. That revealed blank gets a tinted cell and an error-coloured X so
    // it reads as "the game told you" rather than the player's own grey note: colour plus a filled
    // background, so it does not rely on hue alone.
    val background = when (mark) {
        CellMark.FILLED -> scheme.onSurface
        CellMark.REVEALED -> scheme.errorContainer
        else -> Color.Transparent
    }
    val description = stringResource(R.string.cd_cell, row + 1, col + 1, stateWord)

    Box(
        modifier
            .background(background)
            .pointerInput(index) {
                // The ViewModel ignores taps on settled cells and finished boards, so the handler
                // does not need to restart whenever those change.
                detectTapGestures(
                    onTap = { onTap(index) },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress(index)
                    },
                )
            }
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (mark == CellMark.CROSSED || mark == CellMark.REVEALED) {
            val revealed = mark == CellMark.REVEALED
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(CrossInset)
                    .drawCross(
                        color = if (revealed) scheme.onErrorContainer else scheme.onSurfaceVariant,
                        width = if (revealed) RevealedCrossWidth else NoteCrossWidth,
                    )
            )
        }
    }
}

/** Keeps the X clear of the cell borders so it reads as a mark rather than a filled square. */
private val CrossInset = 3.dp

/** The player's own note: a light grey X. */
private val NoteCrossWidth = 1.5.dp

/** A blank a mistake revealed: heavier, so it differs in weight as well as colour. */
private val RevealedCrossWidth = 2.5.dp

private fun Modifier.drawCross(color: Color, width: Dp) = drawBehind {
    val stroke = width.toPx()
    drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), stroke)
    drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke)
}

/**
 * Every cell edge, with a heavier stroke every five cells and around the outside.
 *
 * The five-cell grouping is the standard nonogram aid: it lets a player count along a long line
 * without losing their place. Drawn in one pass over the whole grid so shared edges are painted once.
 */
private fun Modifier.drawGridLines(size: Int, thin: Color, thick: Color) = drawBehind {
    val step = this.size.width / size
    val thinPx = 1.dp.toPx()
    val thickPx = 2f * thinPx

    for (i in 0..size) {
        val offset = i * step
        val heavy = i % GROUP == 0 || i == size
        val color = if (heavy) thick else thin
        val width = if (heavy) thickPx else thinPx
        drawLine(color, Offset(offset, 0f), Offset(offset, this.size.height), width)
        drawLine(color, Offset(0f, offset), Offset(this.size.width, offset), width)
    }
}

/** Cells per heavy guide line. */
private const val GROUP = 5

/** Clue digit height as a fraction of its slot, leaving room for the side bearings of "15". */
private const val CLUE_TEXT_FRACTION = 0.78f

/** Clue size at 100% font scale on a board roomy enough not to cap it. */
private val ClueTextPreferred = 13.dp

/** A clue slot's width as a fraction of a grid cell. Clues are digits, so they need less room. */
private const val CLUE_CELL_FRACTION = 0.62f
