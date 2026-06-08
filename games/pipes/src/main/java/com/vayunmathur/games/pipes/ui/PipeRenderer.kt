package com.vayunmathur.games.pipes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

enum class Direction { UP, DOWN, LEFT, RIGHT }

fun DrawScope.drawEmptyCell(cellRect: Rect) {
    drawRect(
        color = Color(0xFF2D2D2D),
        topLeft = cellRect.topLeft,
        size = cellRect.size
    )
    drawRect(
        color = Color(0xFF3A3A3A),
        topLeft = cellRect.topLeft,
        size = cellRect.size,
        style = Stroke(width = 1f)
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
