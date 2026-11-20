package com.vayunmathur.files.platform
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.vayunmathur.files.MainActivity
import com.vayunmathur.files.R

/**
 * Shared foreground-notification plumbing for the zip/unzip workers. Progress updates are
 * throttled so the notification is only re-posted when the integer percentage changes.
 */
abstract class ProgressNotificationWorker(
    context: Context,
    params: WorkerParameters,
    private val channelId: String,
    private val notificationId: Int,
    @StringRes private val channelNameRes: Int,
    @StringRes private val contentTitleRes: Int,
) : CoroutineWorker(context, params) {

    private companion object {
        const val FULL_PROGRESS = 100
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var lastProgress = -1

    protected fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                channelId,
                applicationContext.getString(channelNameRes),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(progress: Int): android.app.Notification {
        // Tap opens the app: the ongoing foreground notification was a dead tap before.
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(applicationContext.getString(contentTitleRes))
            .setContentIntent(contentIntent)
            .setSmallIcon(R.drawable.folder_24px)
            .setProgress(FULL_PROGRESS, progress, false)
            .setOngoing(true)
            .build()
    }

    protected fun createForegroundInfo(progress: Int) =
        ForegroundInfo(
            notificationId,
            buildNotification(progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

    protected fun updateProgress(current: Long, total: Long) {
        if (total <= 0) return
        val progress = (current * FULL_PROGRESS / total).toInt()
        if (progress != lastProgress) {
            lastProgress = progress
            try {
                notificationManager.notify(notificationId, buildNotification(progress))
            } catch (_: IllegalArgumentException) {
            } catch (_: SecurityException) {
            }
        }
    }

    protected fun cancelNotification() {
        try {
            notificationManager.cancel(notificationId)
        } catch (_: IllegalArgumentException) {
        } catch (_: SecurityException) {
        }
    }
}
