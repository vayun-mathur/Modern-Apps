package com.vayunmathur.madeveloper.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vayunmathur.madeveloper.MainActivity
import com.vayunmathur.madeveloper.domain.PendingInstall

/** Notifications for server-pushed installs; the install itself always needs a tap. */
object InstallNotifications {
    private const val CHANNEL = "push-installs"

    /** Tells the user a build was pushed and is downloading. */
    fun showPending(context: Context, install: PendingInstall) =
        notify(context, install, "Dev build pushed", install.pkg + " is downloading")

    /** Tells the user a verified build is ready; tapping opens the app to confirm. */
    fun showReady(context: Context, install: PendingInstall) =
        notify(context, install, "Dev build ready to install", "Tap to install " + install.pkg)

    private fun notify(context: Context, install: PendingInstall, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL, "Pushed installs", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(context, install.pkg.hashCode(), intent, flags)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setContentIntent(pending).setAutoCancel(true).build()
        manager.notify(install.pkg.hashCode(), notification)
    }
}
