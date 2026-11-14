package com.vayunmathur.communicate.telephony

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
import androidx.core.content.ContextCompat
import com.vayunmathur.communicate.MainActivity
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalEvent
import com.vayunmathur.communicate.data.signal.SignalFeature
import com.vayunmathur.communicate.data.signal.SignalLineSession
import com.vayunmathur.communicate.notifications.ConversationSpace
import com.vayunmathur.communicate.notifications.ConversationTarget
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground owner for the Signal primary line: keeps the WebSocket session alive and posts
 * new-message notifications while Communicate is backgrounded. Mirrors [WhatsAppSyncService]
 * (FGS type remoteMessaging).
 */
class SignalSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var session: SignalLineSession

    override fun onCreate() {
        super.onCreate()
        session = SignalLineSession.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: Android requires startForeground() within ~5s of any
        // startForegroundService() call, INCLUDING the ACTION_STOP path (MainActivity calls stop()
        // at launch when not signed in, on a service that was never started).
        ensureChannels()
        startForegroundCompat(buildSyncNotification())

        // Dev-only feature: if ever started in a release build, immediately stand down.
        if (!SignalFeature.enabled) {
            shutdown()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            if (!session.hasUsableCredentials(this@SignalSyncService)) {
                shutdown()
                return@launch
            }
            // Bring up the client — persistence pipeline will be wired by protocol teammate
            // (SignalEventProcessor) once available.
            session.init(this@SignalSyncService)
            // Drain is lightweight: Room writes happen in SignalEventProcessor when protocol lands;
            // meanwhile we post notifications on inbound messages directly.
            launch {
                SignalClient.events.collect { event ->
                    when (event) {
                        is SignalEvent.IncomingMessage -> showIncomingNotification(event)
                        is SignalEvent.IdentityKeyChanged -> showIdentityChangeNotification(event)
                        else -> Unit
                    }
                }
            }
            // Also persist the minimal fan-out until SignalEventProcessor exists:
            // keep conversation lastMessageTimestamp + cached message so threads list works even
            // before protocol's full event handling is wired.
            launch {
                // No-op until SignalEventProcessor is provided by protocol; kept for forward compat.
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.status(TAG, "FGS timeout for type=$fgsType; leaving foreground to avoid crash")
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun ensureChannels() {
        ensureNotificationChannel(
            id = SYNC_CHANNEL_ID,
            name = "Signal sync",
            importance = NotificationManager.IMPORTANCE_LOW,
            description = "Keeps the Signal connection alive",
        ) {
            setSound(null, null)
            enableVibration(false)
        }
        ensureNotificationChannel(
            id = INCOMING_CHANNEL_ID,
            name = "Signal messages",
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = "Incoming Signal messages",
        ) {
            setAllowBubbles(true)
        }
    }

    private fun buildSyncNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SignalSyncService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setContentTitle("Signal")
            .setContentText("Connected")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.notification_action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun showIncomingNotification(event: SignalEvent.IncomingMessage) {
        val isGroup = event.conversationId.startsWith("group:")
        val target = ConversationTarget(
            line = CommunicateLine.Signal,
            address = event.peerPhone ?: event.conversationId,
            remoteId = event.conversationId,
            isGroup = isGroup,
            title = if (isGroup) event.peerName else null,
            personName = event.senderName ?: event.peerName ?: event.peerPhone ?: "Signal",
        )
        ConversationSpace.notifyIncoming(
            context = this,
            target = target,
            channelId = INCOMING_CHANNEL_ID,
            body = event.body.ifBlank { getString(R.string.new_message) },
            timestamp = event.timestamp,
            smallIcon = R.mipmap.ic_launcher,
        )
    }

    /**
     * A changed identity key means messages to that contact now fail. Only the user can decide whether
     * it was a reinstall or an interception, so it has to reach them rather than sitting in a log.
     */
    private fun showIdentityChangeNotification(event: SignalEvent.IdentityKeyChanged) {
        val notificationId = event.peerAci.hashCode()
        val tap = PendingIntent.getActivity(
            this,
            notificationId,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_OPEN_SIGNAL_THREAD, event.conversationId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, INCOMING_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.signal_safety_number_changed_title))
            .setContentText(getString(R.string.signal_safety_number_changed_body))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.signal_safety_number_changed_body)),
            )
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(event.conversationId, notificationId, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(SYNC_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(SYNC_NOTIFICATION_ID, notification)
        }
    }

    private fun shutdown() {
        SignalClient.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Notification id and logging tag for the Signal sync foreground service. */
    companion object {
        private const val TAG = "SignalSync"
        private const val SYNC_NOTIFICATION_ID = 4722
        private const val SYNC_CHANNEL_ID = "signal_sync"
        private const val INCOMING_CHANNEL_ID = "signal_messages_incoming"
        private const val ACTION_STOP = "com.vayunmathur.communicate.signal.STOP_SYNC"
        const val EXTRA_OPEN_SIGNAL_THREAD = "open_signal_thread"

        fun start(context: Context) {
            // Dev-only feature: never start the Signal sync service in the release variant.
            if (!SignalFeature.enabled) return
            ContextCompat.startForegroundService(context, Intent(context, SignalSyncService::class.java))
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SignalSyncService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
