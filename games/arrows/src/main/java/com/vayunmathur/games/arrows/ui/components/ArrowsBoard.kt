// RAW ANIMATION EXCEPTION: an arrow travelling its route is imperative motion - one Animatable
// per tap, run through a sequence of legs (travel, blocked-hold, retreat) whose easing and length
// depend on how far the arrow actually got, which no declarative target value can express.
package com.vayunmathur.games.arrows.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.vayunmathur.games.arrows.R
import com.vayunmathur.games.arrows.data.ArrowPiece
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.Direction
import com.vayunmathur.games.arrows.data.Mirror
import com.vayunmathur.games.arrows.domain.ArrowsRules
import com.vayunmathur.games.arrows.platform.ArrowMove
import com.vayunmathur.library.ui.MaterialTheme
import kotlinx.coroutines.delay

/**
 * The board.
 *
 * Painted in one [drawBehind] pass rather than as a composable per cell: an arrow spans several cells
 * and has to be a single continuous stroke with one arrowhead, which per-cell composables cannot
 * express. Taps anywhere along an arrow are resolved by arithmetic on the touch position, which is
 * both simpler and more accurate than hit-testing a stack of overlapping boxes.
 *
 * Because a canvas carries no semantics, one transparent box per arrow is laid over its head cell to
 * give screen readers something to find and activate.
 */
@Composable
fun ArrowsBoard(
    game: ArrowsGameState,
    showRoutes: Boolean,
    move: ArrowMove?,
    onTapArrow: (Int) -> Unit,
    onMoveFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val arrowColor = scheme.onSurface
    val blockedColor = scheme.error
    val mirrorColor = scheme.tertiary
    val dotColor = scheme.outlineVariant
    val cols = game.puzzle.cols

    // How far along its route the moving arrow currently is, in cells. Driven by one animation per tap;
    // `0` whenever nothing is moving, so a settled board draws from `game` alone.
    val advance = remember(move) { Animatable(0f) }
    // Turns red only once it has actually run into something, not from the first frame.
    var showBlocked by remember(move) { mutableStateOf(false) }

    LaunchedEffect(move) {
        if (move == null) return@LaunchedEffect
        if (move.clears) {
            // One past the end of the route, so the tail clears the edge too rather than winking
            // out on it. The route already ends with the head's escape path, so its size alone is
            // the distance that takes the tail out; adding `move.advance` counted that path twice
            // and kept the (by then invisible) arrow animating - and taps locked - for up to a
            // whole extra board crossing after it had left.
            val target = move.route.size.toFloat()
            advance.animateTo(target, tween(cellMillis(move.route.size), easing = LinearEasing))
        } else {
            // Wedged arrows still get a nudge, or a tap on one reads as the game ignoring the input.
            val target = if (move.advance == 0) NUDGE_CELLS else move.advance.toFloat()
            advance.animateTo(target, tween(cellMillis(move.advance), easing = LinearOutSlowInEasing))
            showBlocked = true
            delay(BLOCKED_HOLD_MILLIS)
            advance.animateTo(0f, tween(cellMillis(move.advance), easing = FastOutSlowInEasing))
        }
        onMoveFinished()
    }

    // The route the last blocked arrow tried to take, up to and including what stopped it. Derived rather
    // than stored, so it cannot go stale against the board.
    val blockedRoute = if (!showRoutes || game.blockedId < 0) emptyList() else {
        game.puzzle.pieces.firstOrNull { it.id == game.blockedId }?.let { piece ->
            val travel = ArrowsRules.travel(game, piece)
            // route is body + path, so drop the body and keep one past where it stopped: the cell that
            // stopped it is the one the player needs to look at.
            travel.route.drop(piece.length).take(travel.advance + 1)
        }.orEmpty()
    }

    BoxWithConstraints(modifier) {
        val cell = maxWidth / cols

        Box(
            Modifier
                .size(cell * cols, cell * game.puzzle.rows)
                .pointerInput(game.puzzle, game.removed, game.isOver) {
                    detectTapGestures { offset ->
                        val cellPx = size.width.toFloat() / cols
                        val col = (offset.x / cellPx).toInt()
                        val row = (offset.y / cellPx).toInt()
                        if (!game.puzzle.contains(row, col)) return@detectTapGestures
                        game.pieceAt(row * cols + col)?.let { onTapArrow(it.id) }
                    }
                }
                .drawBehind {
                    val cellPx = size.width / cols
                    drawDots(game, cellPx, dotColor)
                    for ((index, mirror) in game.puzzle.mirrors) {
                        drawMirror(index, mirror, cols, cellPx, mirrorColor)
                    }
                    if (blockedRoute.isNotEmpty()) {
                        drawRoute(blockedRoute, cols, cellPx, blockedColor)
                    }
                    for (piece in game.remaining) {
                        val moving = piece.id == move?.pieceId
                        val color = when {
                            moving && showBlocked -> blockedColor
                            piece.id == game.blockedId -> blockedColor
                            else -> arrowColor
                        }
                        if (moving && move != null) {
                            drawTravellingArrow(piece, move, advance.value, cols, cellPx, color)
                        } else {
                            drawArrow(piece, cols, cellPx, color)
                        }
                    }
                }
        ) {
            // Semantics only: invisible, one per arrow, sitting on its head cell. Taps anywhere along an
            // arrow are handled by the board's own pointerInput; these exist so a screen reader has
            // something to find and activate.
            //
            // `key` matters: without it, clearing an arrow shifts every later Box's identity onto a
            // different arrow, and any interaction state they hold - a half-finished ripple - replays on
            // an unrelated head. `indication = null` then makes sure there is no ripple to leak, since
            // the board draws its own feedback and a stray square flashing over a cell reads as a bug.
            for (piece in game.remaining) {
                key(piece.id) {
                    val description = stringResource(
                        R.string.cd_arrow,
                        stringResource(piece.direction.spokenNameRes),
                        piece.head / cols + 1,
                        piece.head % cols + 1,
                    )
                    Box(
                        Modifier
                            .offset(x = cell * (piece.head % cols), y = cell * (piece.head / cols))
                            .size(cell)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onTapArrow(piece.id) }
                            .clearAndSetSemantics { contentDescription = description }
                    )
                }
            }
            // Redirectors are not interactive, but a screen reader still has to know they are there.
            val redirector = stringResource(R.string.cd_redirector)
            for (index in game.puzzle.mirrors.keys) {
                key(index) {
                    Box(
                        Modifier
                            .offset(x = cell * (index % cols), y = cell * (index / cols))
                            .size(cell)
                            .clearAndSetSemantics { contentDescription = redirector }
                    )
                }
            }
        }
    }
}

/** How far past the head cell's centre the tip reaches, as a fraction of a cell. */
private const val HEAD_REACH = 0.34f

/** How long the arrow takes per cell of travel. Fast enough not to be a wait, slow enough to follow. */
private const val MILLIS_PER_CELL = 45

/** Floor on the animation, so a one-cell move is still long enough to register. */
private const val MIN_MOVE_MILLIS = 120

private fun cellMillis(cells: Int): Int =
    (cells * MILLIS_PER_CELL).coerceAtLeast(MIN_MOVE_MILLIS)

/** How far a wedged arrow lurches before coming back, in cells. */
private const val NUDGE_CELLS = 0.3f

/** How long the arrow stays red at the far end before returning. */
private const val BLOCKED_HOLD_MILLIS = 180L

/** Spoken name for a direction, for the board's accessibility description. */
val Direction.spokenNameRes: Int
    get() = when (this) {
        Direction.UP -> R.string.cd_up
        Direction.DOWN -> R.string.cd_down
        Direction.LEFT -> R.string.cd_left
        Direction.RIGHT -> R.string.cd_right
    }
