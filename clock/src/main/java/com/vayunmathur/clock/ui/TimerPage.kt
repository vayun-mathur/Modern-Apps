package com.vayunmathur.clock.ui

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.FloatingActionButtonDefaults
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.R
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.mainPages
import com.vayunmathur.clock.util.ClockViewModel
import com.vayunmathur.clock.util.TimerReceiver
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.NavBackStack
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerPage(backStack: NavBackStack<Route>, clockViewModel: ClockViewModel) {
    val now by clockViewModel.now.collectAsState()
    val timers by clockViewModel.timers.collectAsState()
    var isAddingTimer by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val showKeypad = timers.isEmpty() || isAddingTimer

    Scaffold(
        topBar = {
            TopAppBar({ Text(stringResource(R.string.label_timer)) })
        },
        bottomBar = {
            BottomNavBar(backStack, mainPages(), Route.Timer)
        },
        floatingActionButton = {
            if (!showKeypad) {
                FloatingActionButton({
                    isAddingTimer = true
                }) {
                    IconAdd()
                }
            }
        }
    ) { paddingValues ->
        if (showKeypad) {
            TimerKeypadContent(
                paddingValues = paddingValues,
                onStart = { duration ->
                    val timer = Timer(true, "", Clock.System.now(), duration, duration)
                    clockViewModel.upsert(timer) {
                        sendTimerNotification(context, timer.copy(id = it), true)
                    }
                    isAddingTimer = false
                },
                onCancel = {
                    if (timers.isNotEmpty()) {
                        isAddingTimer = false
                    }
                },
                showCancel = timers.isNotEmpty()
            )
        } else {
            LazyColumn(
                contentPadding = paddingValues + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(timers, key = { it.id }) { timer ->
                    TimerCard(timer, now, clockViewModel)
                }
            }
        }
    }
}

@Composable
fun TimerKeypadContent(
    paddingValues: PaddingValues,
    onStart: (Duration) -> Unit,
    onCancel: () -> Unit,
    showCancel: Boolean
) {
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Time Display
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            val padded = input.padStart(6, '0')
            val h = padded.substring(0, 2)
            val m = padded.substring(2, 4)
            val s = padded.substring(4, 6)

            TimeUnitDisplay(h, "h", input.length >= 5)
            Spacer(Modifier.width(8.dp))
            TimeUnitDisplay(m, "m", input.length >= 3)
            Spacer(Modifier.width(8.dp))
            TimeUnitDisplay(s, "s", input.isNotEmpty())
        }

        // Keypad
        val appendDigits: (String) -> Unit = { input = (input + it).takeLast(6).trimStart('0') }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            KeypadRow("1", "2", "3", appendDigits)
            KeypadRow("4", "5", "6", appendDigits)
            KeypadRow("7", "8", "9", appendDigits)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                KeypadButton("00", Modifier.weight(1f)) { appendDigits("00") }
                KeypadButton("0", Modifier.weight(1f)) { appendDigits("0") }
                KeypadButton("⌫", Modifier.weight(1f)) { input = input.dropLast(1) }
            }
        }

        // Bottom Actions
        Box(modifier = Modifier.fillMaxWidth()) {
            if (showCancel) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    IconClose()
                }
            }

            val duration = remember(input) {
                val padded = input.padStart(6, '0')
                val h = padded.substring(0, 2).toIntOrNull() ?: 0
                val m = padded.substring(2, 4).toIntOrNull() ?: 0
                val s = padded.substring(4, 6).toIntOrNull() ?: 0
                (h.hours + m.minutes + s.seconds)
            }

            IconButton(
                onClick = { if (duration.inWholeSeconds > 0) onStart(duration) },
                enabled = duration.inWholeSeconds > 0,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(if (duration.inWholeSeconds > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            ) {
                IconPlay(tint = if (duration.inWholeSeconds > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
            }
        }
    }
}

@Composable
fun TimeUnitDisplay(value: String, unit: String, active: Boolean) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp),
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            fontWeight = FontWeight.Light
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.titleMedium,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.padding(bottom = 12.dp, start = 2.dp)
        )
    }
}

@Composable
fun KeypadRow(k1: String, k2: String, k3: String, onClick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        KeypadButton(k1, Modifier.weight(1f)) { onClick(k1) }
        KeypadButton(k2, Modifier.weight(1f)) { onClick(k2) }
        KeypadButton(k3, Modifier.weight(1f)) { onClick(k3) }
    }
}

@Composable
fun KeypadButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun TimerCard(timer: Timer, now: Instant, clockViewModel: ClockViewModel) {
    val context = LocalContext.current

    // Calculate actual remaining time for the UI via the shared VM helper.
    val realRemainingTime = remember(timer, now) {
        clockViewModel.timerRemaining(timer, now)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timer.name.ifBlank { stringResource(R.string.label_timer) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = {
                    sendTimerNotification(context, timer, false)
                    clockViewModel.delete(timer)
                }) {
                    IconDelete()
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                val colorScheme = MaterialTheme.colorScheme
                val inactiveColor = colorScheme.outlineVariant
                val activeColor = colorScheme.primary
                val strokeWidth = 8.dp

                Canvas(Modifier.fillMaxSize()) {
                    val strokeWidthPx = strokeWidth.toPx()
                    drawCircle(inactiveColor, style = Stroke(width = strokeWidthPx), alpha = 0.3f)

                    val sweep = (realRemainingTime.inWholeMilliseconds.toFloat() /
                            timer.totalLength.inWholeMilliseconds.coerceAtLeast(1000).toFloat()) * 360f

                    drawArc(
                        color = activeColor,
                        startAngle = -90f,
                        sweepAngle = sweep.coerceAtLeast(0f),
                        useCenter = false,
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                    )
                }

                Text(
                    text = formatTimerDuration(realRemainingTime),
                    style = MaterialTheme.typography.displayMedium,
                    color = if (timer.isRunning) colorScheme.primary else colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = {
                        val newLength = timer.remainingLength + 1.minutes
                        val updatedTimer = timer.copy(
                            remainingLength = newLength,
                            totalLength = timer.totalLength + 1.minutes
                        )
                        clockViewModel.upsert(updatedTimer)
                        if (timer.isRunning) {
                            sendTimerNotification(context, updatedTimer, true)
                        }
                    }
                ) {
                    Text(stringResource(R.string.button_add_minute))
                }

                Spacer(Modifier.width(16.dp))

                FloatingActionButton(
                    onClick = {
                        if (timer.isRunning) {
                            clockViewModel.upsert(timer.stopped())
                            sendTimerNotification(context, timer, false)
                        } else {
                            val startedTimer = timer.started()
                            clockViewModel.upsert(startedTimer)
                            sendTimerNotification(context, startedTimer, true)
                        }
                    },
                    containerColor = if (timer.isRunning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (timer.isRunning) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier.size(56.dp)
                ) {
                    if (timer.isRunning) IconPause() else IconPlay()
                }
            }
        }
    }
}

fun formatTimerDuration(duration: Duration): String =
    duration.toComponents { hours, minutes, seconds, _ ->
        if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
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

    // Create action intents
    val pauseIntent = PendingIntent.getBroadcast(
        context, notificationId + 1,
        Intent(context, com.vayunmathur.clock.util.TimerActionReceiver::class.java).apply {
            action = com.vayunmathur.clock.util.TimerActionReceiver.ACTION_PAUSE
            putExtra("timer_id", timer.id)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val resumeIntent = PendingIntent.getBroadcast(
        context, notificationId + 2,
        Intent(context, com.vayunmathur.clock.util.TimerActionReceiver::class.java).apply {
            action = com.vayunmathur.clock.util.TimerActionReceiver.ACTION_RESUME
            putExtra("timer_id", timer.id)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val cancelIntent = PendingIntent.getBroadcast(
        context, notificationId + 3,
        Intent(context, com.vayunmathur.clock.util.TimerActionReceiver::class.java).apply {
            action = com.vayunmathur.clock.util.TimerActionReceiver.ACTION_CANCEL
            putExtra("timer_id", timer.id)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val resetIntent = PendingIntent.getBroadcast(
        context, notificationId + 4,
        Intent(context, com.vayunmathur.clock.util.TimerActionReceiver::class.java).apply {
            action = com.vayunmathur.clock.util.TimerActionReceiver.ACTION_RESET
            putExtra("timer_id", timer.id)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // Content intent to open app
    val contentIntent = PendingIntent.getActivity(
        context, notificationId + 5,
        Intent(context, com.vayunmathur.clock.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // 2. Create the Visual Notification (UI-driven)
    val builder = NotificationCompat.Builder(context, "active_timers_channel")
        .setSmallIcon(R.drawable.outline_timer_24)
        .setContentTitle(timer.name.ifBlank { context.getString(R.string.label_timer) })
        .setUsesChronometer(true)
        .setChronometerCountDown(true)
        .setWhen(endTimestamp)
        .setOngoing(true) // Makes it harder to swipe away accidentally
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setContentIntent(contentIntent)
        .setOnlyAlertOnce(true)

    // Add actions based on timer state
    if (timer.isRunning) {
        builder.addAction(R.drawable.ic_pause_24, context.getString(R.string.action_pause), pauseIntent)
    } else {
        builder.addAction(R.drawable.ic_play_24, context.getString(R.string.action_resume), resumeIntent)
    }
    builder.addAction(R.drawable.ic_cancel_24, context.getString(R.string.action_cancel), cancelIntent)
    builder.addAction(R.drawable.ic_reset_24, context.getString(R.string.action_reset), resetIntent)

    val notification = builder.build()

    nm.notify(notificationId, notification)

    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimestamp, pendingAlarm)
}
