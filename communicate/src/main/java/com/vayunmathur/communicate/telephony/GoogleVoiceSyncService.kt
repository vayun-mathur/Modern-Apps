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
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceAuthException
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceClient
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.googlevoice.GvFolder
import com.vayunmathur.communicate.data.googlevoice.GvMessage
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import com.vayunmathur.communicate.notifications.ConversationSpace
import com.vayunmathur.communicate.notifications.ConversationTarget
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground owner for Google Voice receive state: SIP registration for inbound calls plus
 * conservative polling for new message notifications while Communicate is backgrounded.
 */
class GoogleVoiceSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private lateinit var session: GoogleVoiceSession

    override fun onCreate() {
        super.onCreate()
        session = GoogleVoiceSession.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: startForegroundService() requires a startForeground()
        // call within ~5s, including the ACTION_STOP path (MainActivity calls stop() at launch when
        // signed out, on a fresh install where this service was never started → crash otherwise).
        ensureChannels()
        startForegroundCompat(buildSyncNotification())

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        GoogleVoiceCallManager.init(this)
        GoogleVoiceCallManager.onIncomingCall = { from -> GoogleVoiceTelecom.addIncoming(this, from) }

        serviceScope.launch {
            if (!session.hasUsableCredentials()) {
                shutdown()
                return@launch
            }
            val number = session.phoneNumber() ?: getString(R.string.account_google_voice)
            GoogleVoiceTelecom.registerPhoneAccount(this@GoogleVoiceSyncService, number)
            GoogleVoiceCallManager.startRegistration()
            startPollingMessages()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.status(TAG, "FGS timeout for type=$fgsType; leaving foreground to avoid crash")
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun startPollingMessages() {
        if (pollJob?.isActive == true) return
        pollJob = serviceScope.launch {
            while (isActive) {
                runCatching { pollOnce() }
                    .onFailure { error ->
                        if (error is GoogleVoiceAuthException) {
                            Log.status(TAG, "Google Voice auth rejected; stopping sync service")
                            shutdown()
                            return@launch
                        }
                        Log.status(TAG, "Google Voice message poll failed", error)
                    }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun pollOnce() {
        val client = GoogleVoiceClient.get(this)
        val threads = (client.listThreads(GvFolder.Inbox) + client.listThreads(GvFolder.All))
            .distinctBy { it.id }
        val allMessages = threads
            .flatMap { it.messages }
            .sortedBy { it.timestampMillis }
        val newestTimestamp = allMessages.maxOfOrNull { it.timestampMillis } ?: return
        val lastSeen = session.lastSeenMessageTimestampMillis()
        if (lastSeen <= 0L) {
            val serviceStartWindow = System.currentTimeMillis() - FIRST_POLL_NOTIFY_WINDOW_MILLIS
            allMessages
                .filter { it.timestampMillis >= serviceStartWindow && it.shouldNotify() }
                .forEach { showIncomingNotification(it) }
            session.setLastSeenMessageTimestampMillis(newestTimestamp)
            return
        }

        allMessages
            .filter { it.timestampMillis > lastSeen && it.shouldNotify() }
            .forEach { showIncomingNotification(it) }
        if (newestTimestamp > lastSeen) {
            session.setLastSeenMessageTimestampMillis(newestTimestamp)
        }
    }

    private fun ensureChannels() {
        ensureNotificationChannel(
            id = SYNC_CHANNEL_ID,
            name = getString(R.string.gv_sync_channel_name),
            importance = NotificationManager.IMPORTANCE_LOW,
            description = getString(R.string.gv_sync_channel_desc),
        ) {
            setSound(null, null)
            enableVibration(false)
        }
        ensureNotificationChannel(
            id = INCOMING_CHANNEL_ID,
            name = getString(R.string.gv_incoming_channel_name),
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = getString(R.string.gv_incoming_channel_desc),
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
            Intent(this, GoogleVoiceSyncService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setContentTitle(getString(R.string.gv_sync_notification_title))
            .setContentText(getString(R.string.gv_sync_notification_text))
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

    private fun showIncomingNotification(message: GvMessage) {
        val name = message.phoneNumber.ifBlank { getString(R.string.account_google_voice) }
        val target = ConversationTarget(
            line = CommunicateLine.GoogleVoice,
            address = message.phoneNumber,
            remoteId = message.threadId,
            personName = name,
        )
        ConversationSpace.notifyIncoming(
            context = this,
            target = target,
            channelId = INCOMING_CHANNEL_ID,
            body = message.notificationText(),
            timestamp = message.timestampMillis,
            smallIcon = R.mipmap.ic_launcher,
        )
    }

    private fun GvMessage.shouldNotify(): Boolean = !outgoing

    private fun GvMessage.notificationText(): String = text.ifBlank {
        if (hasMedia) getString(R.string.media_message) else getString(R.string.new_message)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(SYNC_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(SYNC_NOTIFICATION_ID, notification)
        }
    }

    private fun shutdown() {
        pollJob?.cancel()
        pollJob = null
        GoogleVoiceCallManager.stopRegistration()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Poll interval and logging tag for the Google Voice sync service. */
    companion object {
        private const val TAG = "GoogleVoiceSync"
        private const val POLL_INTERVAL_MILLIS = 60_000L
        private const val FIRST_POLL_NOTIFY_WINDOW_MILLIS = 2 * 60_000L
        private const val SYNC_NOTIFICATION_ID = 4720
        private const val SYNC_CHANNEL_ID = "gv_sync"
        private const val INCOMING_CHANNEL_ID = "gv_messages_incoming"
        private const val ACTION_STOP = "com.vayunmathur.communicate.googlevoice.STOP_SYNC"
        const val EXTRA_OPEN_GOOGLE_VOICE_THREAD = "open_google_voice_thread"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GoogleVoiceSyncService::class.java))
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GoogleVoiceSyncService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
