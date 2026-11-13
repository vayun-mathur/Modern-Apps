package com.vayunmathur.games.unblockjam.platform

import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.LevelData

/** What sliding a block one cell does: moves it, wins the level, or nothing. */
sealed interface StepResult {
    /** The block moved; [level] is the board after the move, with the block as the last moved. */
    data class Moved(val level: LevelData) : StepResult

    /** The target block slid out through the exit. */
    data object Won : StepResult

    /** Off its axis, against a wall, another block or the board edge, or a fixed block. */
    data object Blocked : StepResult
}

/** Whether [block] slides left/right (true) or up/down (false). Squares count as vertical, as dragging does. */
fun Block.isHorizontal(): Boolean = dimension.width > dimension.height

/**
 * Slides block [index] of [levelData] one cell by ([dx], [dy]) - the step a screen-reader action
 * or an arrow key makes, under the same rules as a drag: only along the block's own axis, never
 * through another block or off the board, and the target block wins once it slides into the exit.
 */
fun stepBlock(levelData: LevelData, index: Int, dx: Int, dy: Int): StepResult {
    val block = levelData.blocks.getOrNull(index) ?: return StepResult.Blocked
    val alongAxis = if (block.isHorizontal()) dy == 0 && dx != 0 else dx == 0 && dy != 0
    if (block.fixed || !alongAxis) return StepResult.Blocked

    val moved = block.copy(position = Coord(block.position.x + dx, block.position.y + dy))
    val isTarget = index == 0
    val intoExit = isTarget && dx > 0 && block.position.y == levelData.exit.y &&
        block.position.x + block.dimension.width >= levelData.exit.x
    if (intoExit) return StepResult.Won

    if (!isMoveValid(moved, levelData.blocks - block, levelData.dimension)) return StepResult.Blocked
    val blocks = levelData.blocks.toMutableList().also { it[index] = moved }
    return StepResult.Moved(levelData.copy(blocks = blocks, lastMovedBlockIndex = index))
}
