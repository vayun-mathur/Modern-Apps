package com.vayunmathur.games.unblockjam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.games.unblockjam.R
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
    // The board is a picture of physical space, not text: the exit is on the right and a drag to
    // the right moves a block right, whatever the locale. Under RTL the rtl-aware offsets used for
    // the blocks and the exit mirrored the drawing while drag deltas stayed physical, so blocks
    // slid opposite to the finger. Pinning the board to LTR keeps drawing, hit-testing and drag
    // math in one coordinate system.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
            val columns = levelData.dimension.width
            // One cell's width is kept free to the right of the board for the exit lane. Sizing
            // the board to the full width put the exit past the screen edge in portrait, where
            // the width is the limiting side, so the way out was all but invisible.
            val boardSize = minOf(maxWidth * columns / (columns + 1), maxHeight)
            val cellWidth = boardSize / columns
            val cellHeight = boardSize / levelData.dimension.height

            // scale values based on the level's dimensions for consistent visuals
            val scaling = boardSize / minOf(columns, levelData.dimension.height) / 100

            Box(Modifier.size(boardSize + cellWidth, boardSize)) {
                ExitLane(
                    boardSize = boardSize,
                    cellSize = DpSize(cellWidth, cellHeight),
                    exitRow = levelData.exit.y,
                    // Wide enough to swallow the whole target block as it slides out.
                    fadeCells = 1 + levelData.blocks[0].dimension.width,
                )

                Box(
                    Modifier
                        .size(boardSize)
                        // A horizontal drag that starts near the screen edge would otherwise be
                        // taken by the system back gesture and cancel the move. Android honours
                        // up to 200 dp of exclusion per edge, which covers most of a phone board.
                        .systemGestureExclusion()
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(scaling * BOARD_CORNER_SCALE))
                ) {
                    levelData.blocks.forEachIndexed { index, block ->
                        GameBlock(
                            block = block,
                            index = index,
                            levelData = levelData,
                            isLevelWon = isLevelWon,
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
}

/**
 * The way out: an opening in the board's frame one cell wide in the exit row, marked with
 * chevrons in the target block's colour, and past it a fade into the background that the won
 * target block slides under.
 */
@Composable
private fun ExitLane(boardSize: Dp, cellSize: DpSize, exitRow: Int, fadeCells: Int) {
    val chevronColor = MaterialTheme.colorScheme.tertiary
    val exitLabel = stringResource(R.string.exit)
    Box(
        Modifier
            .absoluteOffset(boardSize - 1.dp, cellSize.height * exitRow)
            .size(cellSize.width + 1.dp, cellSize.height)
            .testTag(EXIT_LANE_TEST_TAG)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topEndPercent = 35, bottomEndPercent = 35))
            .drawBehind { drawExitChevrons(chevronColor) }
            .semantics { contentDescription = exitLabel }
    )
    Box(
        Modifier
            .absoluteOffset(boardSize + cellSize.width, cellSize.height * exitRow)
            .size(cellSize.width * fadeCells, cellSize.height)
            .zIndex(1f)
            .background(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        1f / fadeCells to MaterialTheme.colorScheme.background
                    )
                )
            )
    )
}

/** Tags the exit opening, for tests. */
internal const val EXIT_LANE_TEST_TAG = "exit_lane"

private const val BOARD_CORNER_SCALE = 12
