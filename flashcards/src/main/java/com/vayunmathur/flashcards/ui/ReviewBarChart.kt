package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * A simple bar chart of `(label, value)` pairs. Adapted from `health`'s
 * `GenericBarChart`, trimmed to what the stats screen needs: tap a bar to see its
 * value, decimated X labels, and a max/0 Y axis.
 */
@Composable
fun ReviewBarChart(
    data: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
) {
    val maxValue = (data.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(1.0)
    val labelColor = LocalContentColor.current.copy(alpha = 0.6f)
    var selectedIndex by remember(data) { mutableIntStateOf(-1) }

    val chartHeight = 180.dp
    val xAxisHeight = 22.dp
    val sideLabelWidth = 36.dp
    val tooltipHeight = 20.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight + tooltipHeight),
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val sideLabelWidthPx = with(density) { sideLabelWidth.toPx() }
        val chartWidthPx = fullWidthPx - sideLabelWidthPx
        val count = data.size.coerceAtLeast(1)
        val barWidth = (chartWidthPx / count * 0.6f).coerceIn(2f, with(density) { 16.dp.toPx() })
        val spacing = (chartWidthPx - count * barWidth) / (count + 1)

        if (selectedIndex in data.indices) {
            val pair = data[selectedIndex]
            val barCenterX = spacing + selectedIndex * (barWidth + spacing) + barWidth / 2
            Text(
                text = pair.second.toLong().toString(),
                color = barColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = with(density) { barCenterX.toDp() })
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(-(placeable.width / 2), 0)
                        }
                    },
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .align(Alignment.BottomStart)
                .padding(end = sideLabelWidth, bottom = xAxisHeight)
                .pointerInput(data) {
                    detectTapGestures { offset ->
                        val matched = data.indices.firstOrNull { idx ->
                            val left = spacing + idx * (barWidth + spacing)
                            offset.x >= left - spacing / 2 && offset.x <= left + barWidth + spacing / 2
                        } ?: -1
                        selectedIndex = if (matched == selectedIndex) -1 else matched
                    }
                },
        ) {
            gridLines(labelColor)
            data.forEachIndexed { index, pair ->
                val barHeight = (pair.second.toFloat() / maxValue.toFloat() * size.height)
                    .coerceIn(if (pair.second > 0) 3f else 0f, size.height)
                val x = spacing + index * (barWidth + spacing)
                val y = size.height - barHeight
                val drawColor = if (selectedIndex < 0 || selectedIndex == index) {
                    barColor
                } else {
                    barColor.copy(alpha = 0.5f)
                }
                drawRoundRect(
                    color = drawColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
            }
        }

        XAxisLabels(
            labels = data.map { it.first },
            color = labelColor,
            density = density,
        ) { index -> spacing + index * (barWidth + spacing) + barWidth / 2 }

        val actualChartHeightPx = with(density) { (chartHeight - xAxisHeight).toPx() }
        val topOffsetPx = with(density) { tooltipHeight.toPx() }
        YAxisLabel(maxValue.toLong().toString(), labelColor, sideLabelWidth, topOffsetPx, density)
        YAxisLabel("0", labelColor, sideLabelWidth, topOffsetPx + actualChartHeightPx, density)
    }
}

private fun decimationStep(count: Int): Int = when {
    count <= 8 -> 1
    count <= 16 -> 2
    count <= 24 -> 3
    else -> count / 6
}

private fun DrawScope.gridLines(color: Color) {
    listOf(0.25f, 0.5f, 0.75f, 1f).forEach { frac ->
        drawLine(
            color = color.copy(alpha = 0.10f),
            start = Offset(0f, size.height * (1f - frac)),
            end = Offset(size.width, size.height * (1f - frac)),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

@Composable
private fun BoxScope.XAxisLabels(
    labels: List<String>,
    color: Color,
    density: Density,
    xForIndex: (Int) -> Float,
) {
    val step = decimationStep(labels.size).coerceAtLeast(1)
    labels.forEachIndexed { index, label ->
        if (label.isNotEmpty() && index % step == 0) {
            Text(
                text = label,
                color = color,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = with(density) { xForIndex(index).toDp() })
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(-(placeable.width / 2), 0)
                        }
                    },
            )
        }
    }
}

@Composable
private fun BoxScope.YAxisLabel(
    text: String,
    color: Color,
    sideLabelWidth: Dp,
    yPx: Float,
    density: Density,
) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .width(sideLabelWidth)
            .offset(y = with(density) { yPx.toDp() })
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(0, -(placeable.height / 2))
                }
            }
            .padding(start = 4.dp),
    )
}
