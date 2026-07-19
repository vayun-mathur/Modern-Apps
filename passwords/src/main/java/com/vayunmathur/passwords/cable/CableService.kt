package com.vayunmathur.passwords.cable

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.passwords.data.PasswordDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service (type `connectedDevice`) that runs a caBLE authenticator [CableSession].
 * Mirrors the FGS setup in `openassistant/util/InferenceService`. The DB must already be unlocked
 * by [CableActivity]; here it is opened via [buildDatabase] using the cached passphrase.
 *
 * The service stops itself once the session finishes (success, cancel, or the overall timeout).
 */
class CableService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.getStringExtra(EXTRA_FIDO_URI)
        val userVerified = intent?.getBooleanExtra(EXTRA_USER_VERIFIED, false) ?: false
        if (uri == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        scope.launch {
            val ok = runCatching {
                val qr = CableQrData.parse(uri)
                val dao = applicationContext.buildDatabase<PasswordDatabase>().passkeyDao()
                val processor = CtapProcessor(dao, userVerified)
                val advertiser = CableAdvertiser(applicationContext)
                val session = CableSession(qr, processor, advertiser) { updateNotification(it) }
                withTimeoutOrNull(SESSION_TIMEOUT_MS) { session.run() } ?: false
            }.getOrElse {
                Log.e(TAG, "caBLE session failed", it)
                false
            }
            Log.d(TAG, "caBLE session finished, success=$ok")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cross-device sign-in", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Starting cross-device sign-in"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Passwords")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FIDO_URI = "fido_uri"
        const val EXTRA_USER_VERIFIED = "user_verified"

        private const val TAG = "CableService"
        private const val CHANNEL_ID = "cable_service"
        private const val NOTIFICATION_ID = 42
        private const val SESSION_TIMEOUT_MS = 120_000L
    }
}
