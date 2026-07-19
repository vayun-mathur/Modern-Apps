package com.vayunmathur.clock.ui
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconAlarm
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.clock.data.ClockDatabase
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.room.buildDatabase
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import com.vayunmathur.clock.util.AlarmReceiver
import com.vayunmathur.clock.util.AlarmSoundService
import com.vayunmathur.clock.R

class AlarmActivity : ComponentActivity() {
    private var alarmId: Long = -1L
    private var snoozeMinutes: Int = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        // Essential wake-up flags (MUST be before super.onCreate)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        super.onCreate(savedInstanceState)

        alarmId = intent.getLongExtra("ALARM_ID", -1L)
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        km.requestDismissKeyguard(this, null)

        val db = buildDatabase<ClockDatabase>(useDeviceProtectedStorage = true)

        setContent {
            val alarm by androidx.compose.runtime.produceState<com.vayunmathur.clock.data.Alarm?>(initialValue = null) {
                value = withContext(Dispatchers.IO) {
                    db.alarmDao().get(alarmId)
                }
            }

            androidx.compose.runtime.LaunchedEffect(alarm) {
                alarm?.let { snoozeMinutes = it.snoozeMinutes }
            }

            DynamicTheme {
                AlarmRingingScreen(
                    alarmTime = alarm?.let { formatAlarmTime(this@AlarmActivity, it.time) } ?: "--:--",
                    alarmName = alarm?.name ?: getString(R.string.label_alarm),
                    onDismiss = { dismissAlarm() },
                    onSnooze = { snoozeAlarm() }
                )
            }
        }
    }

    private fun dismissAlarm() {
        // 1. Stop the sound/vibration
        stopService(Intent(this, AlarmSoundService::class.java))

        // 2. Clear the notification
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(alarmId.toInt())

        // 3. Close the activity
        finish()
    }

    private fun snoozeAlarm() {
        // 1. Stop sound
        stopService(Intent(this, AlarmSoundService::class.java))

        // 2. Calculate snooze time from now using the alarm's snooze length
        val snoozeTime = Clock.System.now().plus(snoozeMinutes.minutes)
        val triggerMillis = snoozeTime.toEpochMilliseconds()

        // 3. Schedule the snooze alarm using the SAME ID
        // This replaces any existing schedule for this ID
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("ALARM_ID", alarmId)
            putExtra("IS_SNOOZE", true)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(AlarmManager::class.java)
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerMillis, pendingIntent),
            pendingIntent
        )

        // 4. Close the activity
        finish()
    }
}

@Composable
fun AlarmRingingScreen(
    alarmTime: String,
    alarmName: String,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Time and Label Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 32.dp)
            ) {
                Text(
                    text = alarmTime,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 80.sp,
                        fontWeight = FontWeight.Black,
                        // Tighten letter spacing for that "Clock" look
                        letterSpacing = (-2).sp
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                if (alarmName.isNotEmpty()) {
                    Text(
                        text = alarmName.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // 2. Animated Centerpiece
            // You can easily swap this Box for a Lottie animation later
            Box(
                modifier = Modifier
                    .weight(1f) // Takes up available space between header and buttons
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.size(160.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.05f)
                ) {
                    IconAlarm(
                        modifier = Modifier.padding(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 3. Action Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Dismiss Button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp), // Extra large for easy hitting
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.button_stop),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                // Snooze Button
                FilledTonalButton(
                    onClick = onSnooze,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    IconPause()
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.button_snooze),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}