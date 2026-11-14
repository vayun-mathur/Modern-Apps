package com.vayunmathur.cast.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import com.vayunmathur.cast.MainActivity
import com.vayunmathur.cast.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.domain.ClientState
import com.vayunmathur.cast.platform.CastController
import com.vayunmathur.cast.platform.MirrorPhase
import com.vayunmathur.cast.platform.disconnect
import com.vayunmathur.cast.platform.startMirroring
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "CastService"

/**
 * `IMPORTANCE_LOW`: an open cast session is a status indicator, not an event, so it must never
 * make a sound. There is nothing else on this channel for a user who silences it to lose.
 */
private const val CHANNEL_ID = "cast_session"

private const val NOTIF_ID = 4201

/** Request code for the notification's content intent; distinct from the stop action's. */
private const val REQUEST_OPEN = 0
private const val REQUEST_STOP = 1

/**
 * Keeps the cast session alive while the app is not in front, and owns the screen-capture
 * projection.
 *
 * The projection lives here rather than in the controller because of the Android 14+ ordering rule:
 * a `mediaProjection`-typed foreground service must already be in the foreground *before*
 * `getMediaProjection()` is called. So [onStartCommand] calls `startForeground` first, every time,
 * and only then turns the consent token into a projection.
 *
 * `START_NOT_STICKY` rather than sticky: a restarted service has nothing to restore. The TLS channel
 * died with the process, the receiver has already dropped it, and the consent token was single-use.
 *
 * Stop always routes through [ACTION_STOP] rather than `stopService` from outside, so the three
 * sources - the tile, the notification action and the system's own "Stop sharing" chip - all take
 * the same path. That is `ShareReceiveController`'s discipline.
 */
class CastService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Guards against a second onStartCommand registering the status collector twice. */
    private var collecting = false

    private var projection: MediaProjection? = null

    /**
     * The system "Stop sharing" chip and a revoked projection both land here.
     *
     * Registered *before* `createVirtualDisplay`, which Android 14+ requires, and which also means
     * this is the only reliable notice that the user stopped sharing from outside the app.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.status(TAG, "the projection was stopped from outside the app")
            CastController.stopMirroring(this@CastService)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(
            CHANNEL_ID,
            getString(R.string.cast_notification_channel_name),
            importance = NotificationManager.IMPORTANCE_LOW,
            description = getString(R.string.cast_notification_channel_desc),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before anything else: this is started with startForegroundService, and the platform kills
        // a process that does not honour that within a few seconds. The type must be
        // mediaProjection both when a projection is about to be obtained and whenever one is
        // already held - downgrading to mediaPlayback under a live VirtualDisplay is what gets a
        // capturing service killed.
        val needsProjectionType =
            intent?.action == ACTION_START_MIRRORING || projection != null
        enterForeground(withProjection = needsProjectionType)
        when (intent?.action) {
            ACTION_STOP -> {
                CastController.disconnect(this)
                return START_NOT_STICKY
            }
            ACTION_STOP_MIRRORING -> {
                releaseProjection()
                // No projection is held any more, so the service should stop claiming that type.
                enterForeground(withProjection = false)
                return START_NOT_STICKY
            }
            ACTION_START_MIRRORING -> startMirroringFrom(intent)
        }
        if (!collecting) {
            collecting = true
            // One collector for the lifetime of the service, so the notification tracks the session
            // instead of freezing on whatever was true when it started.
            scope.launch {
                CastController.device
                    .combine(CastController.sessionState) { device: CastDevice?, state: ClientState ->
                        device to state
                    }
                    .combine(CastController.mirrorPhase) { pair: Pair<CastDevice?, ClientState>, mirror: MirrorPhase ->
                        Triple(pair.first, pair.second, mirror)
                    }
                    .collect { (device, state, mirror) ->
                        if (device == null) return@collect
                        getSystemService<NotificationManager>()
                            ?.notify(
                                NOTIF_ID,
                                buildNotification(device.friendlyName, statusText(state.phase, mirror)),
                            )
                    }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Turn the consent token into a projection, in the one order the platform allows.
     *
     * `startForeground` has already happened in [onStartCommand]; the callback is registered before
     * anything is created from the projection, which Android 14+ enforces.
     */
    private fun startMirroringFrom(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, RESULT_CANCELED_FALLBACK)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) {
            Log.status(TAG, "no consent token in the mirroring request")
            demoteFromProjection()
            return
        }
        val manager = getSystemService<MediaProjectionManager>()
        val granted = try {
            manager?.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            // The usual cause is calling this before the service was genuinely in the foreground.
            Log.status(TAG, "getMediaProjection refused", e)
            null
        } catch (e: IllegalStateException) {
            Log.status(TAG, "getMediaProjection refused", e)
            null
        }
        if (granted == null) {
            Log.status(TAG, "no projection")
            demoteFromProjection()
            return
        }
        releaseProjection()
        granted.registerCallback(projectionCallback, null)
        projection = granted
        CastController.startMirroring(this, granted)
    }

    /**
     * Drop back to `mediaPlayback` after failing to obtain a projection.
     *
     * Staying typed `mediaProjection` with no projection to show for it is the state the platform
     * objects to, so it is worth correcting rather than leaving until the next start command.
     */
    private fun demoteFromProjection() {
        if (projection == null) enterForeground(withProjection = false)
    }

    private fun releaseProjection() {
        val active = projection ?: return
        runCatching { active.unregisterCallback(projectionCallback) }
        runCatching { active.stop() }
        projection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseProjection()
        scope.cancel()
    }

    /**
     * Android 15's cumulative-runtime timeout.
     *
     * Checked rather than assumed: the cap applies to `dataSync` and `mediaProcessing` only, so this
     * should never fire for either type used here. Kept because if a future platform version does
     * cap them, tearing the session down cleanly beats being killed with a socket open.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.status(TAG, "foreground service timed out (type $fgsType)")
        CastController.disconnect(this)
    }

    /**
     * [withProjection] switches the declared type to `mediaProjection`, which is the type that has
     * to be in force before `getMediaProjection()` is called. Until there is a projection to hold,
     * `mediaPlayback` is the honest description and does not require one.
     */
    private fun enterForeground(withProjection: Boolean) {
        val device = CastController.device.value
        val notification = buildNotification(
            device?.friendlyName ?: getString(R.string.app_name),
            getString(R.string.cast_notification_text_connecting),
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val type = if (withProjection) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
                startForeground(NOTIF_ID, notification, type)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // A background start the platform refuses. Nothing to recover: the session itself is
            // unaffected, it just will not survive the app being backgrounded.
            Log.status(TAG, "could not enter the foreground", e)
        } catch (e: SecurityException) {
            Log.status(TAG, "could not enter the foreground", e)
        }
    }

    private fun statusText(phase: ClientPhase, mirror: MirrorPhase): String = when {
        mirror == MirrorPhase.Mirroring -> getString(R.string.cast_status_mirroring)
        mirror == MirrorPhase.Negotiating -> getString(R.string.cast_notification_text_starting)
        mirror == MirrorPhase.Failed -> getString(R.string.cast_notification_text_failed)
        phase == ClientPhase.Connecting -> getString(R.string.cast_notification_text_connecting)
        // Worth its own line: the user has to walk over and read the TV, and a notification that just
        // said "connecting" would not tell them that.
        phase == ClientPhase.AwaitingCode -> getString(R.string.cast_notification_text_pairing)
        phase == ClientPhase.Paired -> getString(R.string.cast_status_ready)
        phase == ClientPhase.Failed -> getString(R.string.cast_notification_text_failed)
        else -> getString(R.string.cast_status_ready)
    }

    private fun buildNotification(deviceName: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.cast_notification_title, deviceName))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_cast, getString(UiR.string.stop), stop)
            .build()
    }

        /** Session actions and start/stop helpers for the cast service. */
    companion object {
        /** Ends the whole session, from the notification's own action or the tile. */
        const val ACTION_STOP = "com.vayunmathur.cast.action.STOP"

        private const val ACTION_START_MIRRORING = "com.vayunmathur.cast.action.START_MIRRORING"
        private const val ACTION_STOP_MIRRORING = "com.vayunmathur.cast.action.STOP_MIRRORING"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        /** Not `Activity.RESULT_CANCELED` only to avoid depending on the activity class here. */
        private const val RESULT_CANCELED_FALLBACK = 0

        fun start(context: Context) {
            launch(context, Intent(context, CastService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastService::class.java))
        }

        /** Called by `MirrorConsentActivity` with a freshly granted, single-use token. */
        fun startMirroring(context: Context, resultCode: Int, data: Intent) {
            launch(
                context,
                Intent(context, CastService::class.java)
                    .setAction(ACTION_START_MIRRORING)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
        }

        fun stopMirroring(context: Context) {
            launch(
                context,
                Intent(context, CastService::class.java).setAction(ACTION_STOP_MIRRORING),
            )
        }

        private fun launch(context: Context, intent: Intent) {
            try {
                context.startForegroundService(intent)
            } catch (_: ForegroundServiceStartNotAllowedException) {
                // Refused because the app is in the background with no exemption. The session
                // still works; it just will not outlive the Activity.
            } catch (_: SecurityException) {
                // The service or permission is missing from the manifest.
            }
        }
    }
}
