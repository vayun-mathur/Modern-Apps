package com.vayunmathur.astronomy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalTime::class)
@Composable
fun TimeScrubber(
    simTime: Instant,
    isLive: Boolean,
    onTimeChange: (Instant, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val now = remember { Clock.System.now() }
    var sliderPos by remember(simTime) {
        mutableFloatStateOf(
            ((simTime.toEpochMilliseconds() - now.toEpochMilliseconds()) / 3600000.0 / 12.0)
                .toFloat().coerceIn(-1f, 1f)
        )
    }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (isLive) "Now: ${formatInstant(simTime)}" else "Sim: ${formatInstant(simTime)}", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isLive) FilledTonalButton(onClick = { onTimeChange(Clock.System.now(), true) }) { Text("Now") }
                IconButton(onClick = { onTimeChange(simTime.minus(1.hours), false) }) { Text("-1h") }
                IconButton(onClick = { onTimeChange(simTime.plus(1.hours), false) }) { Text("+1h") }
            }
        }
        Slider(
            value = sliderPos,
            onValueChange = { v ->
                sliderPos = v
                onTimeChange(now.plus((v * 12.0).hours), false)
            },
            valueRange = -1f..1f,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("-12h", style = MaterialTheme.typography.labelSmall)
            Text("Now", style = MaterialTheme.typography.labelSmall)
            Text("+12h", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalTime::class)
fun formatInstant(inst: Instant): String {
    val ldt = inst.toLocalDateTime(TimeZone.currentSystemDefault())
    return buildString {
        append(ldt.year); append('-')
        append(ldt.month.number.toString().padStart(2, '0')); append('-')
        append(ldt.day.toString().padStart(2, '0')); append(' ')
        append(ldt.hour.toString().padStart(2, '0')); append(':')
        append(ldt.minute.toString().padStart(2, '0'))
    }
}
