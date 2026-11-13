package com.vayunmathur.games.unblockjam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.library.ui.MaterialTheme

/**
 * The playable board: the blocks, the frame they slide in, and the exit the target block leaves by.
 *
 * Stateless apart from in-flight drag offsets, which live in each [GameBlock]: every committed move
 * goes out through [onLevelChanged] and comes back as a new [levelData].
 */
@Composable
fun GameBoard(
    levelData: LevelData,
    onLevelChanged: (LevelData) -> Unit,
    onLevelWon: () -> Unit,
    isLevelWon: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
    val boardSize = minOf(maxWidth, maxHeight)
    val cellWidth = boardSize / levelData.dimension.width
    val cellHeight = boardSize / levelData.dimension.height

    // scale values based on the level's dimensions for consistent visuals
    val scaling = boardSize / minOf(levelData.dimension.width, levelData.dimension.height) / 100

    // make sure the exit can cover the whole main block
    val exitWidthMult = 1 + levelData.blocks[0].dimension.width

    Box {
        ExitOverlay(
            boardSize = boardSize,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            exitRow = levelData.exit.y,
            exitWidthMult = exitWidthMult,
        )

        Box(
            Modifier
                .size(boardSize)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(scaling * BOARD_CORNER_SCALE))
        ) {

            levelData.blocks.forEachIndexed { index, block ->
                GameBlock(
                    block = block,
                    index = index,
                    levelData = levelData,
                    isLevelWon = isLevelWon,
                    boardSize = boardSize,
                    cellWidth = cellWidth,
                    cellHeight = cellHeight,
                    scaling = scaling,
                    onLevelChanged = onLevelChanged,
                    onLevelWon = onLevelWon,
                )
            }
        }
    }
    }
}

@Composable
private fun ExitOverlay(
    boardSize: Dp,
    cellWidth: Dp,
    cellHeight: Dp,
    exitRow: Int,
    exitWidthMult: Int,
) {
    Box(
        Modifier
            .size(cellWidth * exitWidthMult + 1.dp, cellHeight)
            .offset(boardSize - 1.dp, cellHeight * exitRow)
            .background(MaterialTheme.colorScheme.primary)
    )
    Box(
        Modifier
            .size(cellWidth * exitWidthMult, cellHeight)
            .offset(boardSize, cellHeight * exitRow)
            .zIndex(1f)
            .background(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        1f / exitWidthMult to MaterialTheme.colorScheme.background
                    )
                )
            )
    )
}

private const val BOARD_CORNER_SCALE = 12
