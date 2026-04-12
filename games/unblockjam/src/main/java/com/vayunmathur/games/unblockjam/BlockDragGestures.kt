package com.vayunmathur.games.unblockjam

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

fun Modifier.blockDragGestures(
    block: Block,
    levelData: LevelData,
    isLevelWon: Boolean,
    cellWidth: Dp,
    cellHeight: Dp,
    isMainBlock: Boolean,
    onLevelWon: () -> Unit,
    onLevelChanged: (LevelData) -> Unit,
    index: Int,
    offsetXProvider: () -> Dp,
    offsetYProvider: () -> Dp,
    offsetXUpdater: (Dp) -> Unit,
    offsetYUpdater: (Dp) -> Unit
): Modifier {
    return pointerInput(block, levelData, isLevelWon) {
        if (isLevelWon) return@pointerInput
        if (block.fixed) return@pointerInput

        var minOffset = 0.dp
        var maxOffset = 0.dp

        detectDragGestures(
            onDragStart = {
                val otherBlocks = levelData.blocks.minus(block)

                fun isOccupied(x: Int, y: Int): Boolean {
                    return otherBlocks.any {
                        x >= it.position.x && x < it.position.x + it.dimension.width &&
                                y >= it.position.y && y < it.position.y + it.dimension.height
                    }
                }

                if (block.dimension.width > block.dimension.height) { // Horizontal
                    var minX = block.position.x
                    var maxX = block.position.x

                    // Find minX
                    while (minX > 0) {
                        var clear = true
                        for (y in block.position.y until block.position.y + block.dimension.height) {
                            if (isOccupied(minX - 1, y)) {
                                clear = false
                                break
                            }
                        }
                        if (clear) minX-- else break
                    }

                    // Find maxX
                    while (maxX + block.dimension.width < levelData.dimension.width) {
                        var clear = true
                        for (y in block.position.y until block.position.y + block.dimension.height) {
                            if (isOccupied(maxX + block.dimension.width, y)) {
                                clear = false
                                break
                            }
                        }
                        if (clear) maxX++ else break
                    }

                    if (isMainBlock && block.position.y == levelData.exit.y) {
                        var pathToExitIsClear = true
                        for (x in (maxX + block.dimension.width) until levelData.dimension.width) {
                            if (isOccupied(x, block.position.y)) {
                                pathToExitIsClear = false
                                break
                            }
                        }
                        if (pathToExitIsClear) {
                            maxX = levelData.exit.x
                        }
                    }

                    minOffset = cellWidth * minX
                    maxOffset = cellWidth * maxX

                } else { // Vertical
                    var minY = block.position.y
                    var maxY = block.position.y

                    // Find minY
                    while (minY > 0) {
                        var clear = true
                        for (x in block.position.x until block.position.x + block.dimension.width) {
                            if (isOccupied(x, minY - 1)) {
                                clear = false
                                break
                            }
                        }
                        if (clear) minY-- else break
                    }

                    // Find maxY
                    while (maxY + block.dimension.height < levelData.dimension.height) {
                        var clear = true
                        for (x in block.position.x until block.position.x + block.dimension.width) {
                            if (isOccupied(x, maxY + block.dimension.height)) {
                                clear = false
                                break
                            }
                        }
                        if (clear) maxY++ else break
                    }
                    minOffset = cellHeight * minY
                    maxOffset = cellHeight * maxY
                }
            },
            onDragEnd = {
                val newX: Int
                val newY: Int

                if (block.dimension.width > block.dimension.height) { // Horizontal
                    newX = (offsetXProvider() / cellWidth).roundToInt()
                    newY = block.position.y
                } else { // Vertical
                    newX = block.position.x
                    newY = (offsetYProvider() / cellHeight).roundToInt()
                }

                val newBlock = block.copy(position = Coord(newX, newY))

                if (isMainBlock && block.position.y == levelData.exit.y && newX >= levelData.exit.x) {
                    onLevelWon()
                } else if (newBlock.position != block.position && isMoveValid(
                        newBlock,
                        levelData.blocks.minus(block),
                        levelData.dimension
                    )
                ) {
                    val newBlocks = levelData.blocks.toMutableList()
                    newBlocks[index] = newBlock
                    onLevelChanged(levelData.copy(blocks = newBlocks, lastMovedBlockIndex = index))
                } else {
                    offsetXUpdater(cellWidth * block.position.x)
                    offsetYUpdater(cellHeight * block.position.y)
                }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                if (block.dimension.width > block.dimension.height) { // Horizontal
                    val newOffsetX = (offsetXProvider() + dragAmount.x.toDp()).coerceIn(minOffset, maxOffset)
                    offsetXUpdater(newOffsetX)
                    val currentX = (newOffsetX / cellWidth).roundToInt()
                    if (isMainBlock && block.position.y == levelData.exit.y && currentX + block.dimension.width - 1 >= levelData.exit.x) {
                        onLevelWon()
                    }
                } else { // Vertical
                    val newOffsetY = (offsetYProvider() + dragAmount.y.toDp()).coerceIn(minOffset, maxOffset)
                    offsetYUpdater(newOffsetY)
                }
            }
        )
    }
}

fun isMoveValid(movedBlock: Block, otherBlocks: List<Block>, dimension: Dimension): Boolean {
    if (movedBlock.position.x < 0 || movedBlock.position.y < 0) return false
    if (movedBlock.position.x + movedBlock.dimension.width > dimension.width) return false
    if (movedBlock.position.y + movedBlock.dimension.height > dimension.height) return false

    for (other in otherBlocks) {
        if (movedBlock.position.x < other.position.x + other.dimension.width &&
            movedBlock.position.x + movedBlock.dimension.width > other.position.x &&
            movedBlock.position.y < other.position.y + other.dimension.height &&
            movedBlock.position.y + movedBlock.dimension.height > other.position.y
        ) {
            return false
        }
    }
    return true
}
