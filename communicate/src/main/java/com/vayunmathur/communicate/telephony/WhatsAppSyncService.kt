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
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEventProcessor
import com.vayunmathur.communicate.data.whatsapp.WhatsAppLineSession
import com.vayunmathur.communicate.notifications.ConversationSpace
import com.vayunmathur.communicate.notifications.ConversationTarget
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground owner for the WhatsApp primary line: keeps the raw-socket Noise session alive, drains
 * client events into Room via [WhatsAppEventProcessor], and posts new-message notifications while
 * Communicate is backgrounded. Mirrors [GoogleVoiceSyncService] (FGS type remoteMessaging).
 */
class WhatsAppSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var session: WhatsAppLineSession
    private var processor: WhatsAppEventProcessor? = null

    override fun onCreate() {
        super.onCreate()
        session = WhatsAppLineSession.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: Android requires startForeground() within ~5s of any
        // startForegroundService() call, INCLUDING the ACTION_STOP path (MainActivity calls stop()
        // at launch when not signed in, on a service that was never started).
        ensureChannels()
        startForegroundCompat(buildSyncNotification())

        // Dev-only feature: if ever started in a release build, immediately stand down.
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) {
            shutdown()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            if (!session.hasUsableCredentials(this@WhatsAppSyncService)) {
                shutdown()
                return@launch
            }
            // Bring up the client + persistence pipeline.
            session.init(this@WhatsAppSyncService)
            val db = WhatsAppDatabase.getDatabase(this@WhatsAppSyncService)
            processor = WhatsAppEventProcessor(db).also { it.start(WhatsAppClient.events) }
            // Notify on inbound messages.
            launch {
                WhatsAppClient.events.collect { event ->
                    if (event is WhatsAppEvent.IncomingMessage) showIncomingNotification(event)
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        processor?.stop()
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
            name = "WhatsApp sync",
            importance = NotificationManager.IMPORTANCE_LOW,
            description = "Keeps the WhatsApp connection alive",
        ) {
            setSound(null, null)
            enableVibration(false)
        }
        ensureNotificationChannel(
            id = INCOMING_CHANNEL_ID,
            name = "WhatsApp messages",
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = "Incoming WhatsApp messages",
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
            Intent(this, WhatsAppSyncService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setContentTitle("WhatsApp")
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

    private fun showIncomingNotification(event: WhatsAppEvent.IncomingMessage) {
        val isGroup = event.conversationId.endsWith("@g.us")
        val target = ConversationTarget(
            line = CommunicateLine.WhatsApp,
            address = event.peerPhone ?: event.conversationId,
            remoteId = event.conversationId,
            isGroup = isGroup,
            title = if (isGroup) event.peerName else null,
            personName = event.senderName ?: event.peerName ?: event.peerPhone ?: "WhatsApp",
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

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(SYNC_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(SYNC_NOTIFICATION_ID, notification)
        }
    }

    private fun shutdown() {
        processor?.stop()
        processor = null
        WhatsAppClient.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Notification id and logging tag for the WhatsApp sync foreground service. */
    companion object {
        private const val TAG = "WhatsAppSync"
        private const val SYNC_NOTIFICATION_ID = 4721
        private const val SYNC_CHANNEL_ID = "wa_sync"
        private const val INCOMING_CHANNEL_ID = "wa_messages_incoming"
        private const val ACTION_STOP = "com.vayunmathur.communicate.whatsapp.STOP_SYNC"
        const val EXTRA_OPEN_WHATSAPP_THREAD = "open_whatsapp_thread"

        fun start(context: Context) {
            // Dev-only feature: never start the WhatsApp sync service in the release variant.
            if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return
            ContextCompat.startForegroundService(context, Intent(context, WhatsAppSyncService::class.java))
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WhatsAppSyncService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
