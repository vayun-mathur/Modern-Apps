package com.vayunmathur.health.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.health.R
import com.vayunmathur.health.notifications.DOSE_RINGING_CHANNEL_ID
import com.vayunmathur.health.notifications.DOSE_REMINDER_NOTIFICATION_ID
import com.vayunmathur.health.notifications.DOSE_SERVICE_NOTIFICATION_ID
import com.vayunmathur.health.notifications.createDoseChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps a dose reminder sounding until it is dismissed.
 *
 * Modelled on `clock/.../service/AlarmSoundService.kt`, minus its per-alarm ringtone and gradual
 * volume, which medication reminders do not offer. The important inherited behaviour is the
 * handover: the insistent notification rings first, this takes over when it manages to start, and
 * the notification is only cancelled once sound is actually coming out — so a failure to play does
 * not leave a silent reminder.
 */
class DoseSoundService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var started = false

    @Volatile
    private var destroyed = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Most of the five-second startForegroundService deadline is gone before we get here, so
        // claim the contract immediately and do everything else after. Missing it gets the service
        // killed asynchronously, which the receiver can neither see nor catch.
        createDoseChannels(this)
        startForeground(
            DOSE_SERVICE_NOTIFICATION_ID,
            ongoingNotification(getString(R.string.dose_due_title)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun ongoingNotification(text: String): Notification =
        NotificationCompat.Builder(this, DOSE_RINGING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.dose_due_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_STICKY
        started = true

        val name = intent?.getStringExtra(EXTRA_MEDICATION_NAME).orEmpty()
        if (name.isNotEmpty()) {
            getSystemService(NotificationManager::class.java)
                ?.notify(DOSE_SERVICE_NOTIFICATION_ID, ongoingNotification(name))
        }

        scope.launch {
            // prepare() blocks, so it belongs off the main thread. Vibration starts first: a
            // ringtone that fails to resolve must not take the vibration down with it.
            withContext(Dispatchers.Main) { startVibration() }

            if (play()) {
                // Only now hand the ring over. Cancelling on entry would drop the safety net
                // during prepare() — the slow part, on exactly the hardware this exists for — and
                // drop it permanently if nothing plays, which is the one case it is for.
                getSystemService(NotificationManager::class.java)
                    ?.cancel(DOSE_REMINDER_NOTIFICATION_ID)
            } else {
                Log.error(TAG, "No ringtone played; leaving the insistent notification up")
            }
        }

        return START_STICKY
    }

    private fun play(): Boolean {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return false
        val playing = runCatching { start(uri) }
            .onFailure {
                Log.error(TAG, "Ringtone $uri failed to play", it)
                release()
            }
            .getOrDefault(false)
        // prepare() is not cancellable, so onDestroy can have come and gone while we were inside
        // it — releasing a player that did not exist yet and leaving this one with nothing alive
        // to stop it.
        if (playing && destroyed) {
            release()
            return false
        }
        return playing
    }

    private fun start(uri: Uri): Boolean {
        mediaPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, uri)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            isLooping = true
            prepare()
            start()
        }
        return true
    }

    private fun release() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    private fun startVibration() {
        vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator
        vibrator?.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, VIBRATION_STEP_MS, VIBRATION_STEP_MS),
                0,
            ),
        )
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        runCatching { mediaPlayer?.stop() }
        release()
        vibrator?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    /** Shared constants and helpers for DoseSoundService. */
    companion object {
        const val EXTRA_MEDICATION_NAME = "MEDICATION_NAME"
        private const val TAG = "DoseSoundService"
        private const val VIBRATION_STEP_MS = 500L
    }
}
