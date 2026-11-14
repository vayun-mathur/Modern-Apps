package com.vayunmathur.clock.platform
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration

/** Receives a finished timer, posts its completion notification, and marks the timer stopped. */
class TimerReceiver : BroadcastReceiver() {
    // Broad catch is deliberate: onReceive must not throw, and Room throws
    // undocumented RuntimeExceptions (not just SQLiteException).
    @Suppress("TooGenericExceptionCaught")
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

                val repository = ClockRepository.get(context)
                // Persistence: keep timer visible after completion instead of deleting.
                try {
                    val existing = repository.getTimer(id)
                    val completed = existing.copy(
                        isRunning = false,
                        remainingLength = Duration.ZERO,
                        remainingStartTime = Clock.System.now(),
                    )
                    repository.upsertTimer(completed)
                } catch (e: Exception) {
                    Log.status(TAG, "could not load timer $id; writing minimal completed row", e)
                    // Fallback: upsert a minimal completed timer if get fails
                    val completedFallback = Timer(
                        isRunning = false,
                        name = name,
                        remainingStartTime = Clock.System.now(),
                        remainingLength = Duration.ZERO,
                        totalLength = Duration.ZERO,
                        id = id,
                    )
                    // Try to preserve totalLength from name-based heuristic: keep ZERO if unknown
                    try {
                        repository.upsertTimer(completedFallback)
                    } catch (ignored: Exception) {
                        // Best-effort: if even fallback fails, keep original delete behavior skipped
                    }
                }
            } catch (e: Exception) {
                Log.error(TAG, "could not complete timer $id", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TimerReceiver"
    }
}
