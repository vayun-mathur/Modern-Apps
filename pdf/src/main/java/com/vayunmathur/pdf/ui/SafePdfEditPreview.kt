package com.vayunmathur.pdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vayunmathur.pdf.platform.SafeAnnotation

/** In-progress preview Canvas for [EditOverlay]: selection outline plus drag/polydraft/ink previews. */
@Composable
internal fun SafePdfEditPreview(
    modifier: Modifier = Modifier,
    annotations: List<SafeAnnotation>,
    selected: Long?,
    tool: EditTool,
    shape: ShapeKind,
    markup: MarkupKind,
    color: Color,
    ch: Float,
    scale: Float,
    dragStart: Offset?,
    dragCurrent: Offset?,
    moveDelta: Offset,
    inkPoints: List<Offset>,
    polyDraft: PolyDraft?,
) {
    Canvas(modifier) {
        // Selection highlight.
        val sel = annotations.firstOrNull { it.id == selected }
        if (sel != null) {
            val left = sel.x0 * scale + moveDelta.x
            val top = ch - sel.y1 * scale + moveDelta.y
            drawRect(
                color = Color(0xFF2196F3),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size((sel.x1 - sel.x0) * scale, (sel.y1 - sel.y0) * scale),
                style = Stroke(width = 3f),
            )
        }
        // In-progress shapes.
        val s = dragStart
        val e = dragCurrent
        if (s != null && e != null && tool == EditTool.HIGHLIGHT) {
            drawRect(
                color = color.copy(alpha = 0.35f),
                topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)),
                style = Fill,
            )
        }
        if (s != null && e != null && tool == EditTool.MARKUP) {
            val left = minOf(s.x, e.x); val right = maxOf(s.x, e.x)
            val top = minOf(s.y, e.y); val bottom = maxOf(s.y, e.y)
            when (markup) {
                MarkupKind.HIGHLIGHT -> drawRect(
                    color = color.copy(alpha = 0.35f),
                    topLeft = Offset(left, top), size = Size(right - left, bottom - top), style = Fill,
                )
                MarkupKind.STRIKEOUT -> drawLine(color, Offset(left, (top + bottom) / 2f), Offset(right, (top + bottom) / 2f), strokeWidth = 2f)
                else -> drawLine(color, Offset(left, bottom - 2f), Offset(right, bottom - 2f), strokeWidth = 2f)
            }
        }
        if (s != null && e != null && tool == EditTool.CALLOUT) {
            drawLine(color, s, e, strokeWidth = 2f)
            drawRect(color = color, topLeft = e, size = Size(120f, 40f), style = Stroke(width = 2f))
        }
        if (s != null && e != null && tool == EditTool.REDACT) {
            drawRect(
                color = Color.Black,
                topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)),
                style = Fill,
            )
        }
        if (s != null && e != null && tool == EditTool.SHAPE) {
            val rect = Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
            val topLeft = Offset(rect.left, rect.top)
            val sz = Size(rect.width, rect.height)
            val style = if (shape.isFill) Fill else Stroke(width = 2f)
            when (shape.geom) {
                ShapeGeom.RECT -> drawRect(color = color, topLeft = topLeft, size = sz, style = style)
                ShapeGeom.OVAL -> drawOval(color = color, topLeft = topLeft, size = sz, style = style)
                ShapeGeom.POLYGON -> {
                    val pts = shape.unitPolygon().map { mapUnit(it, rect) }
                    if (pts.size >= 2) {
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        drawPath(path, color, style = style)
                    }
                }
            }
        }
        if (s != null && e != null && tool == EditTool.LINE) {
            drawLine(color, s, e, strokeWidth = 2f)
        }
        // In-progress polyline / Bézier: draw placed points and connecting path.
        if (polyDraft != null && polyDraft.points.isNotEmpty()) {
            val screenPts = polyDraft.points.map { Offset(it.x * scale, ch - it.y * scale) }
            val path = Path().apply {
                moveTo(screenPts[0].x, screenPts[0].y)
                screenPts.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(width = 2f))
            for (p in screenPts) {
                drawCircle(color = color, radius = 5f, center = p)
            }
        }
        if (tool == EditTool.DRAW && inkPoints.size >= 2) {
            val path = Path().apply {
                moveTo(inkPoints[0].x, inkPoints[0].y)
                inkPoints.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(width = 2f))
        }
    }
}
