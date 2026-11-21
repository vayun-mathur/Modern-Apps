package com.vayunmathur.games.pipes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.vayunmathur.games.pipes.R
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.platform.PipesGameState
import kotlin.math.roundToInt

/**
 * Invisible per-cell nodes laid over the board canvas so TalkBack can explore and play it:
 * each cell reads its row and column and whether it holds a dot, a pipe or nothing, naming
 * the colour by the same letter colour-blind mode draws.
 *
 * Playing without touch mirrors a finger stroke, using the same callbacks the canvas
 * gesture drives ([onStartDraw] on finger-down, [onExtendPath] as it enters a cell,
 * [onCommitDraw] when it lifts): double-tapping a dot or pipe starts a stroke there,
 * double-tapping each next cell extends it, and the "Finish pipe" action on any cell
 * commits it. The nodes carry only semantics actions — no pointer input — so touches
 * still reach the canvas underneath.
 *
 * Positions use AbsoluteAlignment.TopLeft plus absoluteOffset: the canvas never mirrors
 * in RTL, so neither may these (a plain Box would align them to the top-right in RTL).
 */
@Composable
fun BoardCellSemantics(
    geometry: BoardGeometry,
    gameState: PipesGameState,
    boardSize: Dp,
    activeColor: Int? = null,
    isLevelWon: Boolean = false,
    onStartDraw: (CellPos) -> Unit = {},
    onExtendPath: (CellPos) -> Unit = {},
    onCommitDraw: () -> Unit = {},
) {
    val cellDp = with(LocalDensity.current) { geometry.cellSizePx.toDp() }
    val dots = geometry.levelData.endpoints.flatMap { ep -> ep.cells.map { it to ep.colorIndex } }.toMap()
    val startLabel = stringResource(R.string.board_action_start_pipe)
    val extendLabel = stringResource(R.string.board_action_extend_pipe)
    val finishLabel = stringResource(R.string.board_action_finish_pipe)
    val drawingLabel = stringResource(R.string.board_state_drawing)
    Box(Modifier.size(boardSize), contentAlignment = AbsoluteAlignment.TopLeft) {
        for ((cell, rect) in geometry.cellRects) {
            val description = cellDescription(cell, dots[cell], gameState.cellOwner[cell])
            // Only a dot or an existing pipe can start a stroke — the same cells a
            // finger-down can begin on (startDraw ignores any other cell).
            val canStart = cell in dots || gameState.cellOwner[cell] != null
            val drawing = activeColor != null
            Box(
                Modifier
                    .absoluteOffset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .size(cellDp)
                    .semantics {
                        contentDescription = description
                        if (isLevelWon) return@semantics
                        if (drawing) stateDescription = drawingLabel
                        val actions = buildList {
                            if (drawing) {
                                add(CustomAccessibilityAction(extendLabel) { onExtendPath(cell); true })
                                add(CustomAccessibilityAction(finishLabel) { onCommitDraw(); true })
                            }
                            if (canStart) {
                                add(CustomAccessibilityAction(startLabel) { onStartDraw(cell); true })
                            }
                        }
                        if (actions.isNotEmpty()) customActions = actions
                        // Double-tap does the primary thing for the current phase:
                        // start a stroke, or grow the open one into this cell.
                        when {
                            drawing -> onClick(extendLabel) { onExtendPath(cell); true }
                            canStart -> onClick(startLabel) { onStartDraw(cell); true }
                        }
                    }
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
