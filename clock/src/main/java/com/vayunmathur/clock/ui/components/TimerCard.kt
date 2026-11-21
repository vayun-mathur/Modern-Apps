package com.vayunmathur.clock.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.clock.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.platform.TimerActions
import com.vayunmathur.clock.platform.timerRemaining
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.AssistChipDefaults
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.FloatingActionButtonDefaults
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconRestartAlt
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun TimerCard(timer: Timer, now: kotlin.time.Instant, actions: TimerActions) {
    val realRemainingTime = remember(timer, now) { timerRemaining(timer, now, com.vayunmathur.clock.platform.DualTimeBase.elapsedRealtimeMs()) }
    val isCompleted = realRemainingTime == Duration.ZERO && !timer.isRunning
    val stateLabel = when {
        isCompleted -> "Completed"
        timer.isRunning -> "Running"
        else -> "Paused"
    }
    val isPristine = timer.isRunning && realRemainingTime == timer.totalLength
    val showReset = !isPristine

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = timer.name.ifBlank { stringResource(R.string.label_timer) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    val chipColors = when {
                        isCompleted -> AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer, labelColor = MaterialTheme.colorScheme.onErrorContainer)
                        timer.isRunning -> AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer, labelColor = MaterialTheme.colorScheme.onPrimaryContainer)
                        else -> AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, labelColor = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    AssistChip(onClick = {}, label = { Text(stateLabel) }, colors = chipColors)
                }
                IconButton(onClick = { actions.delete(timer) }) { IconDelete() }
            }
            Spacer(Modifier.height(12.dp))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                val colorScheme = MaterialTheme.colorScheme
                val inactiveColor = colorScheme.outlineVariant
                val activeColor = if (isCompleted) colorScheme.outlineVariant.copy(alpha = 0.3f) else colorScheme.primary
                val strokeWidth = 8.dp
                Canvas(Modifier.fillMaxSize()) {
                    val strokeWidthPx = strokeWidth.toPx()
                    drawCircle(inactiveColor, style = Stroke(width = strokeWidthPx), alpha = 0.3f)
                    val sweep = (realRemainingTime.inWholeMilliseconds.toFloat() / timer.totalLength.inWholeMilliseconds.coerceAtLeast(1000).toFloat()) * 360f
                    drawArc(color = activeColor, startAngle = -90f, sweepAngle = sweep.coerceAtLeast(0f), useCenter = false, style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round))
                }
                Text(
                    text = formatTimerDuration(realRemainingTime),
                    style = MaterialTheme.typography.displayMedium,
                    color = when {
                        isCompleted -> colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        timer.isRunning -> colorScheme.primary
                        else -> colorScheme.onSurface
                    }
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (!isCompleted) {
                    FilledTonalButton(onClick = { actions.addMinute(timer) }) { Text(stringResource(R.string.button_add_minute)) }
                    if (showReset) Spacer(Modifier.width(8.dp))
                }
                if (showReset) {
                    FilledTonalButton(onClick = { actions.reset(timer) }) { IconRestartAlt(); Spacer(Modifier.width(4.dp)); Text(stringResource(UiR.string.reset)) }
                    Spacer(Modifier.width(16.dp))
                } else if (!isCompleted) { Spacer(Modifier.width(16.dp)) } else { Spacer(Modifier.width(16.dp)) }
                FloatingActionButton(
                    onClick = { actions.toggle(timer) },
                    containerColor = when {
                        isCompleted -> MaterialTheme.colorScheme.primaryContainer
                        timer.isRunning -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = when {
                        isCompleted -> MaterialTheme.colorScheme.onPrimaryContainer
                        timer.isRunning -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier.size(56.dp)
                ) {
                    when { isCompleted -> IconRestartAlt(); timer.isRunning -> IconPause(); else -> IconPlay() }
                }
            }
        }
    }
}

fun formatTimerDuration(duration: Duration): String =
    duration.toComponents { hours, minutes, seconds, _ ->
        if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }
