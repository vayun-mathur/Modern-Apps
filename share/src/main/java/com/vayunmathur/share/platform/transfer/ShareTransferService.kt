package com.vayunmathur.share.platform.transfer

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
import com.vayunmathur.library.util.deleteNotificationChannel
import com.vayunmathur.library.util.ensureNotificationChannel
import com.vayunmathur.share.MainActivity
import com.vayunmathur.share.R
import com.vayunmathur.share.platform.receive.ShareReceiveController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ShareTransferSvc"

/**
 * The service's own channel, and nothing else's.
 *
 * `IMPORTANCE_LOW` so the always-on "Ready to receive" notification never makes a sound — it is
 * a status indicator, not an event. Every notification the user is meant to *react* to lives on
 * `ShareReceiveNotifier`'s own `IMPORTANCE_MAX` channels, so silencing this one in settings does
 * not silence incoming transfer requests.
 */
private const val CHANNEL_ID = "share_service"

/**
 * The id this channel used to have, when the notifier shared it.
 *
 * Sharing it meant progress and results inherited `IMPORTANCE_LOW` and never left the shade, and
 * that the channel's name flip-flopped depending on which of the two registered it last. Deleted
 * rather than reused, because a channel's importance is fixed at creation.
 */
private const val CHANNEL_LEGACY = "share_transfer"

private const val NOTIF_ID = 4101

/**
 * Foreground service that keeps `:share` receivable while the app is not open.
 *
 * A lifecycle host with **zero session state**: [ShareReceiveController] owns the sockets, the
 * native sessions and the notifier, so this can be recreated or restarted at any time without
 * anything being lost. `START_STICKY` plus a null-Intent restart that just reconciles against
 * the persisted flag is what makes that safe.
 *
 * Foreground-service type is `connectedDevice`, not `dataSync`. `:vpn`'s manifest documents the
 * reason first-hand: Android 15 caps `dataSync` at six cumulative hours per 24 h, then calls
 * [onTimeout] and stops the service — unacceptable for an always-on receiver.
 * `connectedDevice` is semantically accurate (BLE advertising plus a local peer link), is not
 * capped, and is not on Android 13/14's list of types that a `BOOT_COMPLETED` receiver may not
 * start, so [ShareBootReceiver] can bring it up. Its permission prerequisite is satisfied by
 * `CHANGE_WIFI_STATE`, which is a normal permission, so it cannot fail for want of a runtime
 * Bluetooth grant.
 */
class ShareTransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** So two overlapping stop requests cannot both sit waiting on [ShareReceiveController.awaitIdle]. */
    private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        deleteNotificationChannel(CHANNEL_LEGACY)
        ensureNotificationChannel(
            CHANNEL_ID,
            getString(R.string.share_notification_channel_name),
            importance = NotificationManager.IMPORTANCE_LOW,
            description = getString(R.string.share_notification_channel_desc),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enter the foreground before anything else: this service is normally started with
        // startForegroundService, and the platform kills a process that does not honour that
        // within a few seconds regardless of which action it was handling.
        val action = intent?.action
        enterForeground(isSending = action == ACTION_START_SEND)
        when (action) {
            ACTION_START_SEND -> Unit
            // Everything else touches sockets or DataStore. `onStartCommand` runs on the main
            // thread and `acceptIncoming` flushes the Sharing ACCEPT frame down the socket, so
            // none of this may be done inline.
            ACTION_START_RECEIVE -> scope.launch { ShareReceiveController.start(this@ShareTransferService) }
            ACTION_ACCEPT, ACTION_REJECT -> {
                val handle = intent.getLongExtra(EXTRA_SESSION_HANDLE, 0L)
                val accept = action == ACTION_ACCEPT
                scope.launch {
                    val rc = ShareReceiveController.acceptIncoming(this@ShareTransferService, handle, accept)
                    if (rc < 0) Log.status(TAG, "accept($accept) on $handle failed rc=$rc")
                    if (!accept) stopIfNotNeeded()
                }
            }
            ACTION_CANCEL -> {
                val handle = intent.getLongExtra(EXTRA_SESSION_HANDLE, 0L)
                scope.launch {
                    ShareReceiveController.cancel(this@ShareTransferService, handle)
                    stopIfNotNeeded()
                }
            }
            ACTION_TURN_OFF -> scope.launch {
                // The user's own "Turn off": flip the persisted flag so the Quick Settings tile
                // agrees, and let syncServiceState issue the ACTION_STOP that follows.
                ShareReceiveController.setReceiveEnabled(this@ShareTransferService, false)
            }
            ACTION_STOP -> scope.launch { stopIfNotNeeded() }
            else -> {
                // START_STICKY restart, or a start with no action: the persisted flag decides.
                scope.launch { ShareReceiveController.syncServiceState(this@ShareTransferService) }
            }
        }
        return START_STICKY
    }

    /**
     * Stop, unless receiving is still wanted or a transfer is still running.
     *
     * Two separate reasons to stay up. Receiving being wanted is the obvious one — a finished
     * *send* must not take the receiver down with it. The other is that toggling receiving off
     * mid-transfer must not kill a live socket, so the service outlives the flag and waits for
     * the last session to go terminal rather than returning and never being asked again.
     *
     * "Wanted" is the same condition `syncServiceState` starts on, permission included:
     * gating only on the flag would leave the service running forever after
     * `POST_NOTIFICATIONS` was revoked, since it would keep being started and never stop.
     */
    private suspend fun stopIfNotNeeded() {
        if (!stopping.compareAndSet(false, true)) return
        try {
            if (ShareReceiveController.isServiceWanted(this)) return
            ShareReceiveController.stop()
            if (ShareReceiveController.hasActiveTransfers(this)) {
                Log.status(TAG, "deferring stop: a transfer is still in flight")
                ShareReceiveController.awaitIdle(this)
            }
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } finally {
            stopping.set(false)
        }
    }

    // Broad catch is deliberate: startForeground throws undocumented RuntimeExceptions
    // (not just SecurityException) when the platform refuses a background start.
    @Suppress("TooGenericExceptionCaught")
    private fun enterForeground(isSending: Boolean) {
        val notif = buildNotification(isSending)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // A background start that the platform refuses. Nothing to recover here; the tile
            // and the boot receiver both retry through syncServiceState.
            Log.status(TAG, "could not enter the foreground", e)
        }
    }

    /**
     * Android 15's cumulative-runtime timeout.
     *
     * `connectedDevice` is not capped, so this should never fire; if a future platform version
     * caps it anyway, stopping cleanly beats being killed with a live socket open.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.status(TAG, "foreground service timed out (type $fgsType)")
        scope.launch {
            ShareReceiveController.stop()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun buildNotification(isSending: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ShareTransferService::class.java).setAction(ACTION_TURN_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_share)
            .setContentTitle(
                getString(
                    if (isSending) R.string.share_notification_title_sending
                    else R.string.share_notification_title_receiving
                )
            )
            .setContentText(
                getString(
                    if (isSending) R.string.share_notification_text_sending
                    else R.string.share_notification_text
                )
            )
            .setOngoing(true)
            .setContentIntent(pendingIntent)
        // "Turn off" belongs to receiving; offering it beside a send would be a lie.
        if (!isSending) {
            builder.addAction(
                R.drawable.ic_tile_share,
                getString(R.string.share_notification_action_stop),
                stopIntent,
            )
        }
        return builder.build()
    }

    /** Shared constants and helpers for ShareTransferService. */
    companion object {
        const val ACTION_START_RECEIVE = "com.vayunmathur.share.action.START_RECEIVE"
        const val ACTION_START_SEND = "com.vayunmathur.share.action.START_SEND"

        /** Reconcile and stop if nothing needs this service any more. */
        const val ACTION_STOP = "com.vayunmathur.share.action.STOP"

        /** The foreground notification's own "Turn off", which also flips the persisted flag. */
        const val ACTION_TURN_OFF = "com.vayunmathur.share.action.TURN_OFF"

        const val ACTION_ACCEPT = "com.vayunmathur.share.action.ACCEPT"
        const val ACTION_REJECT = "com.vayunmathur.share.action.REJECT"
        const val ACTION_CANCEL = "com.vayunmathur.share.action.CANCEL"

        /** The native session handle, the only session identity that fits a `PendingIntent`. */
        const val EXTRA_SESSION_HANDLE = "com.vayunmathur.share.EXTRA_SESSION_HANDLE"

        /** So a consumer never has to recompute the notification id it is answering for. */
        const val EXTRA_NOTIF_ID = "com.vayunmathur.share.EXTRA_NOTIF_ID"

        fun startSendMode(context: Context) {
            start(context, Intent(context, ShareTransferService::class.java).setAction(ACTION_START_SEND))
        }

        fun routeSessionAction(context: Context, action: String, handle: Long, notifId: Int) {
            val intent = Intent(context, ShareTransferService::class.java).apply {
                this.action = action
                putExtra(EXTRA_SESSION_HANDLE, handle)
                putExtra(EXTRA_NOTIF_ID, notifId)
            }
            start(context, intent)
        }

        fun stop(context: Context) {
            // startForegroundService, not startService: a background startService throws on
            // Android 8+, and every branch of onStartCommand enters the foreground anyway.
            start(context, Intent(context, ShareTransferService::class.java).setAction(ACTION_STOP))
        }

        private fun start(context: Context, intent: Intent) {
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
