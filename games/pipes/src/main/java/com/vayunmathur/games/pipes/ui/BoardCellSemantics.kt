package com.vayunmathur.games.pipes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.vayunmathur.games.pipes.R
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.platform.PipesGameState
import kotlin.math.roundToInt

/**
 * Invisible per-cell nodes laid over the board canvas so TalkBack can explore it: each
 * cell reads its row and column and whether it holds a dot, a pipe or nothing, naming the
 * colour by the same letter colour-blind mode draws. The nodes take no input, so touches
 * still reach the canvas underneath.
 *
 * Positions use AbsoluteAlignment.TopLeft plus absoluteOffset: the canvas never mirrors
 * in RTL, so neither may these (a plain Box would align them to the top-right in RTL).
 */
@Composable
fun BoardCellSemantics(geometry: BoardGeometry, gameState: PipesGameState, boardSize: Dp) {
    val cellDp = with(LocalDensity.current) { geometry.cellSizePx.toDp() }
    val dots = geometry.levelData.endpoints.flatMap { ep -> ep.cells.map { it to ep.colorIndex } }.toMap()
    Box(Modifier.size(boardSize), contentAlignment = AbsoluteAlignment.TopLeft) {
        for ((cell, rect) in geometry.cellRects) {
            val description = cellDescription(cell, dots[cell], gameState.cellOwner[cell])
            Box(
                Modifier
                    .absoluteOffset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .size(cellDp)
                    .semantics { contentDescription = description }
            )
        }
    }
}

@Composable
private fun cellDescription(cell: CellPos, dotColor: Int?, owner: Int?): String {
    val row = cell.row + 1
    val col = cell.col + 1
    return when {
        dotColor != null -> stringResource(R.string.board_cell_dot, row, col, colorLetter(dotColor))
        owner != null -> stringResource(R.string.board_cell_pipe, row, col, colorLetter(owner))
        else -> stringResource(R.string.board_cell_empty, row, col)
    }
}

/** The letter colour-blind mode draws for [colorIndex]. */
internal fun colorLetter(colorIndex: Int): String = ('A' + (colorIndex % 26)).toString()
