package com.vayunmathur.games.unblockjam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.platform.BlockOffset
import com.vayunmathur.games.unblockjam.platform.blockDragGestures
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.animatedFloat
import kotlin.math.roundToInt

/** One block on the board, draggable along its long axis unless it is a fixed wall. */
@Composable
internal fun GameBlock(
    block: Block,
    index: Int,
    levelData: LevelData,
    isLevelWon: Boolean,
    cellWidth: Dp,
    cellHeight: Dp,
    scaling: Dp,
    onLevelChanged: (LevelData) -> Unit,
    onLevelWon: () -> Unit,
) {
    val isMainBlock = index == 0
    val color = when {
        isMainBlock -> MaterialTheme.colorScheme.tertiary
        block.fixed -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val blockWidth = cellWidth * block.dimension.width
    val blockHeight = cellHeight * block.dimension.height

    // In cells, not dp: a rotation or resize changes how big a cell is drawn, not which cell the
    // block is in, so nothing here has to be recomputed when the board size changes.
    var offsetX by remember(block, levelData) { mutableFloatStateOf(block.position.x.toFloat()) }
    var offsetY by remember(block, levelData) { mutableFloatStateOf(block.position.y.toFloat()) }

    val targetOffsetX = if (isMainBlock && isLevelWon) levelData.dimension.width + 1f else offsetX
    val currentOffsetX = animatedFloat(
        targetOffsetX,
        Motion.over(if (isMainBlock && isLevelWon) WIN_ANIM_MILLIS else 0),
    )

    var modifier = Modifier
        .size(blockWidth, blockHeight)
        .offset {
            IntOffset(
                (currentOffsetX * cellWidth.toPx()).roundToInt(),
                (offsetY * cellHeight.toPx()).roundToInt(),
            )
        }
        // Tagged before the inset, so tests read the block's cell rather than its painted inset.
        .testTag("block_$index")
        .padding(scaling * BLOCK_INSET_SCALE)
        .background(color, shape = RoundedCornerShape(percent = BLOCK_CORNER_PERCENT))

    if (!block.fixed) {
        modifier = modifier.blockDragGestures(
            block = block,
            levelData = levelData,
            index = index,
            isLevelWon = isLevelWon,
            cellSize = DpSize(cellWidth, cellHeight),
            offset = BlockOffset(
                x = { offsetX },
                y = { offsetY },
                setX = { offsetX = it },
                setY = { offsetY = it },
            ),
            onLevelWon = onLevelWon,
            onLevelChanged = onLevelChanged,
        )
    }

    Box(modifier = modifier)
}

private const val WIN_ANIM_MILLIS = 600
private const val BLOCK_INSET_SCALE = 4
private const val BLOCK_CORNER_PERCENT = 10
