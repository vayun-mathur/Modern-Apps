package com.vayunmathur.cast.tv.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.vayunmathur.cast.tv.MainActivity
import com.vayunmathur.cast.tv.R
import com.vayunmathur.cast.tv.platform.ReceiverController
import com.vayunmathur.cast.tv.platform.ReceiverPhase
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "ReceiverService"

/**
 * `IMPORTANCE_LOW`: being available to cast to is a status, not an event, so it must never make a
 * sound - least of all on a TV in a living room.
 */
private const val CHANNEL_ID = "cast_tv_receiver"

private const val NOTIF_ID = 4301

/** The high-importance channel whose full-screen intent opens the TV on its own. */
private const val INCOMING_CHANNEL_ID = "cast_tv_incoming"

private const val INCOMING_NOTIF_ID = 4302

/** `Build.VERSION_CODES.UPSIDE_DOWN_CAKE`: `startForeground` takes a service type from API 34. */
private const val MIN_SERVICE_TYPE_SDK = 34

/**
 * Keeps this TV listening while nothing is on screen.
 *
 * A receiver that stops advertising when its Activity goes away is not a receiver: the user opens the
 * app once and then expects the TV to be in the phone's list, including after the launcher has moved
 * on. `mediaPlayback` is the honest foreground type - the service holds two sockets and decodes a
 * stream - and it is not subject to Android 15's cumulative-runtime cap, which covers only `dataSync`
 * and `mediaProcessing`.
 *
 * `START_STICKY`, unlike `:cast`'s service: there is genuinely something to restore here. A restarted
 * receiver just re-advertises and waits, with no consent token or single-use permission involved.
 *
 * The service also opens the app itself when a phone connects while nothing is on screen. A direct
 * start is tried first and a full-screen intent is posted alongside it, the same pairing `clock`'s
 * `AlarmReceiver` uses: the direct start only survives when an exemption holds, and the intent is
 * what actually brings the TV forward from the background. Without either, a pairing code would sit
 * unseen in state until its socket timed out.
 */
class ReceiverService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(
            CHANNEL_ID,
            getString(R.string.tv_notification_channel_name),
            importance = NotificationManager.IMPORTANCE_LOW,
            description = getString(R.string.tv_notification_channel_desc),
        )
        // Silent: appearing on screen is the alert, least of all in a living room. High importance
        // is what lets the full-screen intent below fire; the silence is what keeps it from ringing.
        ensureNotificationChannel(
            INCOMING_CHANNEL_ID,
            getString(R.string.tv_incoming_channel_name),
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = getString(R.string.tv_incoming_channel_desc),
            configure = {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // First, because this is started with startForegroundService and the platform kills a process
        // that does not honour that within a few seconds.
        enterForeground()
        if (intent?.action == ACTION_STOP) {
            ReceiverController.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        ReceiverController.start(this)
        if (!collecting) {
            collecting = true
            scope.launch {
                var wasIncoming = false
                ReceiverController.state.collect { state ->
                    getSystemService<NotificationManager>()
                        ?.notify(NOTIF_ID, buildNotification(statusText(state.phase)))
                    // The idle-to-active edge only: Pairing -> Connected -> Mirroring is one
                    // arrival, not three, and refiring on each would stack intents for no reason.
                    val incoming = state.phase.isIncoming
                    if (incoming && !wasIncoming) bringToFront(statusText(state.phase))
                    // Done once the picture is up - MainActivity moves on to MirrorActivity on its
                    // own - or when the session ends. In between it stays as a tap-to-open backup
                    // for a TV whose full-screen-intent access was never granted.
                    if (!incoming || state.phase is ReceiverPhase.Mirroring) cancelIncoming()
                    wasIncoming = incoming
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ReceiverController.stop()
        scope.cancel()
    }

    /**
     * Android 15's cumulative-runtime timeout.
     *
     * Checked rather than assumed: the cap applies to `dataSync` and `mediaProcessing` only, so this
     * should never fire. Kept because if a future platform version does cap `mediaPlayback`, closing
     * the sockets cleanly beats being killed with a phone mid-stream.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.status(TAG, "foreground service timed out (type $fgsType)")
        ReceiverController.stop()
        stopSelf()
    }

    private fun enterForeground() {
        val notification = buildNotification(getString(R.string.tv_ready_title))
        try {
            if (Build.VERSION.SDK_INT >= MIN_SERVICE_TYPE_SDK) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: SecurityException) {
            // A background start the platform refuses. The receiver still works; it just will not
            // outlive the Activity.
            Log.status(TAG, "could not enter the foreground", e)
        } catch (e: IllegalStateException) {
            // Foreground-service start not allowed from the background on newer platforms.
            Log.status(TAG, "could not enter the foreground", e)
        } catch (e: IllegalArgumentException) {
            // Bad notification or service type on this device.
            Log.status(TAG, "could not enter the foreground", e)
        }
    }

    private fun statusText(phase: ReceiverPhase): String = when (phase) {
        is ReceiverPhase.Pairing -> getString(R.string.tv_notification_text_pairing)
        is ReceiverPhase.Connected -> getString(R.string.tv_paired, phase.senderName)
        is ReceiverPhase.Mirroring ->
            getString(R.string.tv_mirroring, phase.sourceName)
        else -> getString(R.string.tv_ready_title)
    }

    /**
     * Open the TV itself for an incoming phone, from the background.
     *
     * Always lands on `MainActivity` rather than going straight to the picture: an unknown phone
     * needs its pair code on screen first, and a known one passes through `Connected` so briefly
     * that aiming past it would show the mirror a frame before its surface exists. `MainActivity`
     * already moves on to `MirrorActivity` the moment frames arrive, so landing there covers both.
     *
     * `singleTop` + `clearTop` rather than a fresh task: when the idle screen is already up this
     * reuses it instead of stacking a second copy above the session it describes.
     */
    private fun bringToFront(text: String) {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // Best effort: allowed when an exemption holds, refused from the background otherwise.
        // The full-screen intent below is what covers that case, so a refusal is expected noise.
        runCatching { startActivity(open) }
            .onFailure { Log.status(TAG, "background activity start refused; relying on full-screen intent", it) }
        postIncoming(text, open)
    }

    /**
     * The notification that actually opens the TV from the background.
     *
     * A foreground service alone does not exempt a background activity start, so this is the
     * mechanism rather than the fallback. On Android 14+ it additionally needs the user to have
     * granted full-screen-intent access in Settings; without it the notification still lands in
     * the shade with a tap-to-open, and the miss is logged rather than retried.
     */
    private fun postIncoming(text: String, open: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            getSystemService<NotificationManager>()?.canUseFullScreenIntent() == false
        ) {
            Log.status(TAG, "USE_FULL_SCREEN_INTENT not granted; the TV may not open on its own")
        }
        val fullScreen = PendingIntent.getActivity(
            this,
            INCOMING_NOTIF_ID,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, INCOMING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_tv)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService<NotificationManager>()?.notify(INCOMING_NOTIF_ID, notification)
        }.onFailure { Log.status(TAG, "could not post the incoming notification", it) }
    }

    private fun cancelIncoming() {
        runCatching { getSystemService<NotificationManager>()?.cancel(INCOMING_NOTIF_ID) }
    }

    /** A phone is arriving or here: pairing, paired, or streaming. Anything else is idle. */
    private val ReceiverPhase.isIncoming: Boolean
        get() = this is ReceiverPhase.Pairing ||
            this is ReceiverPhase.Connected ||
            this is ReceiverPhase.Mirroring

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_tv)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    /** Service actions and the entry point other components use to start the receiver. */
    companion object {
        const val ACTION_STOP = "com.vayunmathur.cast.tv.action.STOP"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ReceiverService::class.java))
            } catch (_: IllegalStateException) {
                // Refused because the app is in the background with no exemption. The Activity's own
                // session still works; it just will not outlive it.
            } catch (_: SecurityException) {
                // Foreground-service start refused by the platform. Same fallback as above.
            }
        }
    }
}
