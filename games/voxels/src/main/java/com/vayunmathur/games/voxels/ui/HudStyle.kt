package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

// On-screen controls sit over a 3D world that can be anything from a night cave to a sunlit snow
// field. White-on-transparent vanished over bright scenes, so controls use a dark translucent fill
// with a light outline, and labels carry a dark shadow: legible over both extremes.

/** Fill behind a control. */
internal val HudFill = Color.Black.copy(alpha = 0.38f)
/** Fill behind a toggled-on / dimmed control. */
internal val HudFillDim = Color.Black.copy(alpha = 0.22f)
/** Outline around a control. */
internal val HudOutline = Color.White.copy(alpha = 0.7f)
/** Shadow under control labels. */
internal val HudTextShadow = Shadow(color = Color.Black.copy(alpha = 0.85f), offset = Offset(0f, 2f), blurRadius = 4f)

/** A stick's ring (dark fill + light outline) centred at [center]. */
internal fun DrawScope.drawStickBase(center: Offset, radius: Float) {
    drawCircle(HudFill, radius = radius, center = center)
    drawCircle(HudOutline, radius = radius, center = center, style = Stroke(1.5.dp.toPx()))
}

/** A stick's knob (light fill + dark outline) at [center]; brighter while [active]. */
internal fun DrawScope.drawStickKnob(center: Offset, radius: Float, active: Boolean) {
    drawCircle(Color.White.copy(alpha = if (active) 0.85f else 0.6f), radius = radius, center = center)
    drawCircle(Color.Black.copy(alpha = 0.6f), radius = radius, center = center, style = Stroke(1.5.dp.toPx()))
}
