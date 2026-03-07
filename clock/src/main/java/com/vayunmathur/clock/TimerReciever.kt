package com.vayunmathur.clock

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.data.ClockDatabase
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.library.util.buildDatabase
import kotlinx.coroutines.*
import kotlin.time.Clock
import kotlin.time.Duration

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("timer_name") ?: "Timer"
        val id = intent.getLongExtra("timer_id", 0L)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {

                // 3. Cancel the old "ticking" notification if it's still there
                nm.cancel(id.hashCode())
                // 1. Post the "Finished" notification immediately
                // This triggers the sound automatically via the channel settings
                postFinishedNotification(context, name, id)

                // 2. Database Cleanup
                val db = context.buildDatabase<ClockDatabase>()
                db.timerDao().delete(
                    Timer(true, name, Clock.System.now(), Duration.ZERO, Duration.ZERO, id)
                )

            } catch (e: Exception) {
                // Handle potential DB or null pointer issues
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postFinishedNotification(context: Context, name: String, id: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, "finished_timers_channel")
            .setSmallIcon(R.drawable.outline_timer_24)
            .setContentTitle("Timer Finished")
            .setContentText("$name has ended!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(false)
            // Ensure the notification actually "pops up" on screen
            .setFullScreenIntent(null, true)
            .build()

        nm.notify(id.hashCode(), notification)
    }
}