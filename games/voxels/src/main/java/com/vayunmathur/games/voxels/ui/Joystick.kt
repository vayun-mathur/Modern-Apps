package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

@Composable
fun Joystick(
    modifier: Modifier,
    onMove: (x: Float, y: Float) -> Unit,
    onLookDelta: (deltaYaw: Float, deltaPitch: Float) -> Unit,
    isLook: Boolean = false
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var center by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }

    Box(modifier = modifier.size(130.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            center = Offset(size.width / 2f, size.height / 2f)
                            dragging = true
                            val raw = offset - center
                            val dist = kotlin.math.hypot(raw.x, raw.y)
                            val maxDist = 60.dp.toPx()
                            val clamped = if (dist > maxDist && dist > 0.001f) raw * (maxDist / dist) else raw
                            knob = clamped
                            emit(clamped, maxDist, isLook, onMove, onLookDelta)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val maxDistPx = 60.dp.toPx()
                            val newKnob = knob + Offset(dragAmount.x, dragAmount.y)
                            val dist = kotlin.math.hypot(newKnob.x, newKnob.y)
                            val clamped = if (dist > maxDistPx && dist > 0.001f) {
                                newKnob * (maxDistPx / dist)
                            } else newKnob
                            knob = clamped
                            emit(clamped, maxDistPx, isLook, onMove, onLookDelta)
                        },
                        onDragEnd = {
                            knob = Offset.Zero
                            dragging = false
                            if (isLook) onLookDelta(0f, 0f) else onMove(0f, 0f)
                        },
                        onDragCancel = {
                            knob = Offset.Zero
                            dragging = false
                            if (isLook) onLookDelta(0f, 0f) else onMove(0f, 0f)
                        }
                    )
                }
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawStickBase(c, 63.dp.toPx())
            drawStickKnob(c + knob, 27.dp.toPx(), active = dragging)
        }
    }
}

// Reports the stick's normalized displacement. For move this is a direction; for look it is a
// rotation rate applied every frame, so holding the stick at an offset rotates continuously.
private fun emit(
    clamped: Offset,
    maxDist: Float,
    isLook: Boolean,
    onMove: (Float, Float) -> Unit,
    onLookDelta: (Float, Float) -> Unit
) {
    val nx = clamped.x / maxDist
    val ny = clamped.y / maxDist
    val mag = sqrt(nx * nx + ny * ny)
    if (isLook) {
        // Signs preserve the prior look feel (stick right / up map the same way); -ny so up looks up.
        // A cubic response curve gives fine control near center and fast turns near the edge, so both
        // precise and large movements are possible.
        if (mag < 0.1f) onLookDelta(0f, 0f) else onLookDelta(lookCurve(-nx), lookCurve(ny))
    } else {
        if (mag < 0.15f) onMove(0f, 0f) else onMove(nx, -ny)
    }
}

internal fun lookCurve(v: Float): Float {
    val a = kotlin.math.abs(v)
    val shaped = 0.2f * a + 0.8f * a * a * a
    return if (v < 0f) -shaped else shaped
}
