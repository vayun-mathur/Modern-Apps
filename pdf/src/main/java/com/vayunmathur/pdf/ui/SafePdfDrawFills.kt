package com.vayunmathur.pdf.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import com.vayunmathur.pdf.platform.PdfPrimitive

/** Fill-path writer for [SafePdfDrawContext]: all contours in one path so holes cut out. */
internal fun SafePdfDrawContext.drawFillPath(prim: PdfPrimitive.FillPath) {
    val path = reusePath
    path.reset()
    var any = false
    for (contour in prim.contours) {
        if (contour.size < 2) continue
        val first = map(contour[0])
        path.moveTo(first.x, first.y)
        for (i in 1 until contour.size) {
            val p = map(contour[i])
            path.lineTo(p.x, p.y)
        }
        path.close()
        any = true
    }
    if (any) {
        // All contours in one path so interior contours (holes/glyph
        // counters) are cut out by the winding rule.
        path.fillType = if (prim.evenOdd) {
            androidx.compose.ui.graphics.PathFillType.EvenOdd
        } else {
            androidx.compose.ui.graphics.PathFillType.NonZero
        }
        with(scope) {
            drawPath(path, Color(prim.color), style = Fill, blendMode = prim.blend.toCompose())
        }
    }
}
