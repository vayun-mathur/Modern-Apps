package com.vayunmathur.euicc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.vayunmathur.euicc.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground lifetime guard for the BPP install phase.
 *
 * Streaming a Bound Profile Package into the eUICC takes minutes on slow
 * links; without foreground importance the process is killable while the
 * eUICC is mid-write, which is exactly the half-installed state
 * `DownloadScreen` swallows Back to avoid. This service holds a partial wake
 * lock and a `shortService` foreground notification for the duration of the
 * install, mirroring OpenEUICC's
 * `EuiccChannelManagerService.launchProfileDownloadTask` (single-task guard,
 * wake lock, `shortService`).
 *
 * It performs no protocol work itself: the [com.vayunmathur.euicc.platform.EuiccViewModel]
 * still drives `nativeFinishDownload` (and so survives rotation), and starts
 * this service just before the install and stops it right after. If the
 * service is somehow already running, the second start is a no-op.
 */
class DownloadService : Service() {
    private val running = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_GUARD || !running.compareAndSet(false, true)) {
            return START_NOT_STICKY
        }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            // Absolute backstop: a stuck install must not pin the CPU forever.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.installing_title))
            .setContentText(getString(R.string.installing_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(PROGRESS_MAX, PROGRESS_CURRENT, true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(FOREGROUND_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.installing_title),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    /** Notification and wake-lock constants plus the start/stop entry points for the install guard. */
    companion object {
        private const val CHANNEL_ID = "esim_install"
        private const val FOREGROUND_ID = 1001
        private const val WAKE_LOCK_TAG = "euicc:install"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val ACTION_GUARD = "com.vayunmathur.euicc.action.GUARD_INSTALL"

        /** Indeterminate install progress: max and current are both zero. */
        private const val PROGRESS_MAX = 0

        /** Indeterminate install progress: max and current are both zero. */
        private const val PROGRESS_CURRENT = 0

        /** Starts the install guard (no-op when already running). */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadService::class.java).setAction(ACTION_GUARD))
        }

        /** Releases the install guard. */
        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}
