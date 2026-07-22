package com.vayunmathur.clock.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.MainActivity
import com.vayunmathur.clock.R
import com.vayunmathur.library.util.DataStoreUtils
import kotlin.time.Clock

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
        
        val elapsedMs = if (isRunning) {
            totalMs + (Clock.System.now().toEpochMilliseconds() - startMs)
        } else {
            totalMs
        }
        
        val minutes = elapsedMs / 60000
        val seconds = (elapsedMs % 60000) / 1000
        val contentText = String.format("%02d:%02d", minutes, seconds)
        
        // Content intent to open app
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
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
        
        val builder = NotificationCompat.Builder(context, "stopwatch_channel")
            .setSmallIcon(R.drawable.outline_timer_24)
            .setContentTitle(context.getString(R.string.label_stopwatch))
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        
        if (isRunning) {
            builder.addAction(R.drawable.ic_pause_24, context.getString(R.string.action_pause), toggleIntent)
            builder.addAction(R.drawable.ic_lap_24, context.getString(R.string.action_lap), lapIntent)
        } else {
            builder.addAction(R.drawable.ic_play_24, context.getString(R.string.action_resume), toggleIntent)
        }
        builder.addAction(R.drawable.ic_stop_24, context.getString(R.string.action_stop), stopIntent)
        
        nm.notify(StopwatchActionReceiver.STOPWATCH_NOTIFICATION_ID, builder.build())
    }
}