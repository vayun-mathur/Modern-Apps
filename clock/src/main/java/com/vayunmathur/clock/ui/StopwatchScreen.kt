package com.vayunmathur.clock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.clock.R
import com.vayunmathur.clock.ui.components.LapRow
import com.vayunmathur.clock.platform.StopwatchActions
import com.vayunmathur.clock.platform.StopwatchUiState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconRestartAlt
import com.vayunmathur.library.ui.IconTimer
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.VerticalFloatingToolbar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isExpandedWidth
import kotlin.time.Duration.Companion.seconds

/**
 * The stopwatch, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopwatchScreen(
    state: StopwatchUiState,
    actions: StopwatchActions,
) {
    val isRunning = state.isRunning
    val countingTime = state.countingTime
    val lapTimes = state.lapTimes
    val lapSplits by remember(lapTimes) {
        derivedStateOf {
            lapTimes.mapIndexed { index, totalTimeAtLap ->
                if (index == 0) totalTimeAtLap else totalTimeAtLap - lapTimes[index - 1]
            }
        }
    }
    val expanded = isExpandedWidth()

    LazyListScaffold(floatingActionButton = {
        // Lap and reset are peers of start/stop, not separate decisions, so they share the
        // stopwatch's one control surface rather than stacking as three loose FABs. A toolbar
        // rather than a FAB menu because lap is timed to whatever the user is measuring and
        // cannot afford an expand tap first.
        VerticalFloatingToolbar(
            floatingActionButton = {
                FloatingActionButton({ actions.toggleStopwatch() }) {
                    if(isRunning) IconPause() else IconPlay()
                }
            },
        ) {
            if(isRunning) {
                IconButton({ actions.addLap() }) { IconTimer() }
            }
            if(countingTime > 0.seconds) {
                IconButton({ actions.resetStopwatch() }) { IconRestartAlt() }
            }
        }
    }, horizontalPadding = 16.dp) {
        // On expanded widths the dial and the laps sit side by side in fractional
        // columns so neither stretches awkwardly wide; compact stays stacked.
        if (expanded) {
            item {
                Row(
                    modifier = Modifier.fillParentMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        StopwatchDial(countingTime = countingTime)
                    }
                    LapsCard(
                        lapTimes = lapTimes,
                        lapSplits = lapSplits,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            item {
                Box(Modifier.fillParentMaxWidth(), contentAlignment = Alignment.Center) {
                    StopwatchDial(countingTime = countingTime)
                }
            }

            item {
                LapsCard(lapTimes = lapTimes, lapSplits = lapSplits)
            }
        }
    }
}

@Composable
private fun StopwatchDial(countingTime: kotlin.time.Duration) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.padding(top = 40.dp, bottom = 40.dp).size(320.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = trackColor, style = Stroke(width = 8f))
        }
        val sweepAngle = ((countingTime.inWholeMilliseconds % 60000) / 60000f) * 360f
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = 12f, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            countingTime.toComponents { minutes, seconds, nanoseconds ->
                val centiseconds = nanoseconds / 10_000_000
                Text(
                    text = stringResource(R.string.stopwatch_time_format, minutes, seconds),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, fontWeight = FontWeight.Normal)
                )
                Text(
                    text = stringResource(R.string.duration_ms_format, 0, centiseconds),
                    style = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Light)
                )
            }
        }
    }
}

@Composable
private fun LapsCard(
    lapTimes: List<kotlin.time.Duration>,
    lapSplits: List<kotlin.time.Duration>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.header_laps), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.header_split), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.header_total), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        itemsIndexed(lapTimes.reversed()) { index, currentTotal ->
                            val lapNumber = lapTimes.size - index
                            val prevTotal = if (lapNumber > 1) lapTimes[lapNumber - 2] else 0.seconds
                            val split = currentTotal - prevTotal
                            val maxLength = lapSplits.max()
                            val minLength = lapSplits.min()
                            LapRow(lapNumber, when(split) {
                                minLength -> MaterialTheme.colorScheme.tertiary
                                maxLength -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            }, split, currentTotal)
                        }
                    }
                }
    }
}
