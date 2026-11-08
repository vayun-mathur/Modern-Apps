package com.vayunmathur.emergency.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vayunmathur.emergency.R
import com.vayunmathur.emergency.receiver.SosActionReceiver

/**
 * The SOS countdown notification.
 *
 * Mirrors GrapheneOS's `EmergencyActionForegroundService.buildCountDownNotification`: an
 * ongoing, high-importance alarm-category row naming the number about to be dialled, with a
 * cancel action that fires `CANCEL_EMERGENCY_COUNTDOWN` at our own receiver. Unlike
 * GrapheneOS's RemoteViews chronometer layout, this uses the platform `NotificationCompat`
 * countdown (`setUsesChronometer` + `setChronometerCountDown`) so there is no custom layout
 * to maintain - the semantics (counting down to the call, one-tap cancel) are identical.
 *
 * Posted by the foreground service (which owns the channel); also used for the content
 * intent target so tapping the row reopens the SOS screen.
 */
object SosNotifications {

    const val CHANNEL_ID = "emergency_sos"
    const val NOTIFICATION_ID = 0x112

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sos_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.sos_channel_description)
            },
        )
    }

    fun countdown(
        context: Context,
        number: String,
        targetWallClockMillis: Long,
        contentIntent: PendingIntent,
    ): Notification {
        val cancel = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, SosActionReceiver::class.java)
                .setAction(SosActionReceiver.ACTION_CANCEL_COUNTDOWN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.sos_channel_name))
            .setContentText(context.getString(R.string.sos_notification_text, number))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(targetWallClockMillis)
            .addAction(
                android.R.drawable.ic_delete,
                context.getString(R.string.sos_cancel),
                cancel,
            )
            .build()
    }

    /** Posts or updates the countdown row; never throws (a missing notice must not kill SOS). */
    fun notify(context: Context, notification: Notification) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(NOTIFICATION_ID) }
    }
}
