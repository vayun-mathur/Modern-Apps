package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.ui.components.ActivityRingsTrio
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

@Composable
internal fun ActivityRingsHero(
    stepsToday: Long,
    activeCaloriesToday: Long,
    mindfulnessToday: Long,
) {
    val stepsGoal = HealthMetricConfig.STEPS.dailyGoal
    val energyGoal = HealthMetricConfig.ACTIVE_CALORIES.dailyGoal
    val mindfulGoal = 20.0
    val stepsPct = (stepsToday.toFloat() / stepsGoal.toFloat()).coerceIn(0f, 1f)
    val energyPct = (activeCaloriesToday.toFloat() / energyGoal.toFloat()).coerceIn(0f, 1f)
    val mindfulPct = (mindfulnessToday.toFloat() / mindfulGoal.toFloat()).coerceIn(0f, 1f)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                modifier = Modifier.size(140.dp),
                contentAlignment = Alignment.Center,
            ) {
                ActivityRingsTrio(
                    stepsPct = stepsPct,
                    energyPct = energyPct,
                    mindfulPct = mindfulPct,
                    modifier = Modifier.size(140.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RingLegendItem(
                    color = HealthColors.Activity,
                    label = stringResource(R.string.metric_steps),
                    value = "$stepsToday",
                    unit = stringResource(R.string.metric_steps),
                )
                RingLegendItem(
                    color = HealthColors.Nutrition,
                    label = stringResource(R.string.label_active),
                    value = "$activeCaloriesToday",
                    unit = stringResource(R.string.unit_cal),
                )
                RingLegendItem(
                    color = HealthColors.Sleep,
                    label = stringResource(R.string.label_mindfulness),
                    value = "$mindfulnessToday",
                    unit = stringResource(R.string.unit_min),
                )
            }
        }
    }
}

@Composable
private fun RingLegendItem(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    value: String,
    unit: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(10.dp),
            shape = CircleShape,
            color = color,
            content = {},
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

internal fun formatSleep(minutes: Long): String = com.vayunmathur.health.platform.formatDuration(minutes)
