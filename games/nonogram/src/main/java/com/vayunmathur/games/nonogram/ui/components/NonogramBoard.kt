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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        val fontSize = (clueCell.value * 0.78f).coerceIn(6f, 13f).sp
        val lineHeight = clueCell.value.sp

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
    Box {
        Column {
            for (row in 0 until game.size) {
                Row {
                    for (col in 0 until game.size) {
                        val index = row * game.size + col
                        NonogramCell(
                            game = game,
                            index = index,
                            onTap = { onTap(index) },
                            onLongPress = { onLongPress(index) },
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

@Composable
private fun NonogramCell(
    game: NonogramGameState,
    index: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val mark = game.markAt(index)

    // A fill only ever lands on a cell that belongs to the picture, because a wrong guess is turned
    // into a revealed blank instead. That revealed blank gets a tinted cell and an error-coloured X so
    // it reads as "the game told you" rather than the player's own grey note: colour plus a filled
    // background, so it does not rely on hue alone.
    val background = when (mark) {
        CellMark.FILLED -> scheme.onSurface
        CellMark.REVEALED -> scheme.errorContainer
        else -> Color.Transparent
    }

    val stateWord = when (mark) {
        CellMark.FILLED -> stringResource(R.string.cd_filled)
        CellMark.CROSSED -> stringResource(R.string.cd_crossed)
        CellMark.REVEALED -> stringResource(R.string.cd_revealed)
        CellMark.BLANK -> stringResource(R.string.cd_blank)
    }
    val description = stringResource(
        R.string.cd_cell,
        index / game.size + 1,
        index % game.size + 1,
        stateWord,
    )

    Box(
        modifier
            .background(background)
            .pointerInput(index, game.isOver, game.isLocked(index)) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
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

/** A clue slot's width as a fraction of a grid cell. Clues are digits, so they need less room. */
private const val CLUE_CELL_FRACTION = 0.62f
