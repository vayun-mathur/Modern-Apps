package com.vayunmathur.games.pipes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas

enum class Direction { UP, DOWN, LEFT, RIGHT }

/** Six pipe exits for pointy-top hex cells (screen coords, y down): E=0°, SE=60°, SW=120°, W=180°, NW=240°, NE=300°. */
enum class HexDirection { E, SE, SW, W, NW, NE }

fun hexDirectionBetween(fromX: Float, fromY: Float, toX: Float, toY: Float): HexDirection {
    val angle = Math.toDegrees(kotlin.math.atan2((toY - fromY).toDouble(), (toX - fromX).toDouble()))
    val sector = ((kotlin.math.floor((angle + 30) / 60).toInt() % 6) + 6) % 6
    return HexDirection.entries[sector]
}

private fun hexCorner(hexCenter: Offset, radius: Float, index: Int): Offset {
    // Pointy-top vertices at 270° (top) + k*60°.
    val angle = Math.toRadians((270 + index * 60).toDouble())
    return Offset(
        (hexCenter.x + radius * kotlin.math.cos(angle)).toFloat(),
        (hexCenter.y + radius * kotlin.math.sin(angle)).toFloat()
    )
}

fun DrawScope.drawHexCell(hexCenter: Offset, radius: Float) {
    val path = androidx.compose.ui.graphics.Path().apply {
        for (i in 0 until 6) {
            val p = hexCorner(hexCenter, radius, i)
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(path, color = Color(0xFF2D2D2D))
    drawPath(path, color = Color(0xFF6B6B6B), style = Stroke(width = 1.5f))
}

fun DrawScope.drawHexPipeSegment(
    hexCenter: Offset,
    apothem: Float,
    connections: Set<HexDirection>,
    color: Color
) {
    if (connections.isEmpty()) return
    val w = apothem * 0.9f
    for (dir in connections) {
        val angle = Math.toRadians((dir.ordinal * 60).toDouble())
        val edge = Offset(
            (hexCenter.x + apothem * kotlin.math.cos(angle)).toFloat(),
            (hexCenter.y + apothem * kotlin.math.sin(angle)).toFloat()
        )
        drawLine(color, hexCenter, edge, strokeWidth = w, cap = StrokeCap.Round)
    }
    drawCircle(color = color, radius = w / 2, center = hexCenter)
}

fun DrawScope.drawEmptyCell(cellRect: Rect) {
    drawRect(
        color = Color(0xFF2D2D2D),
        topLeft = cellRect.topLeft,
        size = cellRect.size
    )
    drawRect(
        color = Color(0xFF6B6B6B),
        topLeft = cellRect.topLeft,
        size = cellRect.size,
        style = Stroke(width = 1.5f)
    )
}

fun DrawScope.drawPipeSegment(
    cellRect: Rect,
    connections: Set<Direction>,
    color: Color
) {
    if (connections.isEmpty()) return
    val w = cellRect.width * 0.5f

    when (connections.size) {
        1 -> {
            drawArm(cellRect, connections.first(), color, w)
            drawCircle(color = color, radius = w / 2, center = cellRect.center)
        }
        2 -> {
            val dirs = connections.toList()
            if (areOpposite(dirs[0], dirs[1])) {
                drawStraightPipe(cellRect, dirs[0], color, w)
            } else {
                drawCornerPipe(cellRect, dirs[0], dirs[1], color, w)
            }
        }
        else -> {
            for (dir in connections) drawArm(cellRect, dir, color, w)
            val hw = w / 2
            drawRect(color, Offset(cellRect.center.x - hw, cellRect.center.y - hw), Size(w, w))
        }
    }
}

fun DrawScope.drawEndpointBall(cellRect: Rect, color: Color) {
    drawCircle(color = color, radius = cellRect.width * 0.35f, center = cellRect.center)
}

/** Overpass hump: white-outline ellipse so the crossing pipe reads as going over the under-pipe. */
fun DrawScope.drawBridgeHump(cellRect: Rect, color: Color) {
    val w = cellRect.width * 0.52f
    val h = cellRect.height * 0.34f
    drawOval(
        color = Color.White,
        topLeft = Offset(cellRect.center.x - w / 2 - 1.5f, cellRect.center.y - h / 2 - 1.5f),
        size = Size(w + 3f, h + 3f)
    )
    drawOval(
        color = color,
        topLeft = Offset(cellRect.center.x - w / 2, cellRect.center.y - h / 2),
        size = Size(w, h)
    )
}

/** Portal ring: magenta double-ring marker drawn under any pipe. */
fun DrawScope.drawPortalRing(cellRect: Rect) {
    val r = cellRect.width * 0.44f
    drawCircle(color = Color(0xFFAB47BC), radius = r, center = cellRect.center, style = Stroke(width = cellRect.width * 0.09f))
    drawCircle(color = Color.White, radius = r * 0.62f, center = cellRect.center, style = Stroke(width = cellRect.width * 0.05f))
}

/** Wrap-edge chevrons on a border cell whose pipe exits the board. */
fun DrawScope.drawWrapMarker(cellRect: Rect, dir: Direction, color: Color) {
    val cx = cellRect.center.x
    val cy = cellRect.center.y
    val s = cellRect.width * 0.16f
    val (ex, ey) = when (dir) {
        Direction.LEFT -> cellRect.left + s to cy
        Direction.RIGHT -> cellRect.right - s to cy
        Direction.UP -> cx to cellRect.top + s
        Direction.DOWN -> cx to cellRect.bottom - s
    }
    drawCircle(color = color, radius = s * 0.9f, center = Offset(ex, ey))
}

/**
 * The text paint behind [drawColorLabel], kept by the caller across frames. Allocating a
 * Paint per label per frame churned the GC on every drag step in colour-blind mode.
 */
class ColorLabelPaint {
    val paint: android.graphics.Paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
}

/**
 * Colorblind aid: draw the flow's identifying letter, centred on [center] (the cell centre
 * by default) at [textScale] of the cell size.
 */
fun DrawScope.drawColorLabel(
    cellRect: Rect,
    colorIndex: Int,
    labelPaint: ColorLabelPaint = ColorLabelPaint(),
    center: Offset = cellRect.center,
    textScale: Float = 0.5f,
) {
    val letter = ('A' + (colorIndex % 26)).toString()
    val onColor = PIPE_COLORS[colorIndex % PIPE_COLORS.size]
    // Pick black/white text for contrast against the flow color.
    val luminance = 0.299f * onColor.red + 0.587f * onColor.green + 0.114f * onColor.blue
    val textColor = if (luminance > 0.6f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    val paint = labelPaint.paint
    paint.color = textColor
    paint.textSize = cellRect.width * textScale
    val cx = center.x
    val cy = center.y - (paint.descent() + paint.ascent()) / 2f
    drawContext.canvas.nativeCanvas.drawText(letter, cx, cy, paint)
}

/**
 * Where a letter sits on a square pipe segment so that it lands in the middle of the
 * pipe: the cell centre for straight runs and ends, and the middle of the arc for a
 * corner, whose quarter circle (radius half a cell, centred on the cell corner) only
 * grazes the cell centre with its outer edge.
 */
fun pipeLabelAnchor(cellRect: Rect, connections: Set<Direction>): Offset {
    if (connections.size != 2) return cellRect.center
    val dirs = connections.toList()
    if (areOpposite(dirs[0], dirs[1])) return cellRect.center
    val inset = cellRect.width * 0.5f / kotlin.math.sqrt(2f)
    val x = if (Direction.RIGHT in connections) cellRect.right - inset else cellRect.left + inset
    val y = if (Direction.DOWN in connections) cellRect.bottom - inset else cellRect.top + inset
    return Offset(x, y)
}

private fun areOpposite(a: Direction, b: Direction): Boolean =
    (a == Direction.UP && b == Direction.DOWN) || (a == Direction.DOWN && b == Direction.UP) ||
    (a == Direction.LEFT && b == Direction.RIGHT) || (a == Direction.RIGHT && b == Direction.LEFT)

private fun DrawScope.drawStraightPipe(cellRect: Rect, dir: Direction, color: Color, pipeWidth: Float) {
    val cx = cellRect.center.x
    val cy = cellRect.center.y
    val hw = pipeWidth / 2
    if (dir == Direction.UP || dir == Direction.DOWN) {
        drawRect(color, Offset(cx - hw, cellRect.top), Size(pipeWidth, cellRect.height))
    } else {
        drawRect(color, Offset(cellRect.left, cy - hw), Size(cellRect.width, pipeWidth))
    }
}

private fun DrawScope.drawCornerPipe(cellRect: Rect, dir1: Direction, dir2: Direction, color: Color, pipeWidth: Float) {
    val s = cellRect.width
    val r = s / 2

    val hasUp = dir1 == Direction.UP || dir2 == Direction.UP
    val hasDown = dir1 == Direction.DOWN || dir2 == Direction.DOWN
    val hasLeft = dir1 == Direction.LEFT || dir2 == Direction.LEFT
    val hasRight = dir1 == Direction.RIGHT || dir2 == Direction.RIGHT

    val (arcCx, arcCy, startAngle) = when {
        hasUp && hasRight -> Triple(cellRect.right, cellRect.top, 90f)
        hasUp && hasLeft -> Triple(cellRect.left, cellRect.top, 0f)
        hasDown && hasRight -> Triple(cellRect.right, cellRect.bottom, 180f)
        hasDown && hasLeft -> Triple(cellRect.left, cellRect.bottom, 270f)
        else -> return
    }

    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = Offset(arcCx - r, arcCy - r),
        size = Size(2 * r, 2 * r),
        style = Stroke(width = pipeWidth, cap = StrokeCap.Butt)
    )
}

private fun DrawScope.drawArm(cellRect: Rect, dir: Direction, color: Color, pipeWidth: Float) {
    val cx = cellRect.center.x
    val cy = cellRect.center.y
    val hw = pipeWidth / 2
    when (dir) {
        Direction.UP -> drawRect(color, Offset(cx - hw, cellRect.top), Size(pipeWidth, cy - cellRect.top))
        Direction.DOWN -> drawRect(color, Offset(cx - hw, cy), Size(pipeWidth, cellRect.bottom - cy))
        Direction.LEFT -> drawRect(color, Offset(cellRect.left, cy - hw), Size(cx - cellRect.left, pipeWidth))
        Direction.RIGHT -> drawRect(color, Offset(cx, cy - hw), Size(cellRect.right - cx, pipeWidth))
    }
}
