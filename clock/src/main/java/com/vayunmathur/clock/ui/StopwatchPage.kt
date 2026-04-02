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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.clock.MAIN_PAGES
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.nowState
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopwatchPage(backStack: NavBackStack<Route>) {
    var isRunning by retain { mutableStateOf(false) }
    var totalTime by retain { mutableStateOf(0.seconds) }
    var startTime by retain { mutableStateOf(Clock.System.now()) }
    val now by nowState()
    val countingTime = if(isRunning) (now - startTime) + totalTime else totalTime
    val lapTimes = retain { mutableStateListOf<Duration>() }
    val lapSplits by remember {
        derivedStateOf {
            lapTimes.mapIndexed { index, totalTimeAtLap ->
                if (index == 0) {
                    totalTimeAtLap // The first lap's length is just its end time
                } else {
                    totalTimeAtLap - lapTimes[index - 1] // Subtract previous end time
                }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar({Text("Stopwatch")})
    }, bottomBar = {
        BottomNavBar(backStack, MAIN_PAGES, Route.Stopwatch)
    }, floatingActionButton = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if(isRunning) {
                FloatingActionButton({
                    lapTimes += countingTime
                    // add lap
                }) {
                    Icon(painterResource(R.drawable.outline_timer_24), null)
                }
            }
            if(countingTime > 0.seconds) {
                FloatingActionButton(onClick = {
                    isRunning = false
                    totalTime = 0.seconds
                }) {
                    Icon(painterResource(R.drawable.outline_restart_alt_24), null)
                }
            }
            FloatingActionButton({
                if(isRunning) {
                    isRunning = false
                    totalTime += Clock.System.now() - startTime
                } else {
                    isRunning = true
                    startTime = Clock.System.now()
                }
            }) {
                if(isRunning) {
                    IconPause()
                } else {
                    IconPlay()
                }
            }
        }
    }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- 1. CIRCULAR TIMER ---
            Box(
                modifier = Modifier
                    .padding(top = 40.dp, bottom = 40.dp)
                    .size(320.dp),
                contentAlignment = Alignment.Center
            ) {
                // Background Track
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color = Color.DarkGray.copy(alpha = 0.3f), style = Stroke(width = 8f))
                }

                // Sweeping Progress Arc (60-second loop)
                val sweepAngle = ((countingTime.inWholeMilliseconds % 60000) / 60000f) * 360f
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawArc(
                        color = Color.LightGray,
                        startAngle = -90f,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = 12f, cap = StrokeCap.Round)
                    )
                }

                // Time Display
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val minutes = countingTime.inWholeMinutes
                    val seconds = countingTime.inWholeSeconds % 60
                    val centiseconds = (countingTime.inWholeMilliseconds % 1000) / 10

                    Text(
                        text = "$minutes:${seconds.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 84.sp,
                            fontWeight = FontWeight.Normal
                        )
                    )
                    Text(
                        text = centiseconds.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            color = Color.Gray,
                            fontWeight = FontWeight.Light
                        )
                    )
                }
            }

            // --- 2. LAPS BOX ---
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1C1C1E) // Darker background for the box
            ) {
                Column {
                    // Table Header
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Laps", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                        Text("Split", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                        Text("Total", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = Color.Gray.copy(alpha = 0.5f))

                    // Lap List
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        itemsIndexed(lapTimes.reversed()) { index, currentTotal ->
                            val lapNumber = lapTimes.size - index
                            val prevTotal = if (lapNumber > 1) lapTimes[lapNumber - 2] else 0.seconds
                            val split = currentTotal - prevTotal
                            val maxLength = lapSplits.max()
                            val minLength = lapSplits.min()

                            LapRow(lapNumber, when(split) {
                                minLength -> Color.Green
                                maxLength -> Color.Red
                                else -> Color.White
                            }, split, currentTotal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LapRow(number: Int, color: Color, split: Duration, total: Duration) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("# $number", color = color, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(formatDuration(split), color = color, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text(formatDuration(total), color = color, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
    }
}

fun formatDuration(d: Duration): String {
    val m = d.inWholeMinutes
    val s = d.inWholeSeconds % 60
    val ms = (d.inWholeMilliseconds % 1000) / 10
    if(m == 0L) {
        return "${s}.${ms.toString().padStart(2, '0')}"
    }
    return "$m:${s.toString().padStart(2, '0')}.${ms.toString().padStart(2, '0')}"
}