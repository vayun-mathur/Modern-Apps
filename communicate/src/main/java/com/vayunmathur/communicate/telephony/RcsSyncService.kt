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
import android.telephony.ims.SipMessage
import com.vayunmathur.library.log.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vayunmathur.communicate.MainActivity
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.applyRcsDeliveryReport
import com.vayunmathur.communicate.data.cacheInboundRcs
import com.vayunmathur.communicate.data.rcs.ImdnDisposition
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsFileTransferHttp
import com.vayunmathur.communicate.data.rcs.RcsImdn
import com.vayunmathur.communicate.data.rcs.RcsMsrp
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsOutbox
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipResponse
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.cacheInboundRcsWithImdn
import com.vayunmathur.communicate.data.rcs.focusMembers
import com.vayunmathur.communicate.data.rcs.hostedFocusFor
import com.vayunmathur.communicate.data.rcs.refreshEventSubscriptions
import com.vayunmathur.communicate.data.rcs.buildImdnBody
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.RcsKeyDirectory
import com.vayunmathur.communicate.data.rcs.e2e.RcsPeerKeys
import com.vayunmathur.communicate.data.rcs.e2e.RcsPendingGroups
import com.vayunmathur.communicate.data.rcs.e2e.RustMlsCrypto
import com.vayunmathur.communicate.data.rcs.extractImdnMessageId
import com.vayunmathur.communicate.data.rcs.parseChunkHeader
import com.vayunmathur.communicate.data.rcs.parseEditBody
import com.vayunmathur.communicate.data.rcs.parseGeopushBody
import com.vayunmathur.communicate.data.rcs.parseImdnBody
import com.vayunmathur.communicate.data.rcs.parseIsComposingBody
import com.vayunmathur.communicate.data.rcs.parseRevokeBody
import com.vayunmathur.communicate.notifications.ConversationSpace
import com.vayunmathur.communicate.notifications.ConversationTarget
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground owner for the RCS single-registration line: keeps the SIP
 * delegate + UCE listener alive, writes inbound messages to Room, and posts
 * new-message notifications while Communicate is backgrounded. Mirrors
 * [SignalSyncService] (FGS type remoteMessaging).
 */
class RcsSyncService : Service() {

    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** MSRP connections per conversation, owned by this service's lifecycle. */
    internal val msrpConnections = java.util.concurrent.ConcurrentHashMap<String, RcsMsrp.MsrpConnection>()

    /** Remote path the live MSRP connection serves, per conversation. */
    internal val boundPaths = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Subscription listener for subId switches (§7.2); unregistered on destroy. */
    private var subListener: android.telephony.SubscriptionManager.OnSubscriptionsChangedListener? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: Android requires startForeground() within ~5s of any
        // startForegroundService() call, INCLUDING the ACTION_STOP path (MainActivity calls stop()
        // at launch when not registered, on a service that was never started).
        ensureChannels()
        startForegroundCompat(buildSyncNotification())

        // Dev-only feature: if ever started in a release build, immediately stand down.
        if (!RcsFeature.enabled) {
            shutdown()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            RcsSipTransport.ensureRegistered(this@RcsSyncService)
            RcsSipTransport.onInboundMessage = { message ->
                serviceScope.launch { handleInbound(message) }
            }
            watchSubscriptions()
            startAcceptLoop()
            startMaintenanceLoops()
        }

        return START_STICKY
    }

    /** Active-subscription switch (§7.2): re-register on sub changes. */
    private fun watchSubscriptions() {
        // Active-subscription switch (§7.2): tear down + re-register so
        // the delegate, IMS network, and listener follow the new sub.
        // ensureRegistered no-ops when the subId is unchanged, so this is
        // cheap on unrelated subscription broadcasts.
        runCatching {
            val sm = getSystemService(android.telephony.SubscriptionManager::class.java)
            if (sm != null && RcsFeature.enabled) {
                subListener = object : android.telephony.SubscriptionManager.OnSubscriptionsChangedListener() {
                    override fun onSubscriptionsChanged() {
                        serviceScope.launch {
                            RcsSipTransport.ensureRegistered(this@RcsSyncService)
                        }
                    }
                }.also {
                    sm.addOnSubscriptionsChangedListener(
                        ContextCompat.getMainExecutor(this@RcsSyncService),
                        it,
                    )
                }
            }
        }
    }

    /** Passive MSRP accept loop + transient-chunk fallback. */
    private suspend fun startAcceptLoop() {
        // Let the session manager offer/answer passive MSRP: the sync
        // service owns the accept loop, so accepted sockets land here.
        RcsSessionManager.listenContextProvider = { this@RcsSyncService }
        RcsMsrpListen.ensureListening(this@RcsSyncService, ::onMsrpAccepted)
        // Transient MSRP sockets (FT transfers) forward stray inbound
        // chunks here so nothing is dropped outside the session map.
        RcsMsrp.onInboundFallback = { conversationId, contentType, body ->
            val session = RcsSessionManager.sessionFor(conversationId)
            if (session != null) {
                serviceScope.launch { handleMsrpChunk(conversationId, session, contentType, body) }
            }
        }
    }

    /** Outbox pump, teardown watch, and MSRP lifecycle loops. */

    override fun onBind(intent: Intent?): IBinder? = null

    /** Ensure one live MSRP connection per session path (connect/replace/cleanup). */
    internal suspend fun syncMsrpConnection(conversationId: String, session: RcsSession, path: String) {
        val live = msrpConnections[conversationId]
        if (live != null && boundPaths[conversationId] == path && live.isClosed().not()) {
            return
        }
        // New path, replaced path (handover), or dead conn.
        msrpConnections.remove(conversationId)?.close()
        val conn = RcsMsrp.connect(session, this) { contentType, body ->
            serviceScope.launch { handleMsrpChunk(conversationId, session, contentType, body) }
        }
        if (conn != null) {
            msrpConnections[conversationId] = conn
            boundPaths[conversationId] = path
        } else {
            boundPaths.remove(conversationId)
        }
    }

    override fun onDestroy() {
        RcsSipTransport.onInboundMessage = null
        RcsSessionManager.listenContextProvider = null
        RcsMsrp.onInboundFallback = null
        RcsMsrpListen.stop()
        runCatching {
            val sm = getSystemService(android.telephony.SubscriptionManager::class.java)
            subListener?.let { sm?.removeOnSubscriptionsChangedListener(it) }
        }
        subListener = null
        msrpConnections.values.forEach { runCatching { it.close() } }
        msrpConnections.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.status(TAG, "FGS timeout for type=$fgsType; leaving foreground to avoid crash")
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private suspend fun handleInbound(message: SipMessage) {
        val startLine = message.getStartLine()
        // SIP responses (2xx/401/481/…) route to the transport's response
        // handler first — they are transaction outcomes, not chat messages.
        if (RcsSipResponse.isResponse(startLine)) {
            RcsSipTransport.onSipResponseMessage(message)
            return
        }
        val method = startLine.substringBefore(" ").trim().uppercase()
        // Dialog-forming and dialog methods route before MESSAGE handling.
        if (handleDialogMethod(method, message)) return
        val envelope = parseEnvelope(message) ?: return
        // E2EE control + data payloads route before plaintext handling.
        handleE2EEInbound(envelope)?.let { e2ee ->
            cacheE2eeInbound(e2ee)
            return
        }
        val parsed = parseInbound(envelope) ?: return
        cachePlainInbound(parsed, envelope)
    }

    /** Route dialog methods; true when fully handled. */
    private suspend fun handleDialogMethod(method: String, message: SipMessage): Boolean {
        when (method) {
            "INVITE" -> handleInboundInvite(message)
            "BYE" -> handleInboundBye(message)
            "REFER" -> handleInboundRefer(message)
            "OPTIONS" -> handleInboundOptions(message)
            "ACK" -> RcsSessionManager.onSipRequest(
                "ACK",
                message.getCallIdParameter().orEmpty(),
                "", null, "",
            )
            "NOTIFY" -> handleInboundNotify(message)
            "SUBSCRIBE" -> handleInboundSubscribe(message)
            "UPDATE" -> handleInboundUpdate(message)
            else -> return false
        }
        return true
    }

    /** Cache an E2EE inbound row + notify for text. */
    private suspend fun cacheE2eeInbound(e2ee: InboundRcs) {
        when (e2ee.kind) {
            InboundKind.Text -> {
                CommunicateRepository.cacheInboundRcs(
                    context = this,
                    conversationId = e2ee.conversationId,
                    body = e2ee.body,
                    senderId = e2ee.senderId,
                    messageId = e2ee.messageId,
                    ftUrl = e2ee.ftUrl,
                    ftMime = e2ee.ftMime,
                )
                showIncomingNotification(e2ee)
            }
            InboundKind.Receipt, InboundKind.Typing -> Unit
        }
    }

    /** Cache a plaintext inbound row + notify + relay + reports. */
    private suspend fun cachePlainInbound(parsed: InboundRcs, envelope: Envelope) {
        when (parsed.kind) {
            InboundKind.Text -> {
                CommunicateRepository.cacheInboundRcsWithImdn(
                    context = this,
                    conversationId = parsed.conversationId,
                    body = parsed.body,
                    senderId = parsed.senderId,
                    messageId = parsed.messageId,
                    imdnId = parsed.imdnMessageId.orEmpty(),
                    ftUrl = parsed.ftUrl,
                    ftMime = parsed.ftMime,
                )
                // FT auto-fetch (§6.4): download http(s) attachments now so
                // they open offline; the row's ftUrl is rewritten to the
                // local file. geo:/msrp: descriptors stay as-is.
                if (!parsed.ftUrl.isNullOrBlank() &&
                    (parsed.ftUrl.startsWith("http://") || parsed.ftUrl.startsWith("https://"))
                ) {
                    serviceScope.launch {
                        fetchInboundAttachment(parsed.conversationId, parsed.messageId, parsed.ftUrl)
                    }
                }
                // Hosted focus: relay the message to the other members
                // (ciphertext passes through untouched when E2EE is on).
                relayFocusMessage(parsed, envelope.raw)
                // IMDN positive-delivery: the message reached us, so tell the sender.
                sendImdnReport(parsed)
                showIncomingNotification(parsed)
            }
            InboundKind.Receipt, InboundKind.Typing -> {
                // Routed to RcsImdn / RcsSessionManager.typing by parseInbound already.
                Unit
            }
        }
    }

    /** Inbound BYE: tear down the session + MSRP connection. */
    private suspend fun handleInboundBye(message: SipMessage) {
        if (!RcsFeature.enabled) return
        val callId = message.getCallIdParameter() ?: return
        RcsSessionManager.onSipRequest("BYE", callId, "", null, "")
        closeDeadMsrpConnections()
        Log.status(TAG, "BYE processed callId=$callId")
    }

    /**
     * Inbound MSRP chunk: CPIM text becomes an inbox row (+ notification);
     * IMDN/typing ride their trackers. Reuses the SIP parse helpers by
     * synthesizing the CPIM text path.
     */
    internal suspend fun handleMsrpChunk(
        conversationId: String,
        session: RcsSession,
        contentType: String,
        body: ByteArray,
    ) {
        if (!RcsFeature.enabled) return
        val text = body.toString(Charsets.UTF_8)
        if (contentType.contains("imdn", ignoreCase = true)) {
            RcsImdn.onReportReceived(text)
            return
        }
        // The wire type is application/im-iscomposing+xml, which does not
        // contain the substring "im-composing"; like parseInbound, fall back
        // to sniffing the body so typing stanzas are not cached as messages.
        if (contentType.contains("im-composing", ignoreCase = true) ||
            parseIsComposingBody(text) != null
        ) {
            RcsSessionManager.onSipRequest("MESSAGE", session.callId, conversationId, contentType, text)
            return
        }
        val display = if (contentType.contains("cpim", ignoreCase = true)) {
            extractTextBody(body) ?: text
        } else {
            text
        }
        if (display.isBlank()) return
        val ft = RcsFileTransferHttp.parseFtBody(display)
        CommunicateRepository.cacheInboundRcs(
            context = this,
            conversationId = conversationId,
            body = ft?.let { display.substringAfter("\r\n").ifBlank { "[file]" } } ?: display,
            senderId = conversationId,
            messageId = "in-msrp-${display.hashCode()}-${System.currentTimeMillis()}",
            ftUrl = ft?.url,
            ftMime = ft?.mime,
        )
        showIncomingNotification(
            InboundRcs(
                conversationId = conversationId,
                body = display,
                senderId = conversationId,
                messageId = "in-msrp-${display.hashCode()}",
            ),
        )
    }

    /**
     * Focus relay: when [parsed] arrived on a conversation we host the focus
     * for, re-send the raw body to every other focus member. The relayed bytes
     * are the original wire body (CPIM + payload), so MLS ciphertext stays
     * opaque to us — E2EE terminates per-member, not at the focus.
     */
    internal suspend fun relayFocusMessage(parsed: InboundRcs, rawBody: String) {
        if (!RcsFeature.enabled) return
        val focusUri = RcsSessionManager.hostedFocusFor(parsed.conversationId) ?: return
        val members = RcsSessionManager.focusMembers(focusUri) ?: return
        val others = members.filter { it != parsed.senderId && it.isNotBlank() }
        if (others.isEmpty() || rawBody.isBlank()) return
        for (peer in others) {
            runCatching {
                val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
                    fromUri = "sip:me@rcs",
                    toUri = "sip:$peer@rcs",
                    callId = "${java.util.UUID.randomUUID()}@rcs-relay",
                    body = rawBody,
                )
                RcsSipTransport.sendSipMessage(startLine, headers, content)
            }
        }
        Log.status(TAG, "Relayed focus message to ${others.size} members")
    }

    /**
     * FT auto-fetch (§6.4): download an inbound http(s) attachment into the
     * app cache and rewrite the row's ftUrl to the local file URI. Failure
     * keeps the remote URL (tap-to-retry opens it remotely). Never throws.
     */
    private suspend fun fetchInboundAttachment(conversationId: String, messageId: String, url: String) {
        if (!RcsFeature.enabled) return
        runCatching {
            val file = RcsFileTransferHttp.download(this, url) ?: return
            val db = RcsDatabase.getDatabase(this)
            db.cachedMessageDao().get(messageId)?.let { row ->
                db.cachedMessageDao().upsert(
                    row.copy(ftUrl = android.net.Uri.fromFile(file).toString()),
                )
            }
            Log.status(TAG, "Auto-fetched FT for $conversationId")
        }
    }

    private suspend fun sendImdnReport(parsed: InboundRcs) {
        val originalId = parsed.imdnMessageId ?: return
        runCatching {
            val report = buildImdnBody(originalId, ImdnDisposition.Delivered)
            val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
                fromUri = "sip:me@rcs",
                toUri = "sip:${parsed.conversationId}@rcs",
                callId = "${java.util.UUID.randomUUID()}@rcs-imdn",
                body = report,
            )
            RcsSipTransport.sendSipMessage(startLine, headers, content)
        }
    }

    /** Inbound parse pipeline lives in `RcsInboundParse.kt` (split for file length). */

    private fun showIncomingNotification(parsed: InboundRcs) {
        val target = ConversationTarget(
            line = CommunicateLine.Rcs,
            address = parsed.conversationId,
            remoteId = parsed.conversationId,
            isGroup = false,
            personName = parsed.senderId,
        )
        ConversationSpace.notifyIncoming(
            context = this,
            target = target,
            channelId = INCOMING_CHANNEL_ID,
            body = parsed.body.ifBlank { getString(R.string.new_message) },
            timestamp = System.currentTimeMillis(),
            smallIcon = R.mipmap.ic_launcher,
        )
    }

    private fun ensureChannels() {
        ensureNotificationChannel(
            id = SYNC_CHANNEL_ID,
            name = "RCS sync",
            importance = NotificationManager.IMPORTANCE_LOW,
            description = "Keeps the RCS connection alive",
        ) {
            setSound(null, null)
            enableVibration(false)
        }
        ensureNotificationChannel(
            id = INCOMING_CHANNEL_ID,
            name = "RCS messages",
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = "Incoming RCS messages",
        ) {
            setAllowBubbles(true)
        }
    }

    private fun buildSyncNotification(state: RcsRegistrationState? = null): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RcsSyncService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val status = when (state) {
            is RcsRegistrationState.Available -> getString(R.string.rcs_available)
            is RcsRegistrationState.Unavailable -> getString(R.string.rcs_not_available)
            else -> getString(R.string.rcs_provisioning)
        }
        return NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setContentTitle("RCS")
            .setContentText(status)
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

    internal fun updateSyncNotification(state: RcsRegistrationState) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(SYNC_NOTIFICATION_ID, buildSyncNotification(state))
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(SYNC_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(SYNC_NOTIFICATION_ID, notification)
        }
    }

    internal fun shutdown() {
        RcsSipTransport.onInboundMessage = null
        RcsSessionManager.listenContextProvider = null
        RcsMsrp.onInboundFallback = null
        RcsMsrpListen.stop()
        runCatching {
            val sm = getSystemService(android.telephony.SubscriptionManager::class.java)
            subListener?.let { sm?.removeOnSubscriptionsChangedListener(it) }
        }
        subListener = null
        msrpConnections.values.forEach { runCatching { it.close() } }
        msrpConnections.clear()
        runCatching { RcsSipTransport.tearDown(this) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        internal const val TAG = "RcsSync"
        internal const val OUTBOX_POLL_MS = 5 * 60_000L
        private const val SYNC_NOTIFICATION_ID = 4723
        private const val SYNC_CHANNEL_ID = "rcs_sync"
        internal const val INCOMING_CHANNEL_ID = "rcs_messages_incoming"
        private const val ACTION_STOP = "com.vayunmathur.communicate.rcs.STOP_SYNC"
        const val EXTRA_OPEN_RCS_THREAD = "open_rcs_thread"

        fun start(context: Context) {
            // Dev-only feature: never start the RCS sync service in the release variant.
            if (!RcsFeature.enabled) return
            ContextCompat.startForegroundService(context, Intent(context, RcsSyncService::class.java))
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RcsSyncService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
