package com.vayunmathur.measure.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.measure.domain.ArFlowState
import com.vayunmathur.measure.domain.Units
import com.vayunmathur.measure.domain.segmentsOf
import com.vayunmathur.measure.platform.ArMeasureUiState

/**
 * Anchor/edge overlay with a continuous centre reticle. The reticle is
 * the placement point: any tap places at the reticle hit, never at the
 * finger's raw ray, so what the user sees centred is exactly what gets
 * measured. Each segment carries its length at its mid-point.
 */
@Composable
fun ArOverlay(
    state: ArMeasureUiState,
    screenPoints: List<Offset?>,
    onCanvasSized: (Size) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val error = MaterialTheme.colorScheme.error
    val ready = state.flowState is ArFlowState.Ready
    val searching = state.flowState is ArFlowState.Searching
    val segmentLabels = segmentsOf(state.anchors).map {
        Units.formatLength(it.lengthM, state.unitSystem)
    }

    Canvas(
        modifier
            .onSizeChanged { onCanvasSized(Size(it.width.toFloat(), it.height.toFloat())) }
            .pointerInput(ready) {
                if (!ready) return@pointerInput
                detectTapGestures { onTap() }
            }
    ) {
        // Reticle at the centre: filled when a sane hit is selected,
        // hollow while searching, hidden until tracking starts.
        if (ready || searching) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val reticleColor = if (ready) primary else error
            drawCircle(reticleColor.copy(alpha = 0.9f), 6f, c)
            drawCircle(reticleColor.copy(alpha = 0.4f), 22f, c, style = Stroke(2f))
        }

        for (i in 0 until screenPoints.size - 1) {
            val a = screenPoints[i]
            val b = screenPoints[i + 1]
            if (a != null && b != null) {
                drawLine(primary, a, b, 4f)
                val label = segmentLabels.getOrNull(i)
                if (label != null) {
                    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        mid.x,
                        mid.y - 12f,
                        android.graphics.Paint().apply {
                            textSize = 36f
                            textAlign = android.graphics.Paint.Align.CENTER
                        },
                    )
                }
            }
        }
        if (state.polygonClosed && screenPoints.size >= 3) {
            val a = screenPoints.last()
            val b = screenPoints.first()
            if (a != null && b != null) drawLine(primary, a, b, 4f)
        }
        for (p in screenPoints) {
            if (p == null) continue
            drawCircle(primary, 12f, p)
            drawCircle(onPrimary, 5f, p)
        }
    }
}
