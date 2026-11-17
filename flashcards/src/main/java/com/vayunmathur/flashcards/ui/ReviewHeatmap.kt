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
 * A GitHub-style activity heatmap: [weeks] columns of 7 day-cells, the last cell
 * being today, with each cell's opacity scaled by its review count over the max.
 * Built from a `byDay` list of [DailyStat].
 */
@Composable
fun ReviewHeatmap(
    days: List<com.vayunmathur.flashcards.ui.DailyStat>,
    modifier: Modifier = Modifier,
    weeks: Int = 26,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val counts = remember(days) { days.associate { it.epochDay to it.count } }
    val maxCount = (days.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    val today = java.time.LocalDate.now().toEpochDay()
    val emptyColor = color.copy(alpha = 0.10f)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val cell = with(density) { (maxWidth.toPx() / weeks) }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { (cell * 7).toDp() }),
        ) {
            val gap = cell * 0.14f
            val total = weeks * 7
            for (i in 0 until total) {
                val epochDay = today - (total - 1 - i)
                val col = i / 7
                val row = i % 7
                val count = counts[epochDay] ?: 0
                val alpha = if (count <= 0) 0f else 0.2f + 0.8f * (count.toFloat() / maxCount)
                drawRoundRect(
                    color = if (count <= 0) emptyColor else color.copy(alpha = alpha),
                    topLeft = Offset(col * cell + gap / 2, row * cell + gap / 2),
                    size = Size(cell - gap, cell - gap),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
            }
        }
    }
}
