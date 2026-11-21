package com.vayunmathur.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.library.ui.animatedFloat
import kotlinx.coroutines.delay

/** How long the ring stays at full opacity before it starts fading. */
private const val FOCUS_INDICATOR_HOLD_MS = 900L

/** Corner-bracket length as a fraction of the ring radius. */
private const val BRACKET_FRACTION = 0.45f

/**
 * The tap-to-focus ring: drawn at [point] (preview-local coordinates) whenever
 * [tapId] changes, contracting from a wide bracket to its resting size and
 * then fading out. [locked] tints it amber to match the AE/AF-lock pill.
 *
 * The composable only draws; when it appears and disappears is the caller's
 * in-screen state. Motion goes through the library helpers (`animatedDp` /
 * `animatedFloat` on [Motion] specs), never raw animation APIs.
 *
 * Deliberately NOT rotated with the device: the ring + corner brackets are
 * symmetric under 90° rotations, and [point] is in preview coordinates — the
 * ring must stay on the tapped spot, not swing around it.
 */
@Composable
internal fun FocusIndicator(
    point: Offset,
    tapId: Int,
    locked: Boolean,
    modifier: Modifier = Modifier,
) {
    var contracted by remember(tapId) { mutableStateOf(false) }
    var fading by remember(tapId) { mutableStateOf(false) }
    LaunchedEffect(tapId) {
        contracted = true
        delay(FOCUS_INDICATOR_HOLD_MS)
        fading = true
    }
    val radius = animatedDp(
        target = if (contracted) 28.dp else 44.dp,
        spec = Motion.over(250),
    )
    val alpha = animatedFloat(
        target = if (fading) 0f else 1f,
        spec = Motion.over(300),
    )
    if (alpha <= 0f) return
    val color = if (locked) Color(0xFFFFD54F) else Color.White
    Canvas(modifier = modifier) {
        val r = radius.toPx()
        val stroke = 2.dp.toPx()
        val bracket = r * BRACKET_FRACTION
        val ringColor = color.copy(alpha = alpha)
        // Ring plus four corner brackets, stock-camera style.
        drawCircle(ringColor, radius = r, center = point, style = Stroke(stroke))
        val corners = listOf(
            Offset(point.x - r, point.y - r) to Offset(1f, 1f),
            Offset(point.x + r, point.y - r) to Offset(-1f, 1f),
            Offset(point.x - r, point.y + r) to Offset(1f, -1f),
            Offset(point.x + r, point.y + r) to Offset(-1f, -1f),
        )
        for ((corner, dir) in corners) {
            drawLine(ringColor, corner, Offset(corner.x + dir.x * bracket, corner.y), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(ringColor, corner, Offset(corner.x, corner.y + dir.y * bracket), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        drawCircle(ringColor, radius = 2.dp.toPx(), center = point)
    }
}
