package com.vayunmathur.clock.ui

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.clock.MAIN_PAGES
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.TimerReceiver
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.nowState
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val now by nowState()
    val timers by viewModel.data<Timer>().collectAsState(initial = emptyList())

    Scaffold(topBar = {
        TopAppBar({ Text("Timer") })
    }, bottomBar = {
        BottomNavBar(backStack, MAIN_PAGES, Route.Timer)
    }, floatingActionButton = {
        FloatingActionButton({
            backStack.add(Route.NewTimerDialog)
        }) {
            IconAdd()
        }
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            LazyColumn(
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(timers, key = { it.id }) { timer ->
                    TimerCard(timer, now, viewModel)
                }
            }
        }
    }
}

@Composable
fun TimerCard(timer: Timer, now: Instant, viewModel: DatabaseViewModel) {
    val context = LocalContext.current

    // Calculate actual remaining time for the UI
    val realRemainingTime = if (timer.isRunning) {
        timer.remainingLength - (now - timer.remainingStartTime)
    } else {
        timer.remainingLength
    }
    if(realRemainingTime < 0.seconds) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- Left: Circular Progress & Time ---
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.Gray, style = Stroke(4f), alpha = 0.2f)
                }

                val sweep = (realRemainingTime.inWholeMilliseconds.toFloat() /
                        timer.totalLength.inWholeMilliseconds.toFloat()) * 360f

                val colorScheme = MaterialTheme.colorScheme
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(
                        color = colorScheme.primary,
                        startAngle = -90f,
                        sweepAngle = sweep.coerceAtLeast(0f),
                        useCenter = false,
                        style = Stroke(width = 6f, cap = StrokeCap.Round)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(timer.name, style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = formatDuration(realRemainingTime),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // --- Right: Controls ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                // DELETE
                IconButton(onClick = {
                    sendTimerNotification(context, timer, false)
                    viewModel.delete(timer)
                }) {
                    IconDelete()
                }

                Row {
                    // +1:00 Button
                    FilledTonalButton(onClick = {
                        val newLength = timer.remainingLength + 1.minutes
                        val updatedTimer = timer.copy(remainingLength = newLength)
                        viewModel.upsert(updatedTimer)

                        // If it's already running, update the notification immediately
                        if (timer.isRunning) {
                            sendTimerNotification(context, updatedTimer, true)
                        }
                    }) {
                        Text("+ 1:00")
                    }

                    Spacer(Modifier.width(8.dp))

                    // START / STOP Toggle
                    FilledTonalButton(
                        onClick = {
                            if (timer.isRunning) {
                                viewModel.upsert(timer.stopped())
                                sendTimerNotification(context, timer, false)
                            } else {
                                val startedTimer = timer.started()
                                viewModel.upsert(startedTimer)
                                // We pass the current realRemainingTime to the service
                                sendTimerNotification(context, startedTimer, true)
                            }
                        }
                    ) {
                        if (timer.isRunning) IconPause() else IconPlay()
                    }
                }
            }
        }
    }
}

/**
 * Helper function to communicate with the Foreground Service
 */
fun sendTimerNotification(context: Context, timer: Timer, isStarting: Boolean) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val notificationId = timer.id.hashCode()

    val alarmIntent = Intent(context, TimerReceiver::class.java).apply {
        putExtra("timer_id", timer.id)
        putExtra("timer_name", timer.name)
    }

    val pendingAlarm = PendingIntent.getBroadcast(
        context, notificationId, alarmIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    if (!isStarting) {
        nm.cancel(notificationId)
        am.cancel(pendingAlarm)
        return
    }

    // 1. Calculate the end timestamp (The "When")
    val remaining = if (timer.isRunning) {
        timer.remainingLength - (Clock.System.now() - timer.remainingStartTime)
    } else {
        timer.remainingLength
    }
    val endTimestamp = System.currentTimeMillis() + remaining.inWholeMilliseconds

    // 2. Create the Visual Notification (UI-driven)
    val notification = NotificationCompat.Builder(context, "active_timers_channel")
        .setSmallIcon(R.drawable.outline_timer_24)
        .setContentTitle(timer.name)
        .setUsesChronometer(true)
        .setChronometerCountDown(true)
        .setWhen(endTimestamp)
        .setOngoing(true) // Makes it harder to swipe away accidentally
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .build()

    nm.notify(notificationId, notification)

    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimestamp, pendingAlarm)
}