package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R
import com.vayunmathur.weather.util.MetricPoint
import kotlin.math.roundToInt

/**
 * Bottom sheet showing a simple line chart of a metric's hourly values over
 * time. Min/max value labels sit on the left edge; start/end time labels run
 * along the bottom. Tapping or dragging on the chart snaps to the nearest
 * hourly sample and shows its value + time. A map button in the header opens
 * the full-screen shaded weather map. Purely presentational — the caller
 * supplies the points and the value/time formatters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricGraphSheet(
    title: String,
    points: List<MetricPoint>,
    valueLabel: (Double) -> String,
    timeLabel: (Long) -> String,
    onOpenMap: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = onOpenMap) {
                    Icon(
                        painter = painterResource(R.drawable.outline_map_24),
                        contentDescription = stringResource(R.string.open_map),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            if (points.size < 2) {
                Text(
                    text = "Not enough data to plot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val values = points.map { it.value }
            val minV = values.min()
            val maxV = values.max()
            val range = (maxV - minV).takeIf { it > 1e-6 } ?: 1.0

            val lineColor = MaterialTheme.colorScheme.primary
            val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
            val markerColor = MaterialTheme.colorScheme.primary

            var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }

            fun indexAt(x: Float, widthPx: Float): Int {
                if (widthPx <= 0f) return 0
                val frac = (x / widthPx).coerceIn(0f, 1f)
                return (frac * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .pointerInput(points) {
                        detectTapGestures { offset ->
                            selectedIndex = indexAt(offset.x, size.width.toFloat())
                        }
                    }
                    .pointerInput(points) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                selectedIndex = indexAt(offset.x, size.width.toFloat())
                            },
                        ) { change, _ ->
                            selectedIndex = indexAt(change.position.x, size.width.toFloat())
                        }
                    },
            ) {
                val chartWidth = maxWidth
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val n = points.size
                    val w = size.width
                    val h = size.height
                    fun px(i: Int): Float = if (n == 1) w / 2f else w * i / (n - 1)
                    fun py(v: Double): Float = (h - ((v - minV) / range).toFloat() * h)

                    drawLine(gridColor, Offset(0f, 0f), Offset(w, 0f), 1.dp.toPx())
                    drawLine(gridColor, Offset(0f, h), Offset(w, h), 1.dp.toPx())

                    val line = Path()
                    val fill = Path()
                    points.forEachIndexed { i, p ->
                        val x = px(i)
                        val y = py(p.value)
                        if (i == 0) {
                            line.moveTo(x, y)
                            fill.moveTo(x, h)
                            fill.lineTo(x, y)
                        } else {
                            line.lineTo(x, y)
                            fill.lineTo(x, y)
                        }
                    }
                    fill.lineTo(px(n - 1), h)
                    fill.close()

                    drawPath(fill, color = fillColor)
                    drawPath(
                        line,
                        color = lineColor,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )

                    selectedIndex?.let { idx ->
                        val x = px(idx)
                        val y = py(points[idx].value)
                        drawLine(
                            markerColor.copy(alpha = 0.5f),
                            Offset(x, 0f),
                            Offset(x, h),
                            1.5.dp.toPx(),
                        )
                        drawCircle(markerColor, radius = 5.dp.toPx(), center = Offset(x, y))
                    }
                }
                Text(
                    text = valueLabel(maxV),
                    modifier = Modifier.align(Alignment.TopStart),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = valueLabel(minV),
                    modifier = Modifier.align(Alignment.BottomStart),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                selectedIndex?.let { idx ->
                    val p = points[idx]
                    val frac = if (points.size == 1) 0.5f else idx.toFloat() / (points.size - 1)
                    val labelWidth = 96.dp
                    val rawX = chartWidth * frac - labelWidth / 2
                    val clampedX = rawX.coerceIn(0.dp, (chartWidth - labelWidth).coerceAtLeast(0.dp))
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = clampedX)
                            .widthIn(min = labelWidth),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 2.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = valueLabel(p.value),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = timeLabel(p.epochSec),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = timeLabel(points.first().epochSec),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = timeLabel(points.last().epochSec),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
