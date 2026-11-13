package com.vayunmathur.games.nonogram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.nonogram.R
import com.vayunmathur.games.nonogram.data.CellMark
import com.vayunmathur.games.nonogram.data.MarkMode
import com.vayunmathur.library.ui.MaterialTheme

// One grid cell of [NonogramBoard], plus the grid-line overlay drawn over all of them.

/** The state words and action names TalkBack reads, looked up once per grid. */
@Immutable
internal data class CellLabels(
    val filled: String,
    val crossed: String,
    val revealed: String,
    val blank: String,
    val fill: String,
    val cross: String,
    val uncross: String,
) {
    fun wordFor(mark: CellMark): String = when (mark) {
        CellMark.FILLED -> filled
        CellMark.CROSSED -> crossed
        CellMark.REVEALED -> revealed
        CellMark.BLANK -> blank
    }

    /** What a tap (TalkBack double tap) does: the mode's own mark. */
    fun tapLabel(mark: CellMark, mode: MarkMode): String =
        if (mode == MarkMode.FILL) fill else noteLabel(mark)

    /** What a long press (TalkBack double tap and hold) does: the other mark. */
    fun longPressLabel(mark: CellMark, mode: MarkMode): String =
        if (mode == MarkMode.FILL) noteLabel(mark) else fill

    private fun noteLabel(mark: CellMark) = if (mark == CellMark.CROSSED) uncross else cross
}

/** Zero-based row and column, kept together so the cell signature stays short. */
@Immutable
internal data class CellPosition(val row: Int, val col: Int)

@Composable
internal fun NonogramCell(
    mark: CellMark,
    index: Int,
    position: CellPosition,
    stateWord: String,
    tapLabel: String?,
    longPressLabel: String?,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    // A fill only ever lands on a cell that belongs to the picture, because a wrong guess is turned
    // into a revealed blank instead. That revealed blank gets a tinted cell and an error-coloured X so
    // it reads as "the game told you" rather than the player's own grey note: colour plus a filled
    // background, so it does not rely on hue alone.
    val background = when (mark) {
        CellMark.FILLED -> scheme.onSurface
        CellMark.REVEALED -> scheme.errorContainer
        else -> Color.Transparent
    }
    val description = stringResource(R.string.cd_cell_position, position.row + 1, position.col + 1)

    Box(
        modifier
            .background(background)
            // Touch input is handled once for the whole grid (see strokeGestures), so cells only
            // draw and describe themselves.
            // Position as the description, the mark as state, and the same two moves a finger has
            // as named actions — without them TalkBack could read a cell but never fill or cross it.
            .clearAndSetSemantics {
                contentDescription = description
                stateDescription = stateWord
                if (tapLabel != null) onClick(label = tapLabel) { onTap(index); true }
                if (longPressLabel != null) onLongClick(label = longPressLabel) { onLongPress(index); true }
            },
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
internal fun Modifier.drawGridLines(size: Int, thin: Color, thick: Color) = drawBehind {
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
