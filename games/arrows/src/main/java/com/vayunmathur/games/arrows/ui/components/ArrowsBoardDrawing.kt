// RAW ANIMATION EXCEPTION: an arrow travelling its route is imperative motion - one Animatable
// per tap, run through a sequence of legs (travel, blocked-hold, retreat) whose easing and length
// depend on how far the arrow actually got, which no declarative target value can express.
package com.vayunmathur.games.arrows.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vayunmathur.games.arrows.data.ArrowPiece
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.Mirror
import com.vayunmathur.games.arrows.platform.ArrowMove

/**
 * Faint dots at cell centres.
 *
 * A full grid of lines would compete with the arrows, which are themselves thin strokes. Dots give
 * enough of a lattice to judge alignment without adding a second set of lines to read past. Skipped
 * wherever something is drawn, so a dot never shows through a stroke.
 */
internal fun DrawScope.drawDots(game: ArrowsGameState, cell: Float, color: Color) {
    val radius = cell * 0.035f
    val occupied = game.occupancy
    for (row in 0 until game.puzzle.rows) {
        for (col in 0 until game.puzzle.cols) {
            val index = row * game.puzzle.cols + col
            if (index in occupied || index in game.puzzle.mirrors) continue
            drawCircle(
                color = color,
                radius = radius,
                center = Offset((col + 0.5f) * cell, (row + 0.5f) * cell),
            )
        }
    }
}

/** The tile's diagonal, drawn as the mirror it is named for. */
internal fun DrawScope.drawMirror(cell: Int, mirror: Mirror, cols: Int, size: Float, color: Color) {
    val row = cell / cols
    val col = cell % cols
    val inset = size * 0.22f
    val left = col * size + inset
    val right = (col + 1) * size - inset
    val top = row * size + inset
    val bottom = (row + 1) * size - inset
    val (start, end) = when (mirror) {
        Mirror.FORWARD -> Offset(left, bottom) to Offset(right, top)
        Mirror.BACK -> Offset(left, top) to Offset(right, bottom)
    }
    drawLine(color, start, end, strokeWidth = size * 0.09f, cap = StrokeCap.Round)
}

/**
 * How far each trailing segment waits for the one ahead of it, in cells of travel.
 *
 * This is the whole of the snake feel: the head sets off immediately and every segment behind it
 * starts [SEGMENT_LAG_CELLS] of travel later, so the arrow eases out instead of translating as
 * one rigid shape. A fifth of a cell per segment is enough to read as a follow-through on a
 * five-cell arrow without the tail feeling disconnected on a two-cell one.
 */
internal const val SEGMENT_LAG_CELLS = 0.22f

/**
 * Segment [segmentIndex]'s own advance when the move's global advance is [globalAdvance].
 *
 * Pure arithmetic on purpose, so the timing can be pinned by plain unit tests: the head
 * (the last segment) tracks the global advance exactly, each segment behind it is held back
 * by one more [lagPerSegment], and no segment ever passes [maxAdvance] or goes negative.
 * Animating the global value from 0 to [globalAdvanceTarget] therefore starts the head first
 * and still lands every segment exactly on [maxAdvance].
 */
internal fun laggedAdvance(
    globalAdvance: Float,
    segmentIndex: Int,
    segmentCount: Int,
    maxAdvance: Float,
    lagPerSegment: Float = SEGMENT_LAG_CELLS,
): Float {
    val behindHead = (segmentCount - 1 - segmentIndex).coerceAtLeast(0)
    return (globalAdvance - behindHead * lagPerSegment).coerceIn(0f, maxAdvance)
}

/**
 * The global advance at which even the tail has completed [maxAdvance] of travel: the far end
 * has to run one lag per segment past it. Durations should be sized from this value so the
 * arrow's speed per cell is the same whatever its length.
 */
internal fun globalAdvanceTarget(
    maxAdvance: Float,
    segmentCount: Int,
    lagPerSegment: Float = SEGMENT_LAG_CELLS,
): Float = maxAdvance + (segmentCount - 1).coerceAtLeast(0) * lagPerSegment

/**
 * One arrow part-way along its route.
 *
 * Cell `i` of the piece sits at route position `i + advance`, so the body follows wherever the head went
 * and turns corners with it. Fractional advance interpolates between the two positions either side, which
 * is what makes the motion continuous rather than a hop per cell.
 *
 * [advance] is the move's *global* advance (see [laggedAdvance]): each segment converts it to
 * its own, so the head leads and the tail follows a beat later rather than the whole shape
 * translating rigidly. [maxAdvance] is how far each segment ultimately travels.
 *
 * Once the route runs out the piece is leaving the board, so positions past the end are extrapolated from
 * the final step's direction — it slides off the edge instead of stopping dead on it.
 */
internal fun DrawScope.drawTravellingArrow(
    piece: ArrowPiece,
    move: ArrowMove,
    advance: Float,
    maxAdvance: Float,
    cols: Int,
    cell: Float,
    color: Color,
) {
    val segmentAdvance = { index: Int ->
        laggedAdvance(advance, index, piece.cells.size, maxAdvance)
    }
    val centres = piece.cells.indices.map { routePoint(move.route, it + segmentAdvance(it), cols, cell) }
    // Sampled half a cell further along the route rather than taken from the last two body cells: a
    // one-cell arrow has no "last two", and sampling also keeps the head turning smoothly through a
    // mirror instead of snapping once the body reaches it.
    val headAt = piece.cells.lastIndex + segmentAdvance(piece.cells.lastIndex)
    val ahead = routePoint(move.route, headAt + 0.5f, cols, cell)
    val head = centres.last()
    val sampled = Offset(ahead.x - head.x, ahead.y - head.y)
    // A mirror ring leaves an arrow with no escape path at all, so there is nothing ahead to sample and
    // the arrowhead would collapse to a dot. Fall back to the direction it is painted pointing.
    val heading = if (kotlin.math.hypot(sampled.x, sampled.y) > 0.01f) sampled
    else Offset(piece.direction.dCol.toFloat(), piece.direction.dRow.toFloat())
    drawArrowShape(centres, heading, cell, color)
}

/**
 * Where route position [at] falls in pixels, interpolating between whole cells.
 *
 * Beyond the last cell it keeps going in the direction of the final step, so an exiting arrow carries on
 * off the board rather than piling up on the edge.
 */
internal fun routePoint(route: List<Int>, at: Float, cols: Int, cell: Float): Offset {
    fun centre(index: Int) = Offset((index % cols + 0.5f) * cell, (index / cols + 0.5f) * cell)

    val last = route.size - 1
    if (at <= 0f) return centre(route[0])
    if (at >= last) {
        val end = centre(route[last])
        val previous = centre(route[(last - 1).coerceAtLeast(0)])
        val overshoot = at - last
        return Offset(
            end.x + (end.x - previous.x) * overshoot,
            end.y + (end.y - previous.y) * overshoot,
        )
    }
    val low = at.toInt()
    val t = at - low
    val from = centre(route[low])
    val to = centre(route[low + 1])
    return Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
}

/**
 * One arrow: its body as a single joined stroke, and an arrowhead at the head.
 *
 * The stroke runs through cell centres, and a turn in the polyline is drawn as a rounded arc
 * (see [arrowBodyPath]) rather than a sharp corner. The tip reaches past the head's centre by
 * [HEAD_REACH] to leave room for the barbs while staying inside its own cell rather than poking
 * into the next one.
 */
internal fun DrawScope.drawArrow(piece: ArrowPiece, cols: Int, cell: Float, color: Color) {
    val centres = piece.cells.map { Offset((it % cols + 0.5f) * cell, (it / cols + 0.5f) * cell) }
    val heading = Offset(piece.direction.dCol.toFloat(), piece.direction.dRow.toFloat())
    drawArrowShape(centres, heading, cell, color)
}

/**
 * The body path through [points] (cell centres, ending at the arrow tip), with every turn
 * replaced by a rounded quarter-circle arc.
 *
 * The arc radius follows the grid: half a cell, the largest quarter circle that fits inside one
 * cell, so a turn reads as the arrow bending around the corner cell instead of pivoting on its
 * centre. It is clamped to half the shorter neighbouring segment so two arcs on adjacent corners
 * of a short hop can never overlap and swallow the straight between them; a straight (collinear)
 * point keeps a plain line, and a degenerate zero-length neighbour is skipped.
 *
 * Quadratic curves stand in for the circular arc: with the control point on the corner itself the
 * curve leaves one straight tangentially and joins the next the same way, which at stroke width is
 * indistinguishable from the true arc while needing no angle bookkeeping per turn.
 */
internal fun arrowBodyPath(points: List<Offset>, cell: Float): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    if (points.size == 1) return path

    val maxRadius = cell * 0.5f
    for (index in 1 until points.lastIndex) {
        val previous = points[index - 1]
        val corner = points[index]
        val next = points[index + 1]
        val inLength = kotlin.math.hypot(corner.x - previous.x, corner.y - previous.y)
        val outLength = kotlin.math.hypot(next.x - corner.x, next.y - corner.y)
        val radius = minOf(maxRadius, inLength / 2f, outLength / 2f)
        // Collinear (or degenerate) points have no turn to round: cross product ~ 0.
        val cross = (corner.x - previous.x) * (next.y - corner.y) -
            (corner.y - previous.y) * (next.x - corner.x)
        if (radius <= 0f || inLength <= 0f || outLength <= 0f ||
            kotlin.math.abs(cross) <= 0.001f * inLength * outLength
        ) {
            path.lineTo(corner.x, corner.y)
            continue
        }
        val inUnit = Offset((corner.x - previous.x) / inLength, (corner.y - previous.y) / inLength)
        val outUnit = Offset((next.x - corner.x) / outLength, (next.y - corner.y) / outLength)
        val arcStart = Offset(corner.x - inUnit.x * radius, corner.y - inUnit.y * radius)
        val arcEnd = Offset(corner.x + outUnit.x * radius, corner.y + outUnit.y * radius)
        path.lineTo(arcStart.x, arcStart.y)
        path.quadraticBezierTo(corner.x, corner.y, arcEnd.x, arcEnd.y)
    }
    path.lineTo(points.last().x, points.last().y)
    return path
}

/**
 * Draws the body through [centres] and an arrowhead pointing along [heading].
 *
 * [heading] need not be normalised - it is only used for its direction - which lets the animated path
 * supply the difference between its last two points and get a head that turns with the route.
 */
internal fun DrawScope.drawArrowShape(
    centres: List<Offset>,
    heading: Offset,
    cell: Float,
    color: Color,
) {
    val stroke = cell * 0.11f
    val length = kotlin.math.hypot(heading.x, heading.y).takeIf { it > 0.0001f } ?: 1f
    val unit = Offset(heading.x / length, heading.y / length)

    val head = centres.last()
    val tip = Offset(head.x + unit.x * cell * 0.34f, head.y + unit.y * cell * 0.34f)

    drawPath(
        path = arrowBodyPath(centres + tip, cell),
        color = color,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // Two barbs angled back from the tip. The perpendicular of a 2D vector is its components swapped
    // with one negated, so no trigonometry is needed even once the heading is diagonal mid-turn.
    val barb = cell * 0.26f
    for (side in intArrayOf(1, -1)) {
        drawLine(
            color = color,
            start = tip,
            end = Offset(
                tip.x + (-unit.x + -unit.y * side) * barb,
                tip.y + (-unit.y + unit.x * side) * barb,
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * The route a blocked arrow tried to take, drawn as a trail of dots behind the arrows.
 *
 * Dots rather than a line, so it reads as an attempt rather than another arrow, and stops on the cell
 * that turned it back — which is the cell the player needs to look at.
 */
internal fun DrawScope.drawRoute(route: List<Int>, cols: Int, cell: Float, color: Color) {
    val faded = color.copy(alpha = 0.55f)
    for (index in route) {
        drawCircle(
            color = faded,
            radius = cell * 0.09f,
            center = Offset((index % cols + 0.5f) * cell, (index / cols + 0.5f) * cell),
        )
    }
}
