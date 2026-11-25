package com.vayunmathur.communicate.data

import android.content.Context
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData
import com.vayunmathur.communicate.data.signal.SignalServiceData
import com.vayunmathur.communicate.domain.WearCommLine
import com.vayunmathur.communicate.domain.WearCommOp
import com.vayunmathur.communicate.domain.WearCommWatchState
import com.vayunmathur.communicate.domain.WearCommunicateProtocol
import com.vayunmathur.communicate.domain.WearCommunicateSnapshot
import com.vayunmathur.communicate.domain.WearIncomingCall
import com.vayunmathur.communicate.domain.WearMessage
import com.vayunmathur.communicate.domain.WearOpKind
import com.vayunmathur.communicate.domain.WearOpResult
import com.vayunmathur.communicate.domain.WearThread
import com.vayunmathur.communicate.domain.WearThreadRequest
import com.vayunmathur.communicate.domain.buildWearCommunicateSnapshot
import com.vayunmathur.communicate.domain.buildWearNewMessageAlert
import com.vayunmathur.communicate.domain.buildWearThreadPage
import com.vayunmathur.communicate.domain.validWearReply
import com.vayunmathur.communicate.domain.wearThreadKey
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/** Phone-side wear status; absence of a watch is a normal state, never an error. */
data class WearCommunicateSyncState(
    val companionPresent: Boolean = false,
    val lastPublishedAtMillis: Long? = null,
    val lastPublishedThreadCount: Int = 0,
    val lastWatchPendingOps: Int = 0,
)

/** Executes one validated watch op on the phone. Seam for unit tests. */
internal interface WearOpHandler {
    suspend fun quickReply(op: WearCommOp, body: String): Boolean
    suspend fun markRead(op: WearCommOp): Boolean
    suspend fun markVoicemailRead(op: WearCommOp): Boolean
    suspend fun declineCall(op: WearCommOp): Boolean
}

/**
 * Applies one watch op after protocol validation. Quick replies need a
 * non-blank capped body and a thread; mark-read needs a thread; voicemail
 * and decline need a target key. Archive/mute do not exist here because
 * the phone UI offers neither.
 */
internal suspend fun applyWearCommOp(op: WearCommOp, handler: WearOpHandler): Boolean =
    when (op.kind) {
        WearOpKind.QuickReply -> validWearReply(op)?.let { handler.quickReply(op, it) } ?: false
        WearOpKind.MarkRead -> op.threadKey.isNotBlank() && handler.markRead(op)
        WearOpKind.MarkVoicemailRead -> op.targetKey.isNotBlank() && handler.markVoicemailRead(op)
        WearOpKind.DeclineCall -> op.targetKey.isNotBlank() && handler.declineCall(op)
    }

/**
 * Phone side of the Communicate wear companion over WearLink (RFCOMM,
 * :library:wearlink): bounded snapshot data item, thread pages and small
 * alerts as messages, watch ops executed here against
 * [CommunicateRepository]. Repository access and decryption stay on the
 * phone; see [WearCommunicateProtocol] for caps and privacy choices.
 * Delivery is app-lifecycle-bound (no Play-services wakeup): the watch
 * keeps ops pending and resends on the next live session.
 */
class WearCommunicateSync private constructor(context: Context) {

    companion object {
        private const val TAG = "WearCommunicateSync"

        @Volatile
        private var instance: WearCommunicateSync? = null

        fun get(context: Context): WearCommunicateSync = instance ?: synchronized(this) {
            instance ?: WearCommunicateSync(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(WearCommunicateSyncState())
    val state: StateFlow<WearCommunicateSyncState> = _state.asStateFlow()

    private var lastThreadsByKey: Map<String, WearThread> = emptyMap()
    private var lastCallKey: String? = null

    private val handler = object : WearOpHandler {
        override suspend fun quickReply(op: WearCommOp, body: String): Boolean {
            val choice: LineChoice = when (op.line) {
                WearCommLine.Sim -> LineChoice.Sim(op.subscriptionId ?: -1, "SIM")
                WearCommLine.GoogleVoice -> LineChoice.GoogleVoice
                WearCommLine.WhatsApp -> LineChoice.WhatsApp
                WearCommLine.Signal -> LineChoice.Signal
                WearCommLine.Rcs -> LineChoice.Rcs
            }
            return CommunicateRepository.sendMessage(
                appContext, choice, op.address, body,
                threadRemoteId = op.remoteId, participants = op.participants,
            )
        }

        override suspend fun markRead(op: WearCommOp): Boolean = runCatching {
            when (op.line) {
                WearCommLine.Sim ->
                    CommunicateRepository.markSimThreadRead(appContext, op.threadId)
                WearCommLine.GoogleVoice -> op.remoteId?.let {
                    CommunicateRepository.updateGoogleVoiceThread(
                        appContext, it, GoogleVoiceParser.ThreadAction.MarkRead,
                    )
                } ?: false
                WearCommLine.WhatsApp -> {
                    CommunicateRepository.markWhatsAppRead(appContext, op.remoteId, op.address, null)
                    true
                }
                WearCommLine.Signal -> {
                    CommunicateRepository.markSignalRead(appContext, op.remoteId, op.address, null)
                    true
                }
                WearCommLine.Rcs -> {
                    CommunicateRepository.markRcsRead(appContext, op.remoteId, op.address)
                    true
                }
            }
        }.getOrDefault(false)

        override suspend fun markVoicemailRead(op: WearCommOp): Boolean {
            val entry = CommunicateRepository.loadVoicemailsMerged(appContext)
                .firstOrNull { wearVoicemailKey(it) == op.targetKey } ?: return false
            return CommunicateRepository.markVoicemailEntryRead(appContext, entry)
        }

        override suspend fun declineCall(op: WearCommOp): Boolean {
            val call = InAppCallRegistry.state.value
            if (call.phase != InAppCallPhase.Incoming) return false
            if (wearCallKey(call.line, call.peerId) != op.targetKey) return false
            InAppCallRegistry.reject(source = "wear")
            return true
        }
    }

    private val listener = object : WearLinkListener {
        override fun onData(path: String, body: ByteArray) {
            if (path != WearCommunicateProtocol.WATCH_STATE_PATH) return
            val watchState = runCatching {
                WearCommunicateProtocol.json.decodeFromString<WearCommWatchState>(body.decodeToString())
            }.getOrNull() ?: return
            _state.update { it.copy(lastWatchPendingOps = watchState.pendingOps) }
        }

        override fun onMessage(path: String, body: ByteArray) {
            when (path) {
                WearCommunicateProtocol.REQUEST_SYNC_PATH -> scope.launch { publishSnapshot() }
                WearCommunicateProtocol.REQUEST_THREAD_PATH -> {
                    val request = runCatching {
                        WearCommunicateProtocol.json.decodeFromString<WearThreadRequest>(body.decodeToString())
                    }.getOrNull() ?: return
                    scope.launch { publishThreadPage(request) }
                }
                WearCommunicateProtocol.OP_PATH -> {
                    val op = runCatching {
                        WearCommunicateProtocol.json.decodeFromString<WearCommOp>(body.decodeToString())
                    }.getOrNull()
                    if (op == null) {
                        Log.status(TAG, "ignoring malformed wear op")
                        return
                    }
                    scope.launch {
                        val ok = runCatching { applyWearCommOp(op, handler) }.getOrDefault(false)
                        node.sendMessage(
                            WearCommunicateProtocol.OP_RESULT_PATH,
                            WearCommunicateProtocol.json.encodeToString(
                                WearOpResult(op.opId, op.kind, ok),
                            ).encodeToByteArray(),
                        )
                        if (ok) publishSnapshot()
                    }
                }
            }
        }
    }

    private val node: WearLinkNode =
        WearLinkNode(appContext, WearCommunicateProtocol.CAPABILITY, listener)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            node.state.collect { linkState ->
                val present = linkState is WearLinkState.Connected
                val wasPresent = _state.value.companionPresent
                _state.update { it.copy(companionPresent = present) }
                if (present && !wasPresent) publishSnapshot()
            }
        }
        // Incoming-call alerts: only decline crosses back (no answer-on-watch).
        scope.launch {
            InAppCallRegistry.state.collect { call ->
                val key = if (call.phase == InAppCallPhase.Incoming) {
                    wearCallKey(call.line, call.peerId)
                } else {
                    null
                }
                if (key != null && key != lastCallKey) {
                    node.sendMessage(
                        WearCommunicateProtocol.INCOMING_CALL_PATH,
                        WearCommunicateProtocol.json.encodeToString(
                            WearIncomingCall(
                                callKey = key,
                                displayName = call.peerName,
                                line = call.line?.toWearLine() ?: WearCommLine.Sim,
                                isVideo = call.isVideo,
                                timestampMillis = System.currentTimeMillis(),
                            ),
                        ).encodeToByteArray(),
                    )
                }
                lastCallKey = key
            }
        }
        node.start()
    }

    fun syncNow() {
        scope.launch { publishSnapshot() }
    }

    /** Builds the bounded snapshot from the merged repository and publishes it. */
    suspend fun publishSnapshot() {
        val threads = CommunicateRepository.loadSmsThreadsMerged(appContext)
        val wearThreads = threads.map { thread ->
            thread.toWearThread(isDisappearing(thread))
        }
        val snapshot = buildWearCommunicateSnapshot(
            threads = wearThreads,
            calls = CommunicateRepository.loadCallLogsMerged(appContext).map { it.toWearCall() },
            voicemails = CommunicateRepository.loadVoicemailsMerged(appContext)
                .map { it.toWearVoicemail() },
            generatedAtMillis = System.currentTimeMillis(),
        )
        // Small new-message alerts for threads that advanced since the last snapshot.
        if (lastThreadsByKey.isNotEmpty()) {
            for (thread in snapshot.threads) {
                val previous = lastThreadsByKey[thread.key]
                if (previous != null && thread.timestampMillis > previous.timestampMillis &&
                    thread.unreadCount > 0
                ) {
                    node.sendMessage(
                        WearCommunicateProtocol.NEW_MESSAGE_PATH,
                        WearCommunicateProtocol.json.encodeToString(
                            buildWearNewMessageAlert(thread, thread.timestampMillis),
                        ).encodeToByteArray(),
                    )
                }
            }
        }
        lastThreadsByKey = snapshot.threads.associateBy { it.key }
        val sent = node.putData(
            WearCommunicateProtocol.SNAPSHOT_PATH,
            WearCommunicateProtocol.json.encodeToString(snapshot).encodeToByteArray(),
        )
        if (sent) {
            _state.update {
                it.copy(
                    lastPublishedAtMillis = snapshot.generatedAtMillis,
                    lastPublishedThreadCount = snapshot.threads.size,
                )
            }
        } else {
            Log.status(TAG, "publish snapshot not delivered (no live WearLink session)")
        }
    }

    /** Answers one thread-page request with a message carrying that page only. */
    suspend fun publishThreadPage(request: WearThreadRequest) {
        val thread = CommunicateRepository.loadSmsThreadsMerged(appContext)
            .firstOrNull { wearThreadKey(it.line.toWearLine(), it.remoteId, it.threadId) == request.threadKey }
            ?: return
        val disappearing = isDisappearing(thread)
        val messages = CommunicateRepository.loadSmsMessagesMerged(appContext, thread)
            .map { it.toWearMessage(thread, disappearing) }
        val page = buildWearThreadPage(
            threadKey = request.threadKey,
            messages = messages,
            beforeTimestampMillis = request.beforeTimestampMillis,
            generatedAtMillis = System.currentTimeMillis(),
        )
        node.sendMessage(
            WearCommunicateProtocol.THREAD_PAGE_PATH,
            WearCommunicateProtocol.json.encodeToString(page).encodeToByteArray(),
            requireAck = true,
        )
    }

    /** WhatsApp is the line whose unified cache knows its disappearing timer today. */
    private suspend fun isDisappearing(thread: SmsThread): Boolean {
        if (thread.line != CommunicateLine.WhatsApp) return false
        val jid = thread.remoteId?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            WhatsAppDatabase.getDatabase(appContext).conversationDao()
                .getConversation(jid)?.ephemeralExpiration ?: 0L
        }.getOrDefault(0L) > 0L
    }

    private fun SmsThread.toWearThread(disappearing: Boolean): WearThread = WearThread(
        key = wearThreadKey(line.toWearLine(), remoteId, threadId),
        threadId = threadId,
        line = line.toWearLine(),
        address = address,
        remoteId = remoteId,
        subscriptionId = subscriptionId,
        displayName = displayName ?: groupTitle ?: address,
        snippet = snippet,
        timestampMillis = timestampMillis,
        unreadCount = unreadCount,
        isGroup = isGroup,
        groupTitle = groupTitle,
        participants = participants,
        disappearing = disappearing,
    )

    private fun SmsMessage.toWearMessage(thread: SmsThread, disappearing: Boolean): WearMessage {
        // Media crosses as a label only: no content URI, no mediaUrl from serviceData.
        val mediaLabel = when {
            attachments.isNotEmpty() -> mediaLabelFor(attachments.first().mimeType)
            WhatsAppServiceData.parse(serviceData)?.mediaMime != null ->
                mediaLabelFor(WhatsAppServiceData.parse(serviceData)?.mediaMime)
            SignalServiceData.parse(serviceData)?.mediaMime != null ->
                mediaLabelFor(SignalServiceData.parse(serviceData)?.mediaMime)
            else -> null
        }
        return WearMessage(
            messageKey = remoteId?.takeIf { it.isNotBlank() }
                ?: "${wearThreadKey(thread.line.toWearLine(), thread.remoteId, thread.threadId)}:$id",
            threadKey = wearThreadKey(thread.line.toWearLine(), thread.remoteId, thread.threadId),
            body = body,
            timestampMillis = timestampMillis,
            outgoing = outgoing,
            read = read,
            senderLabel = senderAddress.takeIf { thread.isGroup && !outgoing },
            mediaLabel = mediaLabel,
            status = status.name,
            disappearing = disappearing,
        )
    }

    private fun CommunicateCallLogEntry.toWearCall(): com.vayunmathur.communicate.domain.WearCallEntry =
        com.vayunmathur.communicate.domain.WearCallEntry(
            key = "${line.name}:$id",
            displayName = displayName ?: phoneNumber,
            phoneNumber = phoneNumber,
            type = type.name,
            timestampMillis = timestampMillis,
            durationSeconds = durationSeconds,
            line = line.toWearLine(),
        )

    private fun VoicemailEntry.toWearVoicemail(): com.vayunmathur.communicate.domain.WearVoicemail =
        com.vayunmathur.communicate.domain.WearVoicemail(
            key = wearVoicemailKey(this),
            phoneNumber = phoneNumber,
            timestampMillis = timestampMillis,
            durationSeconds = durationSeconds,
            transcript = transcript.orEmpty(),
            isRead = isRead,
            line = line.toWearLine(),
        )
}

/** Voicemail watch key; never the content URI or GV remote media reference. */
internal fun wearVoicemailKey(entry: VoicemailEntry): String =
    "${entry.line.name}:${entry.remoteId ?: entry.id}"

/** Incoming-call watch key from the registry's line + peer id. */
internal fun wearCallKey(line: CommunicateLine?, peerId: String): String =
    "${line?.name ?: WearCommLine.Sim.name}:$peerId"

internal fun CommunicateLine.toWearLine(): WearCommLine = WearCommLine.valueOf(name)

private fun mediaLabelFor(mimeType: String?): String = when {
    mimeType == null -> "File"
    mimeType.startsWith("image/") -> "Photo"
    mimeType.startsWith("video/") -> "Video"
    mimeType.startsWith("audio/") -> "Voice message"
    else -> "File"
}
