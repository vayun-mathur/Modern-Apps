package com.vayunmathur.clock.util
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.data.ClockDatabase
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.R
import com.vayunmathur.library.room.buildDatabase
import kotlinx.coroutines.*
import kotlin.time.Clock
import kotlin.time.Duration

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("timer_name") ?: context.getString(R.string.label_timer)
        val id = intent.getLongExtra("timer_id", 0L)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                nm.cancel(id.hashCode())

                val notification = NotificationCompat.Builder(context, "finished_timers_channel")
                    .setSmallIcon(R.drawable.outline_timer_24)
                    .setContentTitle(context.getString(R.string.timer_finished_title))
                    .setContentText(context.getString(R.string.timer_finished_text, name))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setFullScreenIntent(null, true)
                    .build()
                nm.notify(id.hashCode(), notification)

                val db = context.buildDatabase<ClockDatabase>(useDeviceProtectedStorage = true)
                db.timerDao().delete(
                    Timer(true, name, Clock.System.now(), Duration.ZERO, Duration.ZERO, id)
                )
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }
}