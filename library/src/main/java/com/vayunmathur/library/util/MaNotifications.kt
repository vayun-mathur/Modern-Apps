package com.vayunmathur.library.util

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Shared notification-posting shell: channel registration plus a
 * `NotificationCompat` builder with the repo-standard `FLAG_IMMUTABLE`
 * content intent.
 *
 * Apps used to repeat the same `ensureChannel` (with a harmful
 * `getNotificationChannel != null` early-return that froze channel names —
 * see [ensureNotificationChannel]) plus the same `PendingIntent.getActivity`
 * with `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE` and the same
 * `areNotificationsEnabled` / `SecurityException` guards. That shell lives
 * here now; an app keeps only its channel IDs, strings, icons, actions and
 * styles — like email's per-account channels and sort keys or clock's alarm
 * channels — by passing them into [post].
 *
 * For channel registration alone (channels the app posts elsewhere, e.g.
 * from a service), use [ensureNotificationChannel] directly.
 */
object MaNotifications {
    /**
     * Register [channelId] and post one notification built by [build].
     *
     * The [build] lambda receives a `NotificationCompat.Builder` already bound
     * to [channelId] with the content intent set to launch [launchIntent]
     * (or no content intent when null). Returns the posted notification id,
     * or null when notifications are disabled or the post was denied.
     */
    fun post(
        context: Context,
        channelId: String,
        channelName: String,
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
        channelDescription: String? = null,
        notificationId: Int,
        launchIntent: Intent? = null,
        requestCode: Int = notificationId,
        build: (NotificationCompat.Builder) -> NotificationCompat.Builder,
    ): Int? {
        context.ensureNotificationChannel(
            channelId,
            channelName,
            importance,
            channelDescription,
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return null
        // POST_NOTIFICATIONS only exists on API 33+; below that the
        // areNotificationsEnabled check above is the whole gate, and asking
        // for a permission the platform does not know would deny everything.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
        }
        var builder = NotificationCompat.Builder(context, channelId)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            builder = builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    requestCode,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        val notification: Notification = try {
            build(builder).build()
        } catch (_: Exception) {
            return null
        }
        return try {
            manager.notify(notificationId, notification)
            notificationId
        } catch (_: SecurityException) {
            // Pre-permission grant on Android 13+ — silently drop.
            null
        }
    }

    /** Broadcast `PendingIntent` with the repo-standard immutable flags. */
    fun broadcastIntent(
        context: Context,
        intent: Intent,
        requestCode: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
