package com.vayunmathur.clock.wear.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vayunmathur.clock.wear.ui.WearRingActivity

/** What is ringing on the watch. */
enum class RingKind { ALARM, TIMER }

/**
 * Posts the ringing surface for a fired alarm/timer: a high-priority
 * full-screen notification whose intent opens [WearRingActivity] (the
 * thing that actually sounds/vibrates), plus a direct activity start
 * attempt for when the watch is already interactive.
 */
object WatchRingNotifier {
    const val CHANNEL_ID = "wear_clock_ringing"
    const val EXTRA_KIND = "ring_kind"
    const val EXTRA_ID = "ring_id"
    const val EXTRA_LABEL = "ring_label"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ringing", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = "Alarms and timers ringing on this watch"
                        setBypassDnd(true)
                    },
            )
        }
    }

    fun show(context: Context, kind: RingKind, id: Long, label: String) {
        ensureChannel(context)
        val intent = Intent(context, WearRingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_KIND, kind.name)
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_LABEL, label)
        }
        val fullScreen = PendingIntent.getActivity(
            context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (kind == RingKind.ALARM) "Alarm" else "Timer finished")
            .setContentText(label.ifBlank { if (kind == RingKind.ALARM) "Alarm" else "Timer" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching { manager?.notify(id.toInt(), notification) }
        runCatching { context.startActivity(intent) }
    }

    fun dismiss(context: Context, id: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching { manager?.cancel(id.toInt()) }
    }
}
