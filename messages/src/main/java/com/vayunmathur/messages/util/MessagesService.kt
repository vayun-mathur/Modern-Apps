package com.vayunmathur.messages.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.vayunmathur.messages.MainActivity
import com.vayunmathur.messages.R
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.util.SourceConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the two WebView puppets for the lifetime
 * of the navigation/messaging session.
 *
 * Modeled on findfamily's LocationTrackingService — `Service` subclass
 * with a `SupervisorJob`-scoped `serviceScope`. The persistent ongoing
 * notification on channel [SYNC_CHANNEL_ID] is the OS-required cost of
 * a foreground service; we use a low-importance silent channel so it
 * doesn't badge or beep. Incoming-message alerts ride on a separate
 * IMPORTANCE_HIGH channel [INCOMING_CHANNEL_ID] using MessagingStyle.
 */
class MessagesService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stateCollector: Job? = null
    private var incomingCollector: Job? = null
    private var lastSyncNotificationContent: String? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        MessagesSessionManager.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "ACTION_STOP")
            shutdown()
            return START_NOT_STICKY
        }

        ensureChannels()
        startForeground(
            SYNC_NOTIFICATION_ID,
            buildSyncNotification(MessagesSessionManager.connectionStates.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0,
        )

        // Start the GMessages session (idempotent).
        MessagesSessionManager.start()

        stateCollector?.cancel()
        stateCollector = serviceScope.launch {
            MessagesSessionManager.connectionStates.collect { states ->
                val content = describeStates(states)
                if (content != lastSyncNotificationContent) {
                    lastSyncNotificationContent = content
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(SYNC_NOTIFICATION_ID, buildSyncNotification(states))
                }
            }
        }

        incomingCollector?.cancel()
        incomingCollector = serviceScope.launch {
            MessagesSessionManager.incoming.collect { event ->
                showIncomingNotification(event)
            }
        }

        // START_STICKY: if the system kills us, restart with a null intent
        // so we recreate the puppets but don't replay any ACTION_STOP.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stateCollector?.cancel()
        incomingCollector?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Default behavior: keep the service alive when the user swipes
        // the task away — they still want messages to come in.
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Android 15+ (API 35) enforces a runtime limit on dataSync foreground services and calls this
     * when the limit is reached. We MUST stop the foreground promptly or the system throws
     * ForegroundServiceDidNotStopInTimeException (a hard crash). Downgrade to a background service
     * so the process/connection can keep running where allowed instead of crashing.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS onTimeout (type=$fgsType) — leaving foreground to avoid crash")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ----------------------------------------------------------------
    // Channels + notification building
    // ----------------------------------------------------------------

    private fun ensureChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(SYNC_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    SYNC_CHANNEL_ID,
                    getString(R.string.channel_sync_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.channel_sync_desc)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        if (nm.getNotificationChannel(INCOMING_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    INCOMING_CHANNEL_ID,
                    getString(R.string.channel_incoming_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = getString(R.string.channel_incoming_desc)
                }
            )
        }
    }

    private fun buildSyncNotification(states: Map<MessageSource, SourceConnectionState>): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MessagesService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_sync_title))
            .setContentText(describeStates(states))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.notification_action_stop), stop)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun describeStates(states: Map<MessageSource, SourceConnectionState>): String {
        val connected = states.entries
            .filter { it.value is SourceConnectionState.Connected }
            .map {
                when (it.key) {
                    MessageSource.MESSAGES_WEB -> "Phone"
                    MessageSource.VOICE -> "Voice"
                    MessageSource.TELEGRAM -> "Telegram"
                    MessageSource.SIGNAL -> "Signal"
                    MessageSource.WHATSAPP -> "WhatsApp"
                    MessageSource.MESSENGER -> "Messenger"
                    MessageSource.INSTAGRAM -> "Instagram"
                }
            }
        return if (connected.isEmpty()) {
            getString(R.string.notification_sync_text_none)
        } else {
            "Connected to ${connected.joinToString(" + ")}"
        }
    }

    private fun showIncomingNotification(event: GMEvent.IncomingMessage) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val convId = "${event.source.idPrefix}:${event.conversationId}"
        val tag = convId
        val notificationId = convId.hashCode()

        // Open into the specific conversation when tapped.
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_OPEN_CONVERSATION, convId)
        }
        val tap = PendingIntent.getActivity(
            this, notificationId,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Quick-reply via RemoteInput → MessagesQuickReplyReceiver.
        val remoteInput = RemoteInput.Builder(QUICK_REPLY_KEY)
            .setLabel(getString(R.string.notification_quick_reply_label))
            .build()
        val replyIntent = Intent(this, MessagesQuickReplyReceiver::class.java).apply {
            action = MessagesQuickReplyReceiver.ACTION_REPLY
            putExtra(MessagesQuickReplyReceiver.EXTRA_CONVERSATION_ID, convId)
        }
        val replyPending = PendingIntent.getBroadcast(
            this, notificationId,
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val replyAction = NotificationCompat.Action.Builder(
            0,
            getString(R.string.notification_quick_reply_label),
            replyPending,
        ).addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        val sender = Person.Builder()
            .setName(event.peerName ?: event.peerPhone ?: getString(R.string.app_name))
            .build()
        val self = Person.Builder().setName("You").build()

        val style = NotificationCompat.MessagingStyle(self)
            .addMessage(event.body, event.timestamp, sender)

        val n = NotificationCompat.Builder(this, INCOMING_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(style)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(replyAction)
            .build()
        nm.notify(tag, notificationId, n)
    }

    private fun shutdown() {
        MessagesSessionManager.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "MessagesService"
        const val SYNC_CHANNEL_ID = "messages_sync"
        const val INCOMING_CHANNEL_ID = "messages_incoming"
        const val SYNC_NOTIFICATION_ID = 9001
        const val ACTION_STOP = "com.vayunmathur.messages.STOP"
        const val EXTRA_OPEN_CONVERSATION = "open_conversation"
        const val QUICK_REPLY_KEY = "messages_quick_reply"

        /** Helper used by Activities to start the service on app launch. */
        fun start(context: android.content.Context) {
            val intent = Intent(context, MessagesService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
