package com.vayunmathur.clock.service
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
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.vayunmathur.clock.R
import com.vayunmathur.clock.platform.ALARM_CHANNEL_ID
import com.vayunmathur.clock.platform.ALARM_RING_NOTIFICATION_ID
import com.vayunmathur.clock.platform.createAlarmChannel
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.RINGTONE_SILENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Foreground service that plays the ringing alarm's ringtone and vibration until dismissed, snoozed, or timed out. */
class AlarmSoundService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var ringingForAlarmId: Long = -1L

    /** Whether the MediaPlayer is actually producing sound, so the ring notification is spare. */
    @Volatile
    private var sounding = false

    @Volatile
    private var destroyed = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // The five-second startForegroundService deadline is mostly spent before we get here -
        // process fork, class loading, Application.onCreate - so claim the contract immediately
        // and do everything else afterwards. Missing it gets the service killed asynchronously,
        // which the receiver cannot see or catch.
        startForeground(
            NOTIFICATION_ID,
            ongoingNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        // AlarmReceiver creates the channels before starting us, so this is only belt and
        // braces for a start no receiver preceded (none is expected: the service is NOT_STICKY).
        createAlarmChannel(this)
    }

    private fun ongoingNotification(): Notification =
        NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_access_alarm_24)
            .setContentTitle(getString(R.string.alarm_ringing_notification_title))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getLongExtra("ALARM_ID", -1L) ?: -1L

        if (alarmId == -1L) {
            // No alarm to ring for. The only intent-less start is the system restarting a
            // service it killed mid-ring; resurrecting here re-rings after dismiss (#729)
            // with no activity left to stop it, and plays the default sound even for
            // vibrate-only/silent alarms. Stay dead instead.
            stopSelf()
            return START_NOT_STICKY
        }

        if (ringingForAlarmId != -1L && ringingForAlarmId != alarmId) {
            // A second alarm has fired while the first is still ringing. The receiver
            // already stopped this service before posting the new ring, but the stop
            // is asynchronous (onDestroy runs on the main thread) while this start
            // can land first; without the guard both alarms play on top of each other.
            // The old ring loses: the ringing activity only ever shows the latest.
            stopRingLocked()
        }
        ringingForAlarmId = alarmId
        sounding = false

        scope.launch {
            val alarm = runCatching {
                ClockRepository.get(applicationContext).getAlarm(alarmId)
            }.getOrNull()

            val ringtoneUri = alarm?.ringtoneUri
            val vibrate = alarm?.vibrate ?: true
            val gradualSeconds = alarm?.gradualVolumeSeconds ?: 0

            // Loading and preparing the player are blocking work, so they run
            // on the IO dispatcher (this coroutine) instead of the main thread.
            //
            // Vibration comes first: a stored ringtone can be a content:// URI owned by
            // another app, and one that no longer resolves used to throw out of playAlarm
            // and take the vibration down with it.
            if (vibrate) {
                withContext(Dispatchers.Main) { startVibration() }
            }

            val silent = ringtoneUri == RINGTONE_SILENT
            sounding = !silent && (
                tryPlay(resolveRingtone(ringtoneUri), gradualSeconds, alarmId) ||
                    tryPlay(resolveRingtone(null), gradualSeconds, alarmId)
                )

            // Only now hand the ring over. Cancelling on entry to onStartCommand instead would
            // drop the safety net during the database read and prepare() above - the slow part,
            // on exactly the hardware this is meant to fix - and drop it permanently if neither
            // ringtone plays, which is the one case it exists for.
            //
            // A gradual alarm's notification was posted silent and only becomes spare once
            // the fade actually starts; cancelling it unconditionally would leave a slow
            // prepare() with no sound at all. The receiver made that notification
            // insistent when the service was unlikely to start, so only cancel there.
            if (sounding) {
                cancelRingNotification()
                // The looping player is the ring now: bound it like the notification,
                // which times out after ten minutes. Otherwise a missed alarm plays
                // until the process dies or the battery does.
                scheduleAutoStop(alarmId, RING_TIMEOUT_MS)
            } else if (!silent) {
                Log.error(TAG, "Alarm $alarmId: no ringtone played; leaving the ring notification up")
            }
        }

        // NOT_STICKY: a service killed mid-ring must stay dead. The insistent ring
        // notification is system-side and keeps sounding without us; a sticky restart
        // would ring again after dismiss with nothing able to stop it (#729).
        return START_NOT_STICKY
    }

    private fun tryPlay(uri: Uri?, gradualSeconds: Int, alarmId: Long): Boolean {
        val playing = runCatching { playAlarm(uri, gradualSeconds) }
            .onFailure {
                Log.error(TAG, "Alarm $alarmId: ringtone $uri failed to play", it)
                releasePlayer()
            }
            .getOrDefault(false)
        // prepare() blocks and is not cancellable, so onDestroy can have come and gone while we
        // were inside it - releasing a player that did not exist yet and leaving this one with
        // nothing left alive to stop it.
        if (playing && destroyed) {
            releasePlayer()
            return false
        }
        return playing
    }

    private fun cancelRingNotification() {
        getSystemService(NotificationManager::class.java).cancel(ALARM_RING_NOTIFICATION_ID)
    }

    /** Stops the ring after [timeoutMs] if nobody dismissed it first. */
    private fun scheduleAutoStop(alarmId: Long, timeoutMs: Long) {
        scope.launch {
            delay(timeoutMs)
            // Still the same ring: a newer alarm re-arms its own deadline.
            if (ringingForAlarmId == alarmId) {
                Log.status(TAG, "Alarm $alarmId: ringing timed out; stopping")
                stopSelf()
            }
        }
    }

    private fun resolveRingtone(uriString: String?): Uri? = when (uriString) {
        null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        RINGTONE_SILENT -> null
        else -> runCatching { uriString.toUri() }.getOrNull()
    }

    private fun playAlarm(alarmUri: Uri?, gradualSeconds: Int): Boolean {
        alarmUri ?: return false
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
        return true
    }

    private fun releasePlayer() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
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
        vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator
        val pattern = longArrayOf(0, 500, 500) // Off, On, Off
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    /** Synchronous stop of the current ring, for the overlapping-alarm handover. */
    private fun stopRingLocked() {
        runCatching { mediaPlayer?.stop() }
        releasePlayer()
        runCatching { vibrator?.cancel() }
        vibrator = null
        sounding = false
    }

    override fun onDestroy() {
        destroyed = true
        ringingForAlarmId = -1L
        scope.cancel()
        // stop() throws IllegalStateException unless prepared/started; an unguarded call
        // would skip the vibrator cancel and super.onDestroy() below, wedging vibration on.
        runCatching { mediaPlayer?.stop() }
        releasePlayer()
        runCatching { vibrator?.cancel() }
        vibrator = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

        /** Notification id and ring timeout shared with the alarm receiver. */
    companion object {
        const val NOTIFICATION_ID = 1001

        /**
         * Matches the receiver's bound: the insistent notification gives up after
         * ten minutes, so the looping player must not outlive it.
         */
        const val RING_TIMEOUT_MS = 10 * 60 * 1000L
        private const val TAG = "AlarmSoundService"
    }
}
