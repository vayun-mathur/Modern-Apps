package com.vayunmathur.games.unblockjam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.platform.blockDragGestures
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.animatedDp

/** One block on the board, draggable along its long axis unless it is a fixed wall. */
@Composable
internal fun GameBlock(
    block: Block,
    index: Int,
    levelData: LevelData,
    isLevelWon: Boolean,
    boardSize: Dp,
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

    var offsetX by remember(block, levelData) { mutableStateOf(cellWidth * block.position.x) }
    var offsetY by remember(block, levelData) { mutableStateOf(cellHeight * block.position.y) }

    val targetOffsetX = if (isMainBlock && isLevelWon) boardSize + cellWidth else offsetX
    val currentOffsetX = animatedDp(
        targetOffsetX,
        Motion.over(if (isMainBlock && isLevelWon) WIN_ANIM_MILLIS else 0),
    )

    var modifier = Modifier
        .size(blockWidth, blockHeight)
        .offset { IntOffset(currentOffsetX.roundToPx(), offsetY.roundToPx()) }
        .padding(scaling * BLOCK_INSET_SCALE)
        .background(color, shape = RoundedCornerShape(percent = BLOCK_CORNER_PERCENT))

    if (!block.fixed) {
        modifier = modifier.blockDragGestures(
            block = block,
            levelData = levelData,
            isLevelWon = isLevelWon,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            isMainBlock = isMainBlock,
            onLevelWon = onLevelWon,
            onLevelChanged = onLevelChanged,
            index = index,
            offsetXProvider = { offsetX },
            offsetYProvider = { offsetY },
            offsetXUpdater = { offsetX = it },
            offsetYUpdater = { offsetY = it }
        )
    }

    Box(modifier = modifier)
}

private const val WIN_ANIM_MILLIS = 600
private const val BLOCK_INSET_SCALE = 4
private const val BLOCK_CORNER_PERCENT = 10
