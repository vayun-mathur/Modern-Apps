package com.vayunmathur.clock

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation3.runtime.NavKey
import com.github.doyaaaaaken.kotlincsv.client.CsvFileReader
import com.github.doyaaaaaken.kotlincsv.client.CsvReader
import com.vayunmathur.clock.data.Alarm
import com.vayunmathur.clock.data.ClockDatabase
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.ui.AlarmPage
import com.vayunmathur.clock.ui.ClockPage
import com.vayunmathur.clock.ui.StopwatchPage
import com.vayunmathur.clock.ui.TimerPage
import com.vayunmathur.clock.ui.dialog.NewTimerDialog
import com.vayunmathur.clock.ui.dialog.SelectTimeZonesDialog
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.dialog.TimePickerDialogContent
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock

lateinit var citiesToTimezones: Map<String, String>

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val alarmManager = getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms()) {
            // Redirect user to system settings to allow exact alarms
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
        createTimerNotificationChannels(this)
        val ds = DataStoreUtils.getInstance(this)
        val db = buildDatabase<ClockDatabase>()
        val viewModel = DatabaseViewModel(db, Timer::class to db.timerDao(), Alarm::class to db.alarmDao())
        setContent {
            LaunchedEffect(Unit) {
                CoroutineScope(Dispatchers.IO).launch {
                    readTimezones(this@MainActivity)
                }
            }
            DynamicTheme {
                Navigation(ds, viewModel)
            }
        }
    }
}

fun readTimezones(context: Context) {
    citiesToTimezones =
        context.assets.open("cities.csv").bufferedReader().readLines().drop(1).map {
            it.split(",")
        }.filter {
            val pop = it[14].toDoubleOrNull()
            pop != null && pop > 100000
        }.associate {
            it[1].replace("\"", "") to it[15]
        }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Alarm: Route
    @Serializable
    data object Clock: Route
    @Serializable
    data object Timer: Route
    @Serializable
    data object Stopwatch: Route
    @Serializable
    data object SelectTimeZonesDialog: Route
    @Serializable
    data object NewTimerDialog: Route
    @Serializable
    data object NewAlarmDialog: Route
    @Serializable
    data class AlarmSetTimeDialog(val id: Long, val time: LocalTime): Route
}

val MAIN_PAGES = listOf(
    BottomBarItem("Alarm", Route.Alarm, R.drawable.baseline_access_alarm_24),
    BottomBarItem("Clock", Route.Clock, R.drawable.baseline_access_time_24),
    BottomBarItem("Timer", Route.Timer, R.drawable.baseline_hourglass_bottom_24),
    BottomBarItem("Stopwatch", Route.Stopwatch, R.drawable.outline_timer_24)
)

@Composable
fun Navigation(ds: DataStoreUtils, viewModel: DatabaseViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Alarm)
    MainNavigation(backStack) {
        entry<Route.Alarm> {
            AlarmPage(backStack, viewModel)
        }
        entry<Route.Clock> {
            ClockPage(backStack, ds)
        }
        entry<Route.Timer> {
            TimerPage(backStack, viewModel)
        }
        entry<Route.Stopwatch> {
            StopwatchPage(backStack)
        }
        entry<Route.SelectTimeZonesDialog>(metadata = DialogPage()) {
            SelectTimeZonesDialog(backStack, ds)
        }
        entry<Route.NewTimerDialog>(metadata = DialogPage()) {
            NewTimerDialog(backStack, viewModel)
        }
        entry<Route.NewAlarmDialog>(metadata = DialogPage()) {
            TimePickerDialogContent(backStack, "alarm_time", Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time)
        }
        entry<Route.AlarmSetTimeDialog>(metadata = DialogPage()) {
            TimePickerDialogContent(backStack, "alarm_set_time_${it.id}", it.time)
        }
    }
}

fun createTimerNotificationChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)

    nm.createNotificationChannels(listOf(
        // 1. Quiet channel for ongoing countdowns
        NotificationChannel("active_timers_channel", "Active Timers", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Ongoing countdowns"
            setShowBadge(false)
        },
        // 2. Loud channel for the "Time's Up" alert
        NotificationChannel("finished_timers_channel", "Timer Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alerts when timers finish"
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    ))
}