package com.vayunmathur.games.unblockjam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.unblockjam.R
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.platform.BlockPosition
import com.vayunmathur.games.unblockjam.platform.StepResult
import com.vayunmathur.games.unblockjam.platform.isHorizontal
import com.vayunmathur.games.unblockjam.platform.stepBlock
import com.vayunmathur.games.unblockjam.platform.blockDragGestures
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.travelledOffset
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
    val markColor = MaterialTheme.colorScheme.onTertiary
    val focusColor = MaterialTheme.colorScheme.onBackground
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val blockWidth = cellWidth * block.dimension.width
    val blockHeight = cellHeight * block.dimension.height

    // Everything in cells, not dp: a rotation or resize changes how big a cell is drawn, not
    // which cell the block is in. Keyed on the level, not the block, so the state survives the
    // block's own moves and only resets for a different level.
    val committed = Offset(block.position.x.toFloat(), block.position.y.toFloat())
    var drag by remember(levelData.id) { mutableStateOf<Offset?>(null) }
    var trip by remember(levelData.id) { mutableStateOf(SettleTrip(0, committed)) }

    // Where the block rests: its cell, or past the exit once the target block has won. When this
    // moves - a committed drag, an undo, a restart, the win - the block glides there from wherever
    // it is rather than jumping, on a short settle (or the longer win slide-out).
    val won = isMainBlock && isLevelWon
    // Two cells past the board: through the one-cell exit lane and under the fade beyond it.
    val rest = if (won) Offset(levelData.dimension.width + 2f, committed.y) else committed
    val settled = travelledOffset(
        key = levelData.id to trip.id,
        from = trip.from,
        to = rest,
        spec = if (won) Motion.over(WIN_ANIM_MILLIS) else Motion.reorder(),
    )
    // The drag detector keeps the object it started with, so it reads the settle through this
    // rather than capturing the State of whichever trip was current back then.
    val latestSettled = rememberUpdatedState(settled)
    val position = remember(levelData.id) {
        object : BlockPosition {
            override val x get() = (drag ?: latestSettled.value.value).x
            override val y get() = (drag ?: latestSettled.value.value).y
            override fun dragTo(x: Float, y: Float) { drag = Offset(x, y) }
            override fun release() {
                val from = drag ?: return
                drag = null
                trip = SettleTrip(trip.id + 1, from)
            }
        }
    }

    var modifier = Modifier
        .size(blockWidth, blockHeight)
        // Read here, in layout, so a drag or a settle never recomposes the block.
        .offset {
            val at = drag ?: latestSettled.value.value
            IntOffset((at.x * cellWidth.toPx()).roundToInt(), (at.y * cellHeight.toPx()).roundToInt())
        }
        // Tagged before the inset, so tests read the block's cell rather than its painted inset.
        .testTag("block_$index")
        .blockAccessibility(
            labels = blockLabels(block, isMainBlock),
            horizontal = block.isHorizontal(),
            movable = !block.fixed && !isLevelWon,
            interactionSource = interactionSource,
        ) { dx, dy ->
            val step = stepBlock(levelData, index, dx, dy)
            when (step) {
                is StepResult.Moved -> onLevelChanged(step.level)
                StepResult.Won -> onLevelWon()
                StepResult.Blocked -> Unit
            }
            step != StepResult.Blocked
        }
        .padding(scaling * BLOCK_INSET_SCALE)
        .background(color, shape = RoundedCornerShape(percent = BLOCK_CORNER_PERCENT))
        // Keyboard focus needs to be visible: an outline on the focused block, over its fill.
        .then(
            if (focused) {
                Modifier.border(FOCUS_OUTLINE, focusColor, RoundedCornerShape(percent = BLOCK_CORNER_PERCENT))
            } else {
                Modifier
            }
        )
        // The target block carries the exit's chevrons, so it stands out by shape as well as
        // colour: on several pack palettes it was only red against orange, which colour-blind
        // players cannot tell apart.
        .then(if (isMainBlock) Modifier.drawBehind { drawExitChevrons(markColor) } else Modifier)

    if (!block.fixed) {
        modifier = modifier.blockDragGestures(
            block = block,
            levelData = levelData,
            index = index,
            isLevelWon = isLevelWon,
            cellSize = DpSize(cellWidth, cellHeight),
            position = position,
            onLevelWon = onLevelWon,
            onLevelChanged = onLevelChanged,
        )
    }

    Box(modifier = modifier)
}

/** What TalkBack calls [block] and its moves: kind, size and top-left cell (1-based). */
@Composable
private fun blockLabels(block: Block, isMainBlock: Boolean): BlockLabels {
    val kind = stringResource(
        when {
            isMainBlock -> R.string.cd_target_block
            block.fixed -> R.string.cd_wall
            else -> R.string.cd_block
        }
    )
    return BlockLabels(
        description = stringResource(
            R.string.cd_block_at,
            kind,
            block.dimension.width,
            block.dimension.height,
            block.position.y + 1,
            block.position.x + 1,
        ),
        left = stringResource(R.string.action_move_left),
        right = stringResource(R.string.action_move_right),
        up = stringResource(R.string.action_move_up),
        down = stringResource(R.string.action_move_down),
    )
}

/** One release: a settle from [from], told apart from the last by [id]. */
private data class SettleTrip(val id: Int, val from: Offset)

private const val WIN_ANIM_MILLIS = 600
private const val BLOCK_INSET_SCALE = 4
private const val BLOCK_CORNER_PERCENT = 10
private val FOCUS_OUTLINE = 3.dp
