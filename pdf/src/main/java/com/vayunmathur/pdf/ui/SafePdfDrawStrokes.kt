package com.vayunmathur.pdf.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vayunmathur.pdf.platform.PdfPrimitive

/** Stroke-path writer for [SafePdfDrawContext]. */
internal fun SafePdfDrawContext.drawStrokePath(prim: PdfPrimitive.StrokePath) {
    if (prim.points.size < 2) return
    val path = reusePath
    path.reset()
    val start = map(prim.points[0])
    path.moveTo(start.x, start.y)
    for (i in 1 until prim.points.size) {
        val p = map(prim.points[i])
        path.lineTo(p.x, p.y)
    }
    // Rust emits width, dash lengths and dash phase all in page space
    // (draw.rs scales each by the CTM), so all three take the same
    // page->canvas factor. The miter limit is a ratio of miter length to
    // line width (PDF 32000-1 8.4.3.5) and must NOT be scaled.
    val pathEffect = if (prim.dash.size >= 2) {
        androidx.compose.ui.graphics.PathEffect.dashPathEffect(
            FloatArray(prim.dash.size) { prim.dash[it] * scale },
            prim.dashPhase * scale,
        )
    } else {
        null
    }
    val cap = when (prim.cap) {
        1 -> androidx.compose.ui.graphics.StrokeCap.Round
        2 -> androidx.compose.ui.graphics.StrokeCap.Square
        else -> androidx.compose.ui.graphics.StrokeCap.Butt
    }
    val join = when (prim.join) {
        1 -> androidx.compose.ui.graphics.StrokeJoin.Round
        2 -> androidx.compose.ui.graphics.StrokeJoin.Bevel
        else -> androidx.compose.ui.graphics.StrokeJoin.Miter
    }
    // Miter limit is in line-width units per spec, not device pixels.
    with(scope) {
        drawPath(
            path,
            Color(prim.color),
            style = Stroke(
                width = (prim.width * scale).coerceAtLeast(1f),
                miter = prim.miter,
                cap = cap,
                join = join,
                pathEffect = pathEffect,
            ),
            blendMode = prim.blend.toCompose(),
        )
    }
}
