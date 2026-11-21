package com.vayunmathur.clock.platform

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.MainActivity
import com.vayunmathur.clock.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.DataStoreUtils
import java.util.Locale
import kotlin.time.Clock

/** Builds and updates the ongoing stopwatch notification with pause, lap, and stop actions. */
object StopwatchNotificationHelper {
    fun updateNotification(context: Context) {
        val ds = DataStoreUtils.getInstance(context)
        val isRunning = ds.getBoolean(StopwatchActionReceiver.KEY_STOPWATCH_RUNNING, false)
        val totalMs = ds.getLong(StopwatchActionReceiver.KEY_STOPWATCH_TOTAL) ?: 0L
        val startMs = ds.getLong(StopwatchActionReceiver.KEY_STOPWATCH_START) ?: 0L

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!isRunning && totalMs == 0L) {
            // Stopwatch is reset, cancel notification
            nm.cancel(StopwatchActionReceiver.STOPWATCH_NOTIFICATION_ID)
            return
        }

        val contentText = formatElapsed(isRunning, totalMs, startMs)
        val builder = baseBuilder(context, contentText)
        addActions(context, builder, isRunning)
        nm.notify(StopwatchActionReceiver.STOPWATCH_NOTIFICATION_ID, builder.build())
    }

    private fun formatElapsed(isRunning: Boolean, totalMs: Long, startMs: Long): String {
        val elapsedMs = if (isRunning) {
            totalMs + (Clock.System.now().toEpochMilliseconds() - startMs)
        } else {
            totalMs
        }
        // Past one hour, roll over to H:MM:SS (the timer card's shape)
        // instead of letting minutes accumulate without bound ("125:34").
        val hours = elapsedMs / MILLIS_PER_HOUR
        val minutes = (elapsedMs % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE
        val seconds = (elapsedMs % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun baseBuilder(
        context: Context,
        contentText: String,
    ): NotificationCompat.Builder {
        // Content intent to open app
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, "stopwatch_channel")
            .setSmallIcon(R.drawable.outline_timer_24)
            .setContentTitle(context.getString(R.string.channel_stopwatch_name))
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
    }

    private fun addActions(
        context: Context,
        builder: NotificationCompat.Builder,
        isRunning: Boolean,
    ) {
        // Pause/Resume action
        val toggleIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, StopwatchActionReceiver::class.java).apply {
                action = StopwatchActionReceiver.ACTION_TOGGLE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Lap action
        val lapIntent = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, StopwatchActionReceiver::class.java).apply {
                action = StopwatchActionReceiver.ACTION_LAP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action
        val stopIntent = PendingIntent.getBroadcast(
            context, 3,
            Intent(context, StopwatchActionReceiver::class.java).apply {
                action = StopwatchActionReceiver.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (isRunning) {
            builder.addAction(
                R.drawable.ic_pause_24,
                context.getString(R.string.action_pause),
                toggleIntent,
            )
            builder.addAction(
                R.drawable.ic_lap_24,
                context.getString(R.string.action_lap),
                lapIntent,
            )
        } else {
            builder.addAction(
                R.drawable.ic_play_24,
                context.getString(R.string.action_resume),
                toggleIntent,
            )
        }
        builder.addAction(
            R.drawable.ic_stop_24,
            context.getString(UiR.string.stop),
            stopIntent,
        )
    }

    private const val MILLIS_PER_HOUR = 3600000L
    private const val MILLIS_PER_MINUTE = 60000L
    private const val MILLIS_PER_SECOND = 1000L
}
