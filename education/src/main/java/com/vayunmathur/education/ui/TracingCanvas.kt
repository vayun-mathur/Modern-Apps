package com.vayunmathur.education.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import kotlin.math.hypot

/**
 * A finger-tracing canvas: [glyph] is shown large and faint as a guide, and the
 * child's strokes are drawn over it. Once enough has been traced [onTraced] is
 * called once (no-penalty — completion is the success condition).
 */
@Composable
fun TracingCanvas(
    glyph: String,
    enabled: Boolean,
    onTraced: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strokes = remember { mutableStateListOf<Path>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var lastPoint by remember { mutableStateOf(Offset.Zero) }
    var tracedLength by remember { mutableFloatStateOf(0f) }
    var fired by remember { mutableStateOf(false) }
    val inkColor = MaterialTheme.colorScheme.primary

    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            glyph,
            style = TextStyle(fontSize = 160.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            lastPoint = offset
                            currentPath = Path().apply { moveTo(offset.x, offset.y) }
                        },
                        onDrag = { change, _ ->
                            val p = change.position
                            currentPath?.lineTo(p.x, p.y)
                            tracedLength += hypot(p.x - lastPoint.x, p.y - lastPoint.y)
                            lastPoint = p
                            change.consume()
                            if (!fired && tracedLength > TRACE_THRESHOLD_PX) {
                                fired = true
                                onTraced()
                            }
                        },
                        onDragEnd = {
                            currentPath?.let { strokes.add(it) }
                            currentPath = null
                        },
                    )
                },
        ) {
            val stroke = Stroke(width = 22f, cap = StrokeCap.Round)
            strokes.forEach { drawPath(it, inkColor, style = stroke) }
            currentPath?.let { drawPath(it, inkColor, style = stroke) }
        }
    }
}

private const val TRACE_THRESHOLD_PX = 350f
