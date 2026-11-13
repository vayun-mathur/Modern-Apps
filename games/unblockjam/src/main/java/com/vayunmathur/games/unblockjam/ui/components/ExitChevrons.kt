package com.vayunmathur.games.unblockjam.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Two right-pointing chevrons centred in the drawing area, sized from its height.
 *
 * The same mark goes on the exit lane and on the target block, so the block reads as the one that
 * belongs at the exit by shape, not only by colour.
 */
internal fun DrawScope.drawExitChevrons(color: Color) {
    val unit = size.height
    val stroke = unit * 0.09f
    val halfHeight = unit * 0.16f
    val halfWidth = unit * 0.1f
    val gap = unit * 0.14f
    for (centreX in floatArrayOf(center.x - gap, center.x + gap)) {
        val tip = Offset(centreX + halfWidth, center.y)
        drawLine(color, Offset(centreX - halfWidth, center.y - halfHeight), tip, stroke, StrokeCap.Round)
        drawLine(color, Offset(centreX - halfWidth, center.y + halfHeight), tip, stroke, StrokeCap.Round)
    }
}
