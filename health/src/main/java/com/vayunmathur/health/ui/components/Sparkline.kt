package com.vayunmathur.health.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import com.vayunmathur.library.ui.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.max

/**
 * Inline bar-style sparkline. Latest bar uses [barColor], the rest use
 * [trackColor] at 0.5 alpha. Auto-scales to the max value in the list.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    if (values.isEmpty()) {
        Spacer(modifier = modifier)
        return
    }
    val maxValue = max(values.max(), 0.0001f)
    val mutedTrack = trackColor.copy(alpha = 0.5f)
    Canvas(modifier = modifier) {
        val n = values.size
        val gap = 2f
        val totalGap = gap * (n - 1)
        val barWidth = max((size.width - totalGap) / n, 1f)
        val cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
        values.forEachIndexed { index, v ->
            val frac = (v / maxValue).coerceIn(0f, 1f)
            val barHeight = max(size.height * frac, 1f)
            val left = index * (barWidth + gap)
            val top = size.height - barHeight
            val color = if (index == n - 1) barColor else mutedTrack
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}
