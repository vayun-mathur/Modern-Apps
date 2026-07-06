package com.vayunmathur.messages.meta

import android.content.Context
import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// TODO: E2EE support — see MetaClient.kt for details

object InstagramClient {

    private const val TAG = "InstagramClient"

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.INSTAGRAM

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private var authData: MetaAuthData? = null
    private var mqttClient: MetaMqttClient? = null
    private var db: MetaDatabase? = null
    private var backfillJob: Job? = null

    // Web bootstrap (#5) + media upload (#14) need an HTTP client and the parsed config.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    private var config: MetaConfig = MetaConfig()

    private val pendingMessages = ConcurrentHashMap<Int, (String?) -> Unit>()

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        db = MetaDatabase.getDatabase(appContext)
        Log.i(TAG, "init")
        runBlocking {
            val auth = MetaAuthData.load(appContext, MetaAuthData.Platform.INSTAGRAM)
            if (auth != null && auth.isValid()) {
                authData = auth
                _state.value = State.Connecting
            } else {
                _state.value = State.NeedsSetup
            }
        }
    }

    fun start() {
        if (!initialized.get()) return
        if (_state.value is State.Connected) return
        scope.launch {
            val auth = MetaAuthData.load(appContext, MetaAuthData.Platform.INSTAGRAM)
                ?: run {
                    _state.value = State.NeedsSetup
                    return@launch
                }
            if (!auth.isValid()) {
                _state.value = State.NeedsSetup
                return@launch
            }
            authData = auth
            connect(auth)
        }
    }

    fun stop() {
        Log.i(TAG, "stop — clearing Instagram session")
        backfillJob?.cancel()
        mqttClient?.disconnect()
        mqttClient = null
        scope.launch { MetaAuthData.clear(appContext, MetaAuthData.Platform.INSTAGRAM) }
        _state.value = State.NeedsSetup
    }

    fun saveAuthData(cookies: Map<String, String>) {
        scope.launch {
            val userId = cookies["ds_user_id"] ?: return@launch
            val auth = MetaAuthData(
                platform = MetaAuthData.Platform.INSTAGRAM,
                userId = userId,
                cookies = cookies
            )
            MetaAuthData.save(appContext, auth)
            authData = auth
            connect(auth)
        }
    }

    private suspend fun connect(auth: MetaAuthData) {
        _state.value = State.Connecting

        // #5: bootstrap web config before opening the realtime socket.
        config = MetaBootstrap.load(auth, httpClient)

        mqttClient = MetaMqttClient(auth, config).apply {
            scope.launch {
                connectionState.collect { state ->
                    when (state) {
                        is MetaMqttClient.ConnectionState.Connected -> {
                            _state.value = State.Connected
                            kickoffBackfill()
                        }
                        is MetaMqttClient.ConnectionState.Disconnected -> {
                            _state.value = State.Disconnected(state.reason)
                        }
                        is MetaMqttClient.ConnectionState.Connecting -> {
                            _state.value = State.Connecting
                        }
                        else -> {}
                    }
                }
            }

            scope.launch {
                messages.collect { mqttMessage ->
                    handleIncomingMessage(mqttMessage)
                }
            }

            connect()
        }
    }

    private fun handleIncomingMessage(mqttMessage: MetaProtocol.MqttMessage) {
        scope.launch {
            try {
                when (mqttMessage.topic) {
                    MetaProtocol.TOPIC_LS_RESP -> {
                        val responseData = MetaProtocol.parsePublishResponse(mqttMessage.payload)
                            ?: return@launch
                        val decodedEvents = LightspeedDecoder.decodePublishResponse(
                            responseData.payload,
                            responseData.sp,
                        )
                        val incomingEvents = MetaProtocol.parseAllEvents(decodedEvents)
                        for (evt in incomingEvents) {
                            when (evt) {
                                is MetaProtocol.IncomingEvent.MessageReceived -> {
                                    val msg = evt.message
                                    // 1:1 peerName = the peer's NAME only (never the numeric
                                    // senderId, which would clobber the ConversationUpdate title via
                                    // SessionManager's `peerName ?: existing`). Group: peerName null
                                    // (keep the group title), sender goes in senderName/senderId.
                                    val peerName = if (msg.isGroup) null else msg.senderName
                                    _events.emit(
                                        GMEvent.IncomingMessage(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${msg.threadId}",
                                            messageId = msg.messageId,
                                            body = msg.text,
                                            peerName = peerName,
                                            peerPhone = null,
                                            timestamp = msg.timestamp,
                                            senderName = if (msg.isGroup) msg.senderName else null,
                                            senderId = if (msg.isGroup) msg.senderId else null,
                                            attachments = MetaProtocol.toSharedAttachments(msg.attachments),
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.MessageEdited -> {
                                    _events.emit(
                                        GMEvent.MessageEdited(
                                            source = MessageSource.INSTAGRAM,
                                            messageId = evt.messageId,
                                            newBody = evt.newText,
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.MessageDeleted -> {
                                    _events.emit(
                                        GMEvent.MessageDeleted(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${evt.threadId}",
                                            messageId = evt.messageId,
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.ReactionReceived -> {
                                    _events.emit(
                                        GMEvent.ReactionReceived(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${evt.threadId}",
                                            messageId = evt.messageId,
                                            senderId = evt.senderId,
                                            emoji = evt.reaction,
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.ReactionRemoved -> {
                                    _events.emit(
                                        GMEvent.ReactionRemoved(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${evt.threadId}",
                                            messageId = evt.messageId,
                                            senderId = evt.senderId,
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.ReadReceipt -> {
                                    _events.emit(
                                        GMEvent.ReadReceipt(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${evt.threadId}",
                                            senderId = evt.senderId,
                                            timestampMs = evt.watermarkTimestampMs,
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.TypingIndicator -> {
                                    _events.emit(
                                        GMEvent.TypingIndicator(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${evt.threadId}",
                                            senderId = evt.senderId,
                                            isTyping = evt.isTyping,
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.ThreadNameChanged -> {
                                    _events.emit(
                                        GMEvent.ConversationNameChanged(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${evt.threadId}",
                                            newName = evt.newName,
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.ThreadImageChanged -> {
                                    _events.emit(
                                        GMEvent.ConversationAvatarChanged(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${evt.threadId}",
                                            avatarUrl = evt.imageUrl,
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.ParticipantAdded -> {
                                    _events.emit(
                                        GMEvent.ParticipantAdded(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${evt.threadId}",
                                            participantId = evt.participantId,
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.ParticipantRemoved -> {
                                    if (evt.participantId == authData?.userId) {
                                        _events.emit(
                                            GMEvent.ConversationDeleted(
                                                source = MessageSource.INSTAGRAM,
                                                conversationId = "ig:${evt.threadId}",
                                            )
                                        )
                                    } else {
                                        _events.emit(
                                            GMEvent.ParticipantRemoved(
                                                source = MessageSource.INSTAGRAM,
                                                conversationId = "ig:${evt.threadId}",
                                                participantId = evt.participantId,
                                            )
                                        )
                                    }
                                }
                                is MetaProtocol.IncomingEvent.ThreadMuteChanged -> {
                                    _events.emit(
                                        GMEvent.MuteSettingChanged(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${evt.threadId}",
                                            muteExpireTimeMs = evt.muteExpireTimeMs,
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.ThreadDeleted -> {
                                    _events.emit(
                                        GMEvent.ConversationDeleted(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${evt.threadId}",
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.MessageRequestReceived -> {
                                    _events.emit(
                                        GMEvent.MessageRequestReceived(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${evt.threadId}",
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.ThreadSynced -> {
                                    _events.emit(
                                        GMEvent.ConversationUpdate(
                                            source = MessageSource.INSTAGRAM,
                                            conversationId = "ig:${evt.threadId}",
                                            peerName = evt.threadName,
                                            peerPhone = null,
                                            avatarUrl = null,
                                            lastPreview = null,
                                            lastTimestamp = evt.lastActivityTimestampMs,
                                            unreadCount = 0,
                                            isGroup = evt.isGroup,
                                            serviceData = MetaProtocol.buildParticipantNamesServiceData(evt.participantNames),
                                        )
                                    )
                                }
                                is MetaProtocol.IncomingEvent.ThreadVerified -> {
                                    Log.d(TAG, "Thread verified: ${evt.threadId} type=${evt.threadType} folder=${evt.folderName}")
                                }
                                is MetaProtocol.IncomingEvent.FolderSynced -> {
                                    Log.d(TAG, "Folder synced: ${evt.threadId}")
                                }
                                is MetaProtocol.IncomingEvent.ThreadMovedToE2EECutover -> {
                                    Log.d(TAG, "Thread moved to E2EE cutover: ${evt.threadId}")
                                }
                            }
                        }
                    }

                    MetaProtocol.TOPIC_THREAD_TYPING,
                    MetaProtocol.TOPIC_ORCA_TYPING_NOTIFICATIONS -> {
                        val json = Json { ignoreUnknownKeys = true }
                        val payload = String(mqttMessage.payload, Charsets.UTF_8)
                        val obj = json.parseToJsonElement(payload).jsonObject
                        val senderId = obj["sender_fbid"]?.jsonPrimitive?.content ?: return@launch
                        val state = obj["state"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@launch
                        val threadId = obj["thread"]?.jsonPrimitive?.content ?: senderId
                        _events.emit(
                            GMEvent.TypingIndicator(
                                source = MessageSource.INSTAGRAM,
                                conversationId = "ig:$threadId",
                                senderId = senderId,
                                isTyping = state == 1,
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle incoming message", e)
            }
        }
    }

    private fun kickoffBackfill() {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            Log.i(TAG, "Backfill: syncing full thread list via Lightspeed (paginated)")
            val client = mqttClient ?: return@launch
            // The real thread rows (deleteThenInsertThread) live in the page-embedded snapshot, not
            // the socket (which only carries ranges). Emit the snapshot through the normal /ls_resp
            // decode+emit path so the inbox populates.
            config.initialSnapshotPayload?.let { snapshot ->
                try {
                    val bytes = MetaProtocol.buildInitialSnapshotLsResp(snapshot, config.initialSnapshotSp)
                    client.emitForProcessing(MetaProtocol.MqttMessage(MetaProtocol.TOPIC_LS_RESP, bytes))
                } catch (e: Exception) {
                    Log.e(TAG, "Initial snapshot emit failed", e)
                }
                // Background: pull a recent history page per thread so conversations show more than
                // just the last message.
                scope.launch {
                    try {
                        client.backfillRecentMessages(snapshot, config.initialSnapshotSp, config.mailboxCursor)
                    } catch (e: Exception) {
                        Log.e(TAG, "Recent-message backfill failed", e)
                    }
                }
            }
            try {
                client.backfillThreads()
            } catch (e: Exception) {
                Log.e(TAG, "Backfill failed", e)
            }
        }
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = mqttClient ?: return false

        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false

        val payload = MetaProtocol.buildSendMessagePayload(threadId, body, client.versionId)
        var response = client.makeLSRequest(payload, 3)

        var retryCount = 0
        while (response == null && retryCount < 5) {
            retryCount++
            Log.w(TAG, "Send failed, retry $retryCount/5")
            kotlinx.coroutines.delay(1000)
            if (_state.value is State.Disconnected) {
                val auth = authData ?: return false
                connect(auth)
                kotlinx.coroutines.delay(2000)
            }
            val retryClient = mqttClient ?: return false
            response = retryClient.makeLSRequest(payload, 3)
        }

        if (response != null) {
            val responseData = MetaProtocol.parsePublishResponse(response.payload)
            if (responseData != null) {
                val decoded = LightspeedDecoder.decodePublishResponse(responseData.payload, responseData.sp)
                val events = MetaProtocol.parseAllEvents(decoded)
                Log.d(TAG, "Send response contained ${events.size} events")
            }
        }
        return response != null
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String?
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = mqttClient ?: return false
        val auth = authData ?: return false
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false

        // #14: real rupload media upload (replaces the base64 hack).
        val fbid = MetaMediaUpload.upload(
            authData = auth,
            config = config,
            threadId = threadId,
            bytes = bytes,
            mimeType = mimeType,
            fileName = fileName,
            httpClient = httpClient,
        )
        if (fbid == null) {
            Log.e(TAG, "Media upload failed for $conversationId")
            return false
        }
        val payload = MetaProtocol.buildSendMediaPayload(
            threadId = threadId,
            attachmentFbIds = listOf(fbid),
            text = "",
            mimeType = mimeType,
            fileName = fileName,
            versionId = client.versionId,
        )
        val response = client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK)
        return response != null
    }

    suspend fun markRead(conversationId: String) {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return
        val client = mqttClient ?: return

        val payload = MetaProtocol.buildMarkReadPayload(threadId, client.versionId)
        client.makeLSRequest(payload, 3)
    }

    // Read receipts follow the Instagram web client: marking a thread read (ThreadMarkReadTask,
    // task 21) is what notifies the other side. There is no separate messagix privacy query for
    // this, so the toggle is an internal flag (default on) — when off we suppress the network send
    // but still report success, per the integrator read-receipt contract.
    @Volatile
    var readReceiptsEnabled: Boolean = true

    suspend fun sendReadReceipt(
        conversationId: String,
        lastMessageId: String?,
        lastTimestamp: Long,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        if (!readReceiptsEnabled) return true
        // The stored id is source-prefixed ("ig:mid.$…"); strip the prefix so parseMessageId
        // (which expects a bare "mid.$…") can read the embedded timestamp instead of failing
        // and silently falling back to the row timestamp.
        val rawMessageId = lastMessageId?.substringAfter(':', lastMessageId)
        val watermark = (rawMessageId?.let { MetaProtocol.parseMessageId(it) })
            ?: lastTimestamp.takeIf { it > 0 }
            ?: System.currentTimeMillis()
        val payload = MetaProtocol.buildMarkReadPayload(threadId, client.versionId, watermark)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun sendReaction(conversationId: String, messageId: String, emoji: String) {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return
        val client = mqttClient ?: return
        val actorId = authData?.userId?.toLongOrNull() ?: return

        val strippedEmoji = MetaProtocol.removeVariationSelectors(emoji)
        val payload = MetaProtocol.buildReactionPayload(
            threadKey = threadId,
            messageId = messageId,
            reaction = strippedEmoji,
            actorId = actorId,
            versionId = client.versionId,
        )
        client.makeLSRequest(payload, 3)
    }

    suspend fun deleteMessage(conversationId: String, messageId: String): Boolean {
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildDeleteMessagePayload(messageId, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun deleteMessageMeOnly(conversationId: String, messageId: String): Boolean {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildDeleteMessageMeOnlyPayload(threadId, messageId, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun editMessage(conversationId: String, messageId: String, text: String): Boolean {
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildEditMessagePayload(messageId, text, client.versionId)
        var response = client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK)
        if (response == null) {
            Log.w(TAG, "Edit message first attempt failed, retrying — messageId=$messageId")
            kotlinx.coroutines.delay(5000)
            response = client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK)
        }
        return response != null
    }

    suspend fun deleteThread(conversationId: String): Boolean {
        // Go bridge uses Instagram.DeleteThread() GraphQL API for Instagram.
        // TODO: Implement proper Instagram GraphQL API call (client.Instagram.DeleteThread).
        // Socket task fallback may not work reliably for IG.
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildDeleteThreadPayload(threadId, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun muteThread(conversationId: String, muteExpireTimeMs: Long): Boolean {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildMuteThreadPayload(threadId, muteExpireTimeMs, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun renameThread(conversationId: String, threadName: String): Boolean {
        // Go bridge uses Instagram.EditGroupTitle() GraphQL API for Instagram.
        // TODO: Implement proper Instagram GraphQL API call.
        // Socket task fallback is used until GraphQL is available.
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildRenameThreadPayload(threadId, threadName, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun fetchMessages(conversationId: String, referenceTimestampMs: Long, referenceMessageId: String) {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return
        val client = mqttClient ?: return
        val payload = MetaProtocol.buildFetchMessagesPayload(threadId, referenceTimestampMs, referenceMessageId, client.versionId, client.mailboxCursor())
        client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK)
            ?.let { client.emitForProcessing(it) }
    }

    suspend fun sendTypingIndicator(conversationId: String, isTyping: Boolean, isGroup: Boolean = false): Boolean {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildTypingIndicatorPayload(
            threadKey = threadId,
            isTyping = isTyping,
            isGroup = isGroup,
            versionId = client.versionId,
        )
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_STATELESS) != null
    }

    suspend fun addParticipant(conversationId: String, participantIds: List<Long>): Boolean {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildAddParticipantsPayload(threadId, participantIds, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun removeParticipant(conversationId: String, participantId: Long): Boolean {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildRemoveParticipantPayload(threadId, participantId, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun setThreadImage(conversationId: String, imageId: Long): Boolean {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildSetThreadImagePayload(threadId, imageId, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun searchUsers(query: String): Boolean {
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildSearchUserPayload(query, client.versionId, isMessenger = false)
        scope.launch {
            kotlinx.coroutines.delay(10)
            val secondaryPayload = MetaProtocol.buildSearchUserSecondaryPayload(query, client.versionId, isMessenger = false)
            client.makeLSRequest(secondaryPayload, MetaProtocol.LS_REQUEST_TYPE_TASK)
        }
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun createGroup(participantIds: List<Long>): Boolean {
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildCreateGroupPayload(participantIds, client.versionId)
        val response = client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) ?: return false
        val responseData = MetaProtocol.parsePublishResponse(response.payload)
        if (responseData != null) {
            val decoded = LightspeedDecoder.decodePublishResponse(responseData.payload, responseData.sp)
            val events = MetaProtocol.parseAllEvents(decoded)
            for (evt in events) {
                if (evt is MetaProtocol.IncomingEvent.MessageReceived) {
                    Log.d(TAG, "Group created with thread ${evt.message.threadId}")
                }
            }
        }
        return true
    }

    /**
     * Send a poll (Instagram CreatePollTask). [allowMultiple] is part of the shared contract;
     * the Meta poll task schema has no multiple-choice field, so it is currently advisory only.
     */
    suspend fun sendPoll(
        conversationId: String,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
    ): Boolean {
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildCreatePollPayload(
            threadKey = threadId,
            question = question,
            options = options,
            versionId = client.versionId,
            allowMultiple = allowMultiple,
        )
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    suspend fun acceptMessageRequest(conversationId: String): Boolean {
        // Go bridge uses Instagram.AcceptMessageRequest() GraphQL API.
        // TODO: Implement proper Instagram GraphQL API call.
        // Socket task fallback is used until GraphQL is available.
        val threadId = extractThreadId(conversationId)?.toLongOrNull() ?: return false
        val client = mqttClient ?: return false
        val payload = MetaProtocol.buildAcceptMessageRequestPayload(threadId, client.versionId)
        return client.makeLSRequest(payload, MetaProtocol.LS_REQUEST_TYPE_TASK) != null
    }

    private fun extractThreadId(conversationId: String): String? {
        // Conversation rows are source-prefixed, and Instagram emits its own
        // "ig:" prefix which handleEvent prefixes again ("ig:ig:<threadId>").
        // Take the final segment so we get the bare numeric thread id whether
        // the id is singly or doubly prefixed.
        return conversationId.substringAfterLast(':')
    }
}
