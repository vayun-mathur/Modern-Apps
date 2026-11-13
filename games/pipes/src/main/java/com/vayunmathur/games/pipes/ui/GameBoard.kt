package com.vayunmathur.games.pipes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.platform.PipesGameState

/** Test tag of the drawing surface, for gesture tests. */
internal const val GAME_BOARD_TAG = "pipes_game_board"

@Composable
fun GameBoard(
    levelData: LevelData,
    gameState: PipesGameState,
    activeColor: Int?,
    activePath: List<CellPos>,
    onStartDraw: (CellPos) -> Unit,
    onExtendPath: (CellPos) -> Unit,
    onCommitDraw: () -> Unit,
    isLevelWon: Boolean,
    modifier: Modifier = Modifier,
    colorblind: Boolean = false
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
    val boardSize = minOf(maxWidth, maxHeight)
    // Layout and pixel geometry are cached: the board recomposes on every drag step, and
    // rescanning every cell for bounds and rects each time is wasted work.
    val layout = remember(levelData) { BoardLayout(levelData) }
    val cellSizeDp = boardSize / layout.maxDim
    val cellSizePx = with(LocalDensity.current) { cellSizeDp.toPx() }
    val geometry = remember(layout, cellSizePx) { BoardGeometry(layout, cellSizePx) }
    val cellRects = geometry.cellRects
    val isHex = geometry.isHex
    val labelPaint = remember { ColorLabelPaint() }
    // The gesture coroutine outlives recompositions, so it must never read values captured
    // at launch: it restarts whenever the geometry changes (rotation, fold, resize), and
    // reads the callbacks through rememberUpdatedState.
    val currentStartDraw by rememberUpdatedState(onStartDraw)
    val currentExtendPath by rememberUpdatedState(onExtendPath)
    val currentCommitDraw by rememberUpdatedState(onCommitDraw)

    Canvas(
        modifier = Modifier
            .size(boardSize)
            .testTag(GAME_BOARD_TAG)
            .pointerInput(geometry, isLevelWon) {
                if (isLevelWon) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pos = event.changes.firstOrNull()?.position ?: continue

                        when {
                            event.changes.any { it.pressed && !it.previousPressed } -> {
                                val cell = geometry.hitTest(pos)
                                if (cell != null) currentStartDraw(cell)
                            }
                            event.changes.any { it.pressed } -> {
                                val cell = geometry.hitTestDrag(pos)
                                if (cell != null) currentExtendPath(cell)
                            }
                            event.changes.any { !it.pressed && it.previousPressed } -> {
                                currentCommitDraw()
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        for (cell in levelData.cells) {
            val rect = cellRects[cell] ?: continue
            if (isHex) {
                drawHexCell(rect.center, cellSizePx * 0.52f)
            } else {
                drawEmptyCell(rect)
            }
        }

        // Portal pads sit under any pipe.
        for (portalCell in levelData.portals.keys) {
            val rect = cellRects[portalCell] ?: continue
            drawPortalRing(rect)
        }

        val displayPaths = if (activeColor != null) gameState.paths + (activeColor to activePath) else gameState.paths

        val jumpExits = mutableListOf<Triple<CellPos, Direction, androidx.compose.ui.graphics.Color>>()

        for ((colorIndex, path) in displayPaths) {
            if (path.isEmpty()) continue
            val color = PIPE_COLORS[colorIndex % PIPE_COLORS.size]

            for (i in path.indices) {
                val cell = path[i]
                val rect = cellRects[cell] ?: continue
                if (isHex) {
                    val connections = buildSet {
                        if (i > 0) hexDirOrNull(cellRects, cell, path[i - 1])?.let { add(it) }
                        if (i < path.lastIndex) hexDirOrNull(cellRects, cell, path[i + 1])?.let { add(it) }
                    }
                    drawHexPipeSegment(rect.center, cellSizePx * 0.5f, connections, color)
                } else {
                    val connections = buildSet {
                        if (i > 0) stepOrJump(levelData, cell, path[i - 1], color, jumpExits)?.let { add(it) }
                        if (i < path.lastIndex) stepOrJump(levelData, cell, path[i + 1], color, jumpExits)?.let { add(it) }
                    }
                    drawPipeSegment(rect, connections, color)
                }
            }
        }

        // Wrap jumps: dot at the board edge the pipe exits through. Portal jumps get no
        // marker — the ring above already explains the teleport.
        for ((cell, side, color) in jumpExits) {
            if (cell in levelData.portals) continue
            val rect = cellRects[cell] ?: continue
            drawWrapMarker(rect, side, color)
        }

        // Bridges: the second crosser humps over the first owner's pipe.
        for (bridge in levelData.bridges) {
            val rect = cellRects[bridge] ?: continue
            val owner = gameState.cellOwner[bridge]
            val over = displayPaths.entries.firstOrNull { (c, path) ->
                bridge in path && (owner == null || c != owner)
            }?.key
            if (over != null) {
                drawBridgeHump(rect, PIPE_COLORS[over % PIPE_COLORS.size])
            }
        }

        for (ep in levelData.endpoints) {
            val color = PIPE_COLORS[ep.colorIndex % PIPE_COLORS.size]
            for (cell in ep.cells) {
                val rect = cellRects[cell] ?: continue
                drawEndpointBall(rect, color)
                if (colorblind) drawColorLabel(rect, ep.colorIndex, labelPaint)
            }
        }
    }
    }
}

private fun directionBetween(from: CellPos, to: CellPos): Direction? {
    return when {
        to.row < from.row -> Direction.UP
        to.row > from.row -> Direction.DOWN
        to.col < from.col -> Direction.LEFT
        to.col > from.col -> Direction.RIGHT
        else -> null
    }
}

/**
 * Adjacent step → its side. Long-range edge (wrap/portal) → side facing the board edge, plus a
 * marker queued by the caller. The pipe segment itself draws as if it exits that side.
 */
private fun stepOrJump(
    levelData: LevelData,
    cell: CellPos,
    neighbor: CellPos,
    color: androidx.compose.ui.graphics.Color,
    jumpExits: MutableList<Triple<CellPos, Direction, androidx.compose.ui.graphics.Color>>,
): Direction? {
    directionBetween(cell, neighbor)?.let {
        if (kotlin.math.abs(neighbor.row - cell.row) + kotlin.math.abs(neighbor.col - cell.col) == 1) return it
    }
    // Long-range: pick the side toward the nearest board edge from the cell's draw position.
    val pos = levelData.renderPositions?.get(cell) ?: com.vayunmathur.games.pipes.data.RenderPos(cell.col.toFloat(), cell.row.toFloat())
    val edgeDistances = listOf(
        Direction.LEFT to pos.x,
        Direction.RIGHT to (levelData.cols - 1 - pos.x),
        Direction.UP to pos.y,
        Direction.DOWN to (levelData.rows - 1 - pos.y)
    )
    val side = edgeDistances.minByOrNull { it.second }?.first ?: return null
    jumpExits.add(Triple(cell, side, color))
    return side
}

private fun hexDirOrNull(
    cellRects: Map<CellPos, Rect>,
    cell: CellPos,
    neighbor: CellPos,
): HexDirection? {
    val a = cellRects[cell]?.center ?: return null
    val b = cellRects[neighbor]?.center ?: return null
    if (a == b) return null
    return hexDirectionBetween(a.x, a.y, b.x, b.y)
}
