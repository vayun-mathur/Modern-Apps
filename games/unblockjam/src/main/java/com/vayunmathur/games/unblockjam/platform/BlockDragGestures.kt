package com.vayunmathur.games.unblockjam.platform
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import kotlin.math.roundToInt
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.LevelData

private data class AxisParams(
    val pos: Int,
    val size: Int,
    val boardLimit: Int,
    val perpPos: Int,
    val perpSize: Int,
)

private fun axisParams(block: Block, levelData: LevelData, isHorizontal: Boolean): AxisParams =
    if (isHorizontal) {
        AxisParams(
            block.position.x,
            block.dimension.width,
            levelData.dimension.width,
            block.position.y,
            block.dimension.height,
        )
    } else {
        AxisParams(
            block.position.y,
            block.dimension.height,
            levelData.dimension.height,
            block.position.x,
            block.dimension.width,
        )
    }

private fun isOccupied(otherBlocks: List<Block>, x: Int, y: Int): Boolean = otherBlocks.any {
    x in it.position.x until it.position.x + it.dimension.width &&
        y in it.position.y until it.position.y + it.dimension.height
}

private fun isClearAt(
    otherBlocks: List<Block>,
    isHorizontal: Boolean,
    perpPos: Int,
    perpSize: Int,
    primary: Int,
): Boolean = (perpPos until perpPos + perpSize).all { p ->
    if (isHorizontal) !isOccupied(otherBlocks, primary, p) else !isOccupied(otherBlocks, p, primary)
}

private fun scanLimit(axis: AxisParams, isClear: (Int) -> Boolean, start: Int, dir: Int): Int {
    var edge = start
    while (true) {
        val inBounds = if (dir < 0) edge > 0 else edge + axis.size < axis.boardLimit
        val probe = if (dir < 0) edge - 1 else edge + axis.size
        if (!inBounds || !isClear(probe)) break
        edge += dir
    }
    return edge
}

private fun exitExtension(
    block: Block,
    otherBlocks: List<Block>,
    levelData: LevelData,
    isHorizontal: Boolean,
    isMainBlock: Boolean,
    axis: AxisParams,
    max: Int,
): Int {
    if (!isHorizontal || !isMainBlock) return max
    if (block.position.y != levelData.exit.y) return max
    val pathClear = (max + axis.size until levelData.dimension.width).all { x ->
        !isOccupied(otherBlocks, x, block.position.y)
    }
    return if (pathClear) levelData.exit.x else max
}

private fun findMovementRange(
    block: Block,
    otherBlocks: List<Block>,
    levelData: LevelData,
    isMainBlock: Boolean,
    isHorizontal: Boolean
): IntRange {
    val axis = axisParams(block, levelData, isHorizontal)
    val isClear = { primary: Int ->
        isClearAt(otherBlocks, isHorizontal, axis.perpPos, axis.perpSize, primary)
    }
    val min = scanLimit(axis, isClear, axis.pos, -1)
    val max = exitExtension(
        block,
        otherBlocks,
        levelData,
        isHorizontal,
        isMainBlock,
        axis,
        scanLimit(axis, isClear, axis.pos, 1),
    )
    return min..max
}

private class DragParams(
    val block: Block,
    val levelData: LevelData,
    val isHorizontal: Boolean,
    val isMainBlock: Boolean,
    val onLevelWon: () -> Unit,
    val onLevelChanged: (LevelData) -> Unit,
    val index: Int,
)

/**
 * Where a dragged block is, in cells: `x`/`y` of its top-left corner, fractional mid-drag.
 *
 * Cells rather than dp so the position means the same thing at any board size: a rotation or a
 * window resize changes how big a cell is drawn, not which cell a block is in.
 */
class BlockOffset(
    val x: () -> Float,
    val y: () -> Float,
    val setX: (Float) -> Unit,
    val setY: (Float) -> Unit,
)

private fun settleAfterDrag(params: DragParams, offset: BlockOffset) {
    val (newX, newY) = snappedPosition(params, offset)
    val newBlock = params.block.copy(position = Coord(newX, newY))
    when {
        isWinningMove(params, newX) -> params.onLevelWon()
        isValidRelocation(params, newBlock) -> applyRelocation(params, newBlock)
        else -> resetOffsets(params, offset)
    }
}

private fun snappedPosition(params: DragParams, offset: BlockOffset): Pair<Int, Int> =
    if (params.isHorizontal) {
        offset.x().roundToInt() to params.block.position.y
    } else {
        params.block.position.x to offset.y().roundToInt()
    }

private fun isWinningMove(params: DragParams, newX: Int): Boolean =
    params.isMainBlock &&
        params.block.position.y == params.levelData.exit.y &&
        newX >= params.levelData.exit.x

private fun isValidRelocation(params: DragParams, newBlock: Block): Boolean =
    newBlock.position != params.block.position &&
        isMoveValid(newBlock, params.levelData.blocks - params.block, params.levelData.dimension)

private fun applyRelocation(params: DragParams, newBlock: Block) {
    val newBlocks = params.levelData.blocks.toMutableList()
    newBlocks[params.index] = newBlock
    params.onLevelChanged(params.levelData.copy(blocks = newBlocks, lastMovedBlockIndex = params.index))
}

private fun resetOffsets(params: DragParams, offset: BlockOffset) {
    offset.setX(params.block.position.x.toFloat())
    offset.setY(params.block.position.y.toFloat())
}

/** Moves the block by a drag of ([dx], [dy]) cells along its axis, within [min]..[max]. */
private fun handleDragDelta(params: DragParams, min: Float, max: Float, offset: BlockOffset, dx: Float, dy: Float) {
    if (!params.isHorizontal) {
        offset.setY((offset.y() + dy).coerceIn(min, max))
        return
    }
    val newX = (offset.x() + dx).coerceIn(min, max)
    offset.setX(newX)
    val reachedExit = newX.roundToInt() + params.block.dimension.width - 1 >= params.levelData.exit.x
    if (params.isMainBlock && params.block.position.y == params.levelData.exit.y && reachedExit) {
        params.onLevelWon()
    }
}

/**
 * Drags [block] along its long axis, reading and writing its position through [offset] in cells.
 *
 * [cellWidth] and [cellHeight] only convert finger movement into cells. They are part of the
 * gesture's key, so a resize mid-game restarts the detector with the new cell size rather than
 * keeping the size the board had when it was first laid out, which snapped drags to the wrong cell.
 */
fun Modifier.blockDragGestures(
    block: Block,
    levelData: LevelData,
    index: Int,
    isLevelWon: Boolean,
    cellSize: DpSize,
    offset: BlockOffset,
    onLevelWon: () -> Unit,
    onLevelChanged: (LevelData) -> Unit,
): Modifier {
    val isHorizontal = block.dimension.width > block.dimension.height
    val isMainBlock = index == 0
    val params = DragParams(block, levelData, isHorizontal, isMainBlock, onLevelWon, onLevelChanged, index)
    return pointerInput(block, levelData, isLevelWon, cellSize) {
        if (isLevelWon || block.fixed) return@pointerInput

        var minOffset = 0f
        var maxOffset = 0f
        val cellWidthPx = cellSize.width.toPx()
        val cellHeightPx = cellSize.height.toPx()

        var dragging = false

        // A drag that does not end with the finger lifting - the system taking the pointer for a
        // back gesture, a second finger, the app losing focus, or this detector restarting under
        // it after a resize - goes back to the last committed cell. Before, the block was left
        // floating wherever the finger had got to, half-way between cells.
        try {
            detectDragGestures(
                onDragStart = {
                    dragging = true
                    val range = findMovementRange(block, levelData.blocks - block, levelData, isMainBlock, isHorizontal)
                    minOffset = range.first.toFloat()
                    maxOffset = range.last.toFloat()
                },
                onDragEnd = {
                    dragging = false
                    settleAfterDrag(params, offset)
                },
                onDragCancel = {
                    dragging = false
                    resetOffsets(params, offset)
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    handleDragDelta(
                        params, minOffset, maxOffset, offset,
                        dragAmount.x / cellWidthPx, dragAmount.y / cellHeightPx,
                    )
                }
            )
        } finally {
            if (dragging) resetOffsets(params, offset)
        }
    }
}

fun isMoveValid(movedBlock: Block, otherBlocks: List<Block>, dimension: Dimension): Boolean {
    if (movedBlock.position.x < 0 || movedBlock.position.y < 0) return false
    if (movedBlock.position.x + movedBlock.dimension.width > dimension.width) return false
    if (movedBlock.position.y + movedBlock.dimension.height > dimension.height) return false

    return otherBlocks.none { other ->
        movedBlock.position.x < other.position.x + other.dimension.width &&
        movedBlock.position.x + movedBlock.dimension.width > other.position.x &&
        movedBlock.position.y < other.position.y + other.dimension.height &&
        movedBlock.position.y + movedBlock.dimension.height > other.position.y
    }
}
