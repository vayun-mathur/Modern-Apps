package com.vayunmathur.clock.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.vayunmathur.clock.R

fun createNotificationChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)

    nm.createNotificationChannels(listOf(
        // 1. Quiet channel for ongoing countdowns
        NotificationChannel("active_timers_channel", context.getString(R.string.channel_ongoing_timers_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.channel_ongoing_timers_description)
            setShowBadge(false)
        },
        // 2. Loud channel for the "Time's Up" alert
        NotificationChannel("finished_timers_channel", context.getString(R.string.channel_completed_timers_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_completed_timers_description)
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        },
        NotificationChannel("ALARM_CHANNEL_ID", context.getString(R.string.channel_alarms_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_alarms_description)
            setBypassDnd(true)
            setSound(null, null) // Service handles sound via MediaPlayer
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        },
        // 3. Stopwatch channel
        NotificationChannel("stopwatch_channel", context.getString(R.string.channel_stopwatch_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.channel_stopwatch_description)
            setShowBadge(false)
            setSound(null, null)
        }
    ))
}
