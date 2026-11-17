package com.vayunmathur.health.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Three concentric activity rings. Outer = steps, middle = energy,
 * inner = mindful. No labels inside — caller provides the legend below.
 */
@Composable
fun ActivityRingsTrio(
    stepsPct: Float,
    energyPct: Float,
    mindfulPct: Float,
    modifier: Modifier = Modifier,
    outerColor: Color = com.vayunmathur.health.ui.HealthColors.Activity,
    middleColor: Color = com.vayunmathur.health.ui.HealthColors.Nutrition,
    innerColor: Color = com.vayunmathur.health.ui.HealthColors.Sleep,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value).dp
        val stroke = (side.value * 0.10f).dp
        val gap = (side.value * 0.02f).dp
        val step = stroke + gap
        Box(modifier = Modifier.size(side), contentAlignment = Alignment.Center) {
            MetricRing(
                progress = stepsPct,
                label = "",
                value = "",
                modifier = Modifier.size(side),
                color = outerColor,
                strokeWidth = stroke,
            )
            MetricRing(
                progress = energyPct,
                label = "",
                value = "",
                modifier = Modifier.size(side - step * 2),
                color = middleColor,
                strokeWidth = stroke,
            )
            MetricRing(
                progress = mindfulPct,
                label = "",
                value = "",
                modifier = Modifier.size(side - step * 4),
                color = innerColor,
                strokeWidth = stroke,
            )
        }
    }
}
