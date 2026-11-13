// RAW ANIMATION EXCEPTION: an arrow travelling its route is imperative motion - one Animatable
// per tap (several may be in flight at once), run through a sequence of legs (travel, blocked-hold, retreat) whose easing and length
// depend on how far the arrow actually got, which no declarative target value can express.
package com.vayunmathur.games.arrows.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.platform.testTag
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
import com.vayunmathur.library.ui.Motion
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
    moves: List<ArrowMove>,
    onTapArrow: (Int) -> Unit,
    onMoveFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val arrowColor = scheme.onSurface
    val blockedColor = scheme.error
    val mirrorColor = scheme.onTertiaryContainer
    val mirrorTileColor = scheme.tertiaryContainer
    val dotColor = scheme.outlineVariant
    val cols = game.puzzle.cols

    // One flight per move still animating, each on its own clock: the moves were applied when
    // tapped, so a second arrow can set off while the first is still in the air. Keyed by move id
    // so adding or retiring one never restarts another.
    val finished by rememberUpdatedState(onMoveFinished)
    val flights = moves.map { move ->
        key(move.id) {
            val segmentCount =
                game.puzzle.pieces.firstOrNull { it.id == move.pieceId }?.cells?.size ?: 1
            val flight = remember { Flight(move, segmentCount) }
            LaunchedEffect(Unit) {
                // `finally`, so a flight cut short is settled too. The move was applied at tap
                // time, so all that is left is to stop drawing it in the air; without this, a
                // board that left composition mid-flight (Settings opened, Game Center pushed)
                // kept the move pending and replayed the whole flight from the start on return.
                try {
                    flight.fly()
                } finally {
                    finished(move.id)
                }
            }
            flight
        }
    }
    val flightFor = flights.associateBy { it.move.pieceId }

    // The route the last blocked arrow tried to take, up to and including what stopped it. Derived rather
    // than stored, so it cannot go stale against the board. Held back while that arrow is still on its
    // way to the obstruction, so the trail appears when it hits rather than the moment it is tapped.
    val blockedStillTravelling = flightFor[game.blockedId]?.let { !it.showBlocked } == true
    val blockedRoute = if (!showRoutes || game.blockedId < 0 || blockedStillTravelling) emptyList() else {
        game.puzzle.pieces.firstOrNull { it.id == game.blockedId }?.let { piece ->
            val travel = ArrowsRules.travel(game, piece)
            // route is body + path, so drop the body and keep one past where it stopped: the cell that
            // stopped it is the one the player needs to look at.
            travel.route.drop(piece.length).take(travel.advance + 1)
        }.orEmpty()
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Fit both ways when the height is bounded, so a tall board on a landscape phone shrinks
        // its cells instead of overflowing into a scroll. Unbounded (inside a scrolling parent)
        // only the width can decide.
        val byWidth = maxWidth / cols
        val cell = if (constraints.hasBoundedHeight) minOf(byWidth, maxHeight / game.puzzle.rows) else byWidth

        Box(
            Modifier
                .size(cell * cols, cell * game.puzzle.rows)
                .testTag(BOARD_TEST_TAG)
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
                        drawMirror(index, mirror, cols, cellPx, mirrorColor, mirrorTileColor)
                    }
                    if (blockedRoute.isNotEmpty()) {
                        drawRoute(blockedRoute, cols, cellPx, blockedColor)
                    }
                    for (piece in game.remaining) {
                        if (piece.id in flightFor) continue
                        val color = if (piece.id == game.blockedId) blockedColor else arrowColor
                        drawArrow(piece, cols, cellPx, color)
                    }
                    // In-flight arrows last, over the settled ones they pass. A clearing arrow is
                    // already gone from `game.remaining`, so it is drawn from the puzzle.
                    for (flight in flights) {
                        val piece = game.puzzle.pieces.firstOrNull { it.id == flight.move.pieceId }
                            ?: continue
                        val color = if (flight.showBlocked) blockedColor else arrowColor
                        drawTravellingArrow(
                            piece, flight.move, flight.advance.value, flight.maxAdvance, cols, cellPx, color,
                        )
                    }
                },
            // Absolute top-left, like `absoluteOffset` below: a plain Box aligns children to its
            // top *start*, which under RTL is the right edge, and that alone mirrors every box.
            contentAlignment = AbsoluteAlignment.TopLeft,
        ) {
            // Semantics only: invisible, one per arrow, sitting on its head cell. Taps anywhere along an
            // arrow are handled by the board's own pointerInput; these exist so a screen reader has
            // something to find and activate.
            //
            // Placed in absolute coordinates (`absoluteOffset` from an absolute top-left), not
            // start-relative ones: the canvas above paints in absolute coordinates and the
            // pointerInput reads absolute touch positions, so these boxes must not mirror under an
            // RTL locale. A mirrored box sat over the wrong cell and, being clickable, swallowed a tap
            // meant for one arrow and launched whichever arrow's head it had been laid out for.
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
                            .absoluteOffset(x = cell * (piece.head % cols), y = cell * (piece.head / cols))
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
                            .absoluteOffset(x = cell * (index % cols), y = cell * (index / cols))
                            .size(cell)
                            .clearAndSetSemantics { contentDescription = redirector }
                    )
                }
            }
        }
    }
}

/**
 * One tap being animated: how far along its route the arrow is, in cells, and whether it has
 * hit something yet.
 */
private class Flight(val move: ArrowMove, private val segmentCount: Int) {
    val advance = Animatable(0f)

    /** Turns red only once it has actually run into something, not from the first frame. */
    var showBlocked by mutableStateOf(false)
        private set

    /** How far each segment ultimately travels. */
    val maxAdvance: Float = when {
        // One past the end of the route, so the tail clears the edge too rather than winking out
        // on it. The route already ends with the head's escape path, so its size alone is the
        // distance that takes the tail out.
        move.clears -> move.route.size.toFloat()
        // Wedged arrows still get a nudge, or a tap on one reads as the game ignoring the input.
        move.advance == 0 -> NUDGE_CELLS
        else -> move.advance.toFloat()
    }

    /**
     * Segments do not share this advance: each converts it with laggedAdvance(), so the head
     * sets off first and the tail follows. The global value therefore has to run past each
     * segment's own destination by one lag per trailing segment (globalAdvanceTarget()).
     */
    suspend fun fly() {
        val target = globalAdvanceTarget(maxAdvance, segmentCount)
        if (move.clears) {
            advance.animateTo(target, Motion.over(cellMillis(target)))
        } else {
            advance.animateTo(target, Motion.open(cellMillis(target)))
            showBlocked = true
            delay(BLOCKED_HOLD_MILLIS)
            advance.animateTo(0f, Motion.close(cellMillis(target)))
        }
    }
}

/** Tags the painted board itself, as opposed to the space it is centred in. */
internal const val BOARD_TEST_TAG = "arrows_board"

/** How far past the head cell's centre the tip reaches, as a fraction of a cell. */
private const val HEAD_REACH = 0.34f

/** How long the arrow takes per cell of travel. Fast enough not to be a wait, slow enough to follow. */
private const val MILLIS_PER_CELL = 45

/** Floor on the animation, so a one-cell move is still long enough to register. */
private const val MIN_MOVE_MILLIS = 120

private fun cellMillis(cells: Float): Int =
    (cells * MILLIS_PER_CELL).toInt().coerceAtLeast(MIN_MOVE_MILLIS)

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
