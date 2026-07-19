package com.vayunmathur.clock.util
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.vayunmathur.clock.R
import com.vayunmathur.clock.data.ClockDatabase
import com.vayunmathur.library.room.buildDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sentinel [com.vayunmathur.clock.data.Alarm.ringtoneUri] value meaning "no sound". */
const val RINGTONE_SILENT = "silent"

class AlarmSoundService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Create the channel (Safe to call multiple times)
        createNotificationChannels(this)

        // 2. Build and show notification FIRST
        val notification = NotificationCompat.Builder(this, "ALARM_CHANNEL_ID")
            .setSmallIcon(R.drawable.baseline_access_alarm_24)
            .setContentTitle(getString(R.string.alarm_ringing_notification_title))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .build()

        // 3. This is the "Contract" with the OS. Do this before MediaPlayer.
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        // 4. Now handle the hardware, using this alarm's per-alarm settings.
        if (!started) {
            started = true
            val alarmId = intent?.getLongExtra("ALARM_ID", -1L) ?: -1L
            scope.launch {
                val alarm = if (alarmId != -1L) {
                    runCatching {
                        buildDatabase<ClockDatabase>(useDeviceProtectedStorage = true)
                            .alarmDao().get(alarmId)
                    }.getOrNull()
                } else null

                val ringtoneUri = alarm?.ringtoneUri
                val vibrate = alarm?.vibrate ?: true
                val gradualSeconds = alarm?.gradualVolumeSeconds ?: 0

                // setDataSource()/prepare() are blocking; run them on the IO
                // dispatcher (this coroutine) instead of the main thread.
                if (ringtoneUri != RINGTONE_SILENT) {
                    playAlarm(resolveRingtone(ringtoneUri), gradualSeconds)
                }
                if (vibrate) {
                    withContext(Dispatchers.Main) { startVibration() }
                }
            }
        }

        return START_STICKY
    }

    private fun resolveRingtone(uriString: String?): Uri? = when (uriString) {
        null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        RINGTONE_SILENT -> null
        else -> runCatching { uriString.toUri() }.getOrNull()
    }

    private fun playAlarm(alarmUri: Uri?, gradualSeconds: Int) {
        alarmUri ?: return
        mediaPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, alarmUri)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            isLooping = true
            prepare()
            if (gradualSeconds > 0) setVolume(0f, 0f)
            start()
        }
        if (gradualSeconds > 0) rampVolume(gradualSeconds)
    }

    /** Fade the alarm in from silent to full over [seconds]. */
    private fun rampVolume(seconds: Int) {
        scope.launch {
            val steps = 20
            val stepDelay = (seconds * 1000L) / steps
            for (i in 1..steps) {
                val volume = i / steps.toFloat()
                withContext(Dispatchers.Main) {
                    runCatching { mediaPlayer?.setVolume(volume, volume) }
                }
                delay(stepDelay)
            }
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        val pattern = longArrayOf(0, 500, 500) // Off, On, Off
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    override fun onDestroy() {
        scope.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        vibrator?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
