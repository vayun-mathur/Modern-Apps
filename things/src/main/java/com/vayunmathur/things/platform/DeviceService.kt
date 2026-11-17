package com.vayunmathur.things.platform

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vayunmathur.library.util.ensureNotificationChannel
import com.vayunmathur.things.MainActivity
import com.vayunmathur.things.R

/**
 * Foreground service whose only job is to keep the process (and therefore the GATT connections
 * owned by [DeviceController]) alive while the Activity is stopped or swiped away, and to drive
 * background auto-reconnect. Modeled on findfamily's `LocationTrackingService`.
 */
class DeviceService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DeviceController.init(applicationContext)
        ensureNotificationChannel(
            CHANNEL_ID,
            getString(R.string.device_channel_name),
            NotificationManager.IMPORTANCE_LOW,
            getString(R.string.device_channel_desc),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        // A connectedDevice foreground start requires a Bluetooth permission; if it was revoked
        // since we were scheduled, satisfy the foreground-start contract with a type-less
        // startForeground and then stop, mirroring the location template.
        if (!DeviceController.hasBluetoothConnectPermission()) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (_: Exception) {
            }
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        DeviceController.autoConnectSavedDevices()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Last-resort cleanup when we're intentionally stopped (both devices forgotten).
        DeviceController.closeManagers()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.device_notification_title))
            .setContentText(getString(R.string.device_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Foreground-service channel/notification ids and start/stop helpers. */
    companion object {
        private const val CHANNEL_ID = "things_device_channel"
        private const val NOTIFICATION_ID = 201

        fun start(context: Context) {
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, DeviceService::class.java)
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, DeviceService::class.java)
            )
        }
    }
}
