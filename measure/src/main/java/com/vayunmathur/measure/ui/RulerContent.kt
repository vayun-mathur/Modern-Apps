package com.vayunmathur.measure.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.measure.R
import com.vayunmathur.measure.data.model.UnitSystem
import com.vayunmathur.measure.domain.Units
import com.vayunmathur.measure.platform.RulerUiState

@Composable
fun RulerContent(
    state: RulerUiState,
    onOpenSettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    var markerY by remember { mutableFloatStateOf(0f) }

    AppScaffold(
        title = stringResource(R.string.tool_ruler),
        actions = { IconButton(onClick = onOpenSettings) { IconSettings() } },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val fromTopMm = markerY / state.pixelsPerMm

            Text(
                Units.formatLength(fromTopMm / 1000.0, state.unitSystem),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.ruler_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            RulerScale(
                pixelsPerMm = state.pixelsPerMm,
                unitSystem = state.unitSystem,
                markerY = markerY,
                onMarkerChange = { markerY = it },
                onHeightChange = { h -> if (markerY == 0f) markerY = h / 2f },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/**
 * Vertical scale down both edges with a draggable measuring marker.
 *
 * Both scales run top-down and read identically, so an object can be laid against
 * whichever edge of the phone is convenient. They deliberately do *not* mirror: opposed
 * scales would be rotationally symmetric, so turning the phone around would give the
 * same reading back and the second scale would earn nothing.
 *
 * Tick spacing is driven by the physical pixel density rather than by dp: dp is a
 * rendering convenience, not a unit of length.
 */
@Composable
private fun RulerScale(
    pixelsPerMm: Float,
    unitSystem: UnitSystem,
    markerY: Float,
    onMarkerChange: (Float) -> Unit,
    onHeightChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val variant = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier.pointerInput(Unit) {
            detectDragGestures { change, _ ->
                onMarkerChange(change.position.y.coerceIn(0f, size.height.toFloat()))
            }
        }
    ) {
        onHeightChange(size.height)

        val metric = unitSystem == UnitSystem.Metric
        // Metric: a tick every mm. Imperial: every 1/16 inch.
        val minorStepPx = if (metric) pixelsPerMm else pixelsPerMm * Units.MM_PER_INCH.toFloat() / 16f
        val majorEvery = if (metric) 10 else 16
        val midEvery = if (metric) 5 else 8
        if (minorStepPx <= 0.5f) return@Canvas

        val maxTickLen = size.width * 0.24f

        // `leftEdge` only changes which side the ticks grow from; both scales measure
        // the same distance from the top.
        fun drawEdge(leftEdge: Boolean) {
            var i = 0
            while (true) {
                val y = i * minorStepPx
                if (y > size.height) break
                val isMajor = i % majorEvery == 0
                val isMid = i % midEvery == 0
                val len = when {
                    isMajor -> maxTickLen
                    isMid -> maxTickLen * 0.62f
                    else -> maxTickLen * 0.36f
                }
                val start = if (leftEdge) Offset(0f, y) else Offset(size.width - len, y)
                val end = if (leftEdge) Offset(len, y) else Offset(size.width, y)
                drawLine(
                    color = if (isMajor) onSurface else variant,
                    start = start,
                    end = end,
                    strokeWidth = if (isMajor) 3f else 1.5f,
                )
                if (isMajor && i > 0) {
                    val label = if (metric) "${i / 10}" else "${i / 16}"
                    val layout = measurer.measure(label, TextStyle(fontSize = 13.sp, color = onSurface))
                    val lx = if (leftEdge) len + 8f else size.width - len - 8f - layout.size.width
                    drawText(layout, topLeft = Offset(lx, y - layout.size.height / 2f))
                }
                i++
            }
        }

        drawEdge(leftEdge = true)
        drawEdge(leftEdge = false)

        drawLine(primary, Offset(0f, markerY), Offset(size.width, markerY), 3f)
    }
}
