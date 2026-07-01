package com.vayunmathur.messages.telegram

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.telegram.api.TlRegistry
import com.vayunmathur.messages.telegram.api.functions.*
import com.vayunmathur.messages.telegram.api.types.*
import com.vayunmathur.messages.telegram.mtproto.TelegramApiClient
import com.vayunmathur.messages.telegram.mtproto.crypto.Srp
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject
import com.vayunmathur.messages.telegram.mtproto.tl.TlMethod
import com.vayunmathur.messages.util.ContactSuggestion
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

object TelegramClient {

    private const val TAG = "TelegramClient"
    private const val API_ID = 94575
    private const val API_HASH = "a3406de8d171bb422bb6ddf3bbd800e2"

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data object Connecting : State
        data object Connected : State
        data class AwaitingCode(val phone: String) : State
        data class AwaitingQrScan(val qrUrl: String) : State
        data class AwaitingPassword(val phone: String, val hint: String) : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.TELEGRAM

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    private lateinit var appContext: Context
    private var apiClient: TelegramApiClient? = null
    private var backfillJob: Job? = null
    private var updateJob: Job? = null
    private var qrRefreshJob: Job? = null
    private var pendingPhone: String? = null
    private var phoneCodeHash: String? = null

    private val peerCache = ConcurrentHashMap<Long, TlObject>()
    private val userNameCache = ConcurrentHashMap<Long, String>()
    private val channelMetaCache = ConcurrentHashMap<Long, Channel>()
    // peer id -> profile photo (photo_id + dc_id), captured during TL decode
    private val photoCache = ConcurrentHashMap<Long, ProfilePhoto>()
    // dc id -> authorized secondary client for cross-DC file downloads
    private val dcClients = ConcurrentHashMap<Int, TelegramApiClient>()
    private val avatarPathCache = ConcurrentHashMap<Long, String>()
    private var reconnectAttempt = 0
    private var isPremium = false

    private const val MAX_MESSAGE_LENGTH = 4096
    private const val MAX_CAPTION_LENGTH = 1024
    private const val MAX_CAPTION_LENGTH_PREMIUM = 4096
    private const val MAX_IMAGE_FILE_SIZE = 10 * 1024 * 1024
    private const val MAX_IMAGE_ASPECT_RATIO = 20.0
    private const val MAX_IMAGE_DIMENSION_SUM = 10000
    private const val MAX_IMAGE_PIXELS = 10000 * 10000L

    private var currentPts = 0
    private var currentQts = 0
    private var currentDate = 0
    private var currentSeq = 0
    // per-channel pts for channel update gap detection
    private val channelPts = ConcurrentHashMap<Long, Int>()
    // guards against overlapping getDifference / getChannelDifference runs
    private val gapRecovering = AtomicBoolean(false)
    private val channelGapRecovering = ConcurrentHashMap<Long, Boolean>()

    private enum class PtsAction { APPLY, SKIP, GAP }

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Log.i(TAG, "init")
        scope.launch {
            val auth = TelegramAuthData.load(appContext)
            if (auth?.loggedIn == true && auth.authKey != null) {
                Log.i(TAG, "resuming from persisted session")
                bootSession(auth)
            } else {
                _state.value = State.NeedsSetup
            }
        }
    }

    fun start() {
        if (!initialized.get()) return
        if (_state.value is State.Connected) return
        scope.launch {
            val auth = TelegramAuthData.load(appContext)
            if (auth?.loggedIn == true && auth.authKey != null) bootSession(auth)
            else _state.value = State.NeedsSetup
        }
    }

    fun stop() {
        Log.i(TAG, "stop — clearing Telegram session")
        backfillJob?.cancel()
        updateJob?.cancel()
        qrRefreshJob?.cancel()
        scope.launch {
            runCatching { apiClient?.invoke(AuthLogOut) { TlRegistry.decode(it) } }
            apiClient?.disconnect()
            apiClient = null
            dcClients.values.forEach { runCatching { it.disconnect() } }
            dcClients.clear()
        }
        pendingPhone = null
        phoneCodeHash = null
        peerCache.clear()
        userNameCache.clear()
        channelMetaCache.clear()
        photoCache.clear()
        avatarPathCache.clear()
        channelPts.clear()
        scope.launch { TelegramAuthData.clear(appContext) }
        _state.value = State.NeedsSetup
    }

    fun submitPhoneNumber(phone: String) {
        pendingPhone = phone
        _state.value = State.Connecting
        scope.launch {
            try {
                val client = ensureClient()
                val initReq = InitConnection(
                    apiId = API_ID,
                    deviceModel = "Android",
                    systemVersion = "14",
                    appVersion = "1.0",
                    systemLangCode = "en",
                    langPack = "",
                    langCode = "en",
                    inner = AuthSendCode(phone, API_ID, API_HASH),
                )
                val result = client.invoke(initReq) { TlRegistry.decode(it) }
                if (result is AuthSentCode) {
                    phoneCodeHash = result.phoneCodeHash
                    _state.value = State.AwaitingCode(phone)
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitPhoneNumber failed", e)
                _state.value = State.Disconnected("Auth failed: ${e.message}")
            }
        }
    }

    fun submitCode(code: String) {
        val phone = pendingPhone ?: return
        val hash = phoneCodeHash ?: return
        scope.launch {
            try {
                val client = apiClient ?: return@launch
                val result = client.invoke(AuthSignIn(phone, hash, code)) { TlRegistry.decode(it) }
                when (result) {
                    is AuthAuthorization -> onAuthorized(result.user)
                    is AuthPassword -> {
                        val hint = try {
                            val pwd = client.invoke(AccountGetPassword) { TlRegistry.decode(it) }
                            if (pwd is AuthPassword) pwd.hint else ""
                        } catch (_: Exception) { "" }
                        _state.value = State.AwaitingPassword(phone, hint)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitCode failed: ${e.message}")
                if (e.message?.contains("SESSION_PASSWORD_NEEDED") == true) {
                    val hint = try {
                        val pwd = apiClient?.invoke(AccountGetPassword) { TlRegistry.decode(it) }
                        if (pwd is AuthPassword) pwd.hint else ""
                    } catch (_: Exception) { "" }
                    _state.value = State.AwaitingPassword(phone, hint)
                } else {
                    _state.value = State.AwaitingCode(phone)
                }
            }
        }
    }

    fun submitPassword(password: String) {
        val phone = pendingPhone ?: ""
        scope.launch {
            try {
                val client = apiClient ?: return@launch
                val pwd = client.invoke(AccountGetPassword) { TlRegistry.decode(it) }
                if (pwd !is AuthPassword) return@launch
                val algo = pwd.currentAlgo ?: return@launch
                val randomA = ByteArray(256).also { random.nextBytes(it) }
                val answer = Srp.computeAnswer(password.toByteArray(), pwd.srpB, randomA, algo.salt1, algo.salt2, algo.g, algo.p)
                val inputPassword = InputCheckPasswordSRP(pwd.srpId, answer.a, answer.m1)
                val result = client.invoke(AuthCheckPassword(inputPassword)) { TlRegistry.decode(it) }
                if (result is AuthAuthorization) {
                    onAuthorized(result.user)
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitPassword failed: ${e.message}")
                _state.value = State.AwaitingPassword(phone, "")
            }
        }
    }

    // ----------------------------------------------------------------
    // QR-code login (auth.exportLoginToken / importLoginToken)
    // Mirrors tdesktop intro_qr.cpp + gotd exportLoginToken handling.
    // Enables logging in by scanning a QR with another Telegram device,
    // which (unlike SMS login) authorizes secret/encrypted chats.
    // ----------------------------------------------------------------

    /**
     * Begin the QR login flow: connect, request a login token, and surface the
     * `tg://login?token=…` URL via [State.AwaitingQrScan] for the UI to render as
     * a QR bitmap. The phone-number flow (submitPhoneNumber/…) remains available
     * as a fallback.
     */
    fun startQrLogin() {
        if (!initialized.get()) return
        _state.value = State.Connecting
        scope.launch {
            try {
                val client = ensureClient()
                exportLoginToken(client, firstCall = true)
            } catch (e: Exception) {
                Log.e(TAG, "startQrLogin failed", e)
                _state.value = State.Disconnected("QR login failed: ${e.message}")
            }
        }
    }

    private suspend fun exportLoginToken(client: TelegramApiClient, firstCall: Boolean) {
        val export = AuthExportLoginToken(API_ID, API_HASH)
        // The first request on a fresh connection must carry initConnection/invokeWithLayer.
        val method: TlMethod<TlObject> = if (firstCall) InitConnection(
            apiId = API_ID,
            deviceModel = "Android",
            systemVersion = "14",
            appVersion = "1.0",
            systemLangCode = "en",
            langPack = "",
            langCode = "en",
            inner = export,
        ) else export
        try {
            val result = client.invoke(method) { TlRegistry.decode(it) }
            handleLoginToken(result)
        } catch (t: Throwable) {
            handleQrError(t)
        }
    }

    private suspend fun refreshLoginToken() {
        val client = apiClient ?: return
        if (_state.value !is State.AwaitingQrScan) return
        exportLoginToken(client, firstCall = false)
    }

    private suspend fun handleLoginToken(result: TlObject) {
        when (result) {
            is AuthLoginTokenResult -> {
                val encoded = Base64.encodeToString(result.token, Base64.URL_SAFE or Base64.NO_WRAP)
                _state.value = State.AwaitingQrScan("tg://login?token=$encoded")
                scheduleQrRefresh(result.expires)
            }
            is AuthLoginTokenMigrateTo -> migrateAndImport(result.dcId, result.token)
            is AuthLoginTokenSuccess -> {
                qrRefreshJob?.cancel()
                when (val auth = result.authorization) {
                    is AuthAuthorization -> onAuthorized(auth.user)
                    else -> {
                        Log.w(TAG, "QR login returned ${auth::class.simpleName}; sign-up required")
                        _state.value = State.Disconnected("This account needs to be set up in an official Telegram app first")
                    }
                }
            }
            else -> {
                Log.w(TAG, "Unexpected login-token result: ${result::class.simpleName}")
                _state.value = State.Disconnected("QR login failed: unexpected response")
            }
        }
    }

    /**
     * Re-import the login token on the DC named by loginTokenMigrateTo. A fresh
     * connection there performs its own key exchange (each DC has a distinct
     * auth key); importLoginToken then yields loginTokenSuccess.
     */
    private suspend fun migrateAndImport(dcId: Int, token: ByteArray) {
        Log.i(TAG, "QR login migrating to DC $dcId")
        qrRefreshJob?.cancel()
        try {
            apiClient?.disconnect()
            val client = TelegramApiClient()
            client.onDisconnected = { handleDisconnect() }
            client.connect(dcId)
            apiClient = client
            startUpdateListener(client)
            val result = client.invoke(AuthImportLoginToken(token)) { TlRegistry.decode(it) }
            handleLoginToken(result)
        } catch (t: Throwable) {
            handleQrError(t)
        }
    }

    private fun scheduleQrRefresh(expires: Int) {
        qrRefreshJob?.cancel()
        qrRefreshJob = scope.launch {
            val nowSec = System.currentTimeMillis() / 1000
            val delaySec = (expires - nowSec).coerceIn(1, 60)
            delay(delaySec * 1000)
            if (_state.value is State.AwaitingQrScan) {
                runCatching { refreshLoginToken() }
            }
        }
    }

    private suspend fun handleQrError(t: Throwable) {
        // Account is 2FA-protected: fall back to the existing SRP password flow.
        if (t.message?.contains("SESSION_PASSWORD_NEEDED") == true) {
            qrRefreshJob?.cancel()
            val hint = runCatching {
                val pwd = apiClient?.invoke(AccountGetPassword) { TlRegistry.decode(it) }
                if (pwd is AuthPassword) pwd.hint else ""
            }.getOrDefault("")
            _state.value = State.AwaitingPassword(pendingPhone ?: "", hint)
            return
        }
        Log.w(TAG, "QR login error: ${humanizeError(t)}")
        _state.value = State.Disconnected("QR login failed: ${humanizeError(t)}")
    }

    fun forceResync() {
        if (_state.value !is State.Connected) return
        kickoffBackfill()
    }

    suspend fun sendMessage(
        conversationId: String,
        body: String,
        noWebpage: Boolean = false,
        entities: List<MessageEntity> = emptyList(),
        replyToMsgId: Int = 0,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        val parsed = if (entities.isEmpty()) parseMarkdown(body) else null
        val resolvedEntities = parsed?.entities ?: entities
        var cleanBody = parsed?.text ?: body
        if (cleanBody.length > MAX_MESSAGE_LENGTH) {
            Log.w(TAG, "Message truncated from ${cleanBody.length} to $MAX_MESSAGE_LENGTH chars")
            cleanBody = cleanBody.take(MAX_MESSAGE_LENGTH)
        }
        // Include topic ID in replyTo for forums (Issue 12)
        val topicReplyId = extractTopicId(conversationId) ?: replyToMsgId
        return try {
            client.invoke(MessagesSendMessage(peer, cleanBody, random.nextLong(), noWebpage, resolvedEntities, topicReplyId)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendMessage failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun editMessage(
        conversationId: String,
        messageId: Int,
        newText: String,
        noWebpage: Boolean = false,
        entities: List<MessageEntity> = emptyList(),
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            client.invoke(MessagesEditMessage(peer, messageId, newText, noWebpage, entities)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "editMessage failed: ${humanizeError(t)}")
            false
        }
    }

    // Uploads a file's parts concurrently over the single MTProto connection. Each part is an
    // independent RPC (its own msg_id), so several can be in flight at once; a semaphore caps the
    // fan-out to avoid flooding the connection. Mirrors the parallel upload.saveFilePart behaviour
    // of the Go telegram bridge. Returns the number of parts uploaded. // UNVERIFIED runtime
    private suspend fun uploadFileParts(
        client: TelegramApiClient,
        fileId: Long,
        bytes: ByteArray,
        useBig: Boolean,
        partSize: Int = 512 * 1024,
        maxParallel: Int = 4,
    ): Int = coroutineScope {
        val parts = (bytes.size + partSize - 1) / partSize
        val semaphore = Semaphore(maxParallel)
        (0 until parts).map { i ->
            async {
                semaphore.withPermit {
                    val start = i * partSize
                    val end = minOf(start + partSize, bytes.size)
                    val chunk = bytes.copyOfRange(start, end)
                    if (useBig) {
                        client.invoke(UploadSaveBigFilePart(fileId, i, parts, chunk)) { TlRegistry.decode(it) }
                    } else {
                        client.invoke(UploadSaveFilePart(fileId, i, chunk)) { TlRegistry.decode(it) }
                    }
                }
            }
        }.awaitAll()
        parts
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String,
        caption: String?,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false

        // Truncate caption to max length (Issue 19)
        val maxLen = if (isPremium) MAX_CAPTION_LENGTH_PREMIUM else MAX_CAPTION_LENGTH
        val truncatedCaption = caption?.let {
            if (it.length > maxLen) {
                Log.w(TAG, "Caption truncated from ${it.length} to $maxLen chars")
                it.take(maxLen)
            } else it
        }

        return try {
            val fileId = random.nextLong()
            val useBig = bytes.size > 10 * 1024 * 1024
            val parts = uploadFileParts(client, fileId, bytes, useBig)

            val inputFile: TlObject = if (useBig) InputFileBig(fileId, parts, fileName)
            else InputFile(fileId, parts, fileName)

            // Check image-as-file threshold (Issue 16)
            val media: TlObject = if (mime.startsWith("image/") && !shouldSendImageAsFile(bytes, mime)) {
                InputMediaUploadedPhoto(inputFile)
            } else {
                val attrs = mutableListOf<TlObject>(DocumentAttributeFilename(fileName))
                when {
                    mime.startsWith("video/") -> attrs.add(DocumentAttributeVideo(0.0, 0, 0))
                    mime.startsWith("audio/") -> attrs.add(DocumentAttributeAudio(0, voice = mime == "audio/ogg"))
                    mime == "image/gif" -> { attrs.add(DocumentAttributeAnimated); attrs.add(DocumentAttributeImageSize(0, 0)) }
                    mime.startsWith("image/") -> {
                        val imgOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, imgOpts)
                        if (imgOpts.outWidth > 0 && imgOpts.outHeight > 0) {
                            attrs.add(DocumentAttributeImageSize(imgOpts.outWidth, imgOpts.outHeight))
                        }
                    }
                }
                InputMediaUploadedDocument(inputFile, mime, attrs)
            }

            client.invoke(MessagesSendMedia(peer, media, truncatedCaption ?: "", random.nextLong())) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendMedia failed: ${humanizeError(t)}")
            false
        }
    }

    /**
     * Send a native Telegram poll via inputMediaPoll (contract §2b). Telegram
     * requires 2–10 non-blank options; out-of-range inputs are rejected
     * client-side. [allowMultiple] maps to Poll.multipleChoice. Returns false on
     * any failure.
     */
    suspend fun sendPoll(
        conversationId: String,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        val cleanOptions = options.map { it.trim() }.filter { it.isNotBlank() }
        if (question.isBlank() || cleanOptions.size < 2 || cleanOptions.size > 10) {
            Log.w(TAG, "sendPoll rejected: question/options out of range (options=${cleanOptions.size})")
            return false
        }
        return try {
            val answers = cleanOptions.mapIndexed { i, opt ->
                PollAnswer(opt, byteArrayOf(i.toByte()))
            }
            val poll = Poll(question = question.trim(), answers = answers, multipleChoice = allowMultiple)
            val media = InputMediaPoll(poll)
            client.invoke(MessagesSendMedia(peer, media, "", random.nextLong())) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendPoll failed: ${humanizeError(t)}")
            false
        }
    }

    private fun shouldSendImageAsFile(bytes: ByteArray, mime: String): Boolean {
        if (bytes.size > MAX_IMAGE_FILE_SIZE) return true
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return false
        val pixels = w.toLong() * h.toLong()
        if (pixels > MAX_IMAGE_PIXELS) return true
        val ratio = w.toDouble() / h.toDouble()
        if (ratio > MAX_IMAGE_ASPECT_RATIO || ratio < (1.0 / MAX_IMAGE_ASPECT_RATIO)) return true
        if (w + h > MAX_IMAGE_DIMENSION_SUM) return true
        return false
    }

    suspend fun markRead(conversationId: String, maxId: Int = Int.MAX_VALUE): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        val topicId = extractTopicId(conversationId) ?: 0
        return try {
            when (peer) {
                is InputPeerChannel -> {
                    val inputChannel = InputChannel(peer.channelId, peer.accessHash)
                    client.invoke(ChannelsReadHistory(inputChannel, maxId)) { TlRegistry.decode(it) }
                }
                else -> {
                    client.invoke(MessagesReadHistory(peer, maxId)) { TlRegistry.decode(it) }
                }
            }
            scope.launch {
                try { client.invoke(MessagesReadMentions(peer, topicId)) { TlRegistry.decode(it) } }
                catch (t: Throwable) { Log.d(TAG, "readMentions: ${t.message}") }
            }
            scope.launch {
                try { client.invoke(MessagesReadReactions(peer, topicId)) { TlRegistry.decode(it) } }
                catch (t: Throwable) { Log.d(TAG, "readReactions: ${t.message}") }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "markRead failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun deleteThread(conversationId: String, revoke: Boolean = false): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        // Check for topic-based deletion (Issue 15)
        val topicId = extractTopicId(conversationId)
        return try {
            when {
                topicId != null && peer is InputPeerChannel -> {
                    val inputChannel = InputChannel(peer.channelId, peer.accessHash)
                    client.invoke(ChannelsDeleteTopicHistory(inputChannel, topicId)) { TlRegistry.decode(it) }
                }
                peer is InputPeerUser -> {
                    client.invoke(MessagesDeleteHistory(peer, revoke = revoke)) { TlRegistry.decode(it) }
                }
                peer is InputPeerChat -> {
                    client.invoke(MessagesDeleteHistory(peer, revoke = revoke)) { TlRegistry.decode(it) }
                    client.invoke(MessagesDeleteChat(peer.chatId)) { TlRegistry.decode(it) }
                }
                peer is InputPeerChannel -> {
                    client.invoke(ChannelsDeleteChannel(
                        InputChannel(peer.channelId, peer.accessHash)
                    )) { TlRegistry.decode(it) }
                }
                else -> {
                    client.invoke(MessagesDeleteHistory(peer, revoke = revoke)) { TlRegistry.decode(it) }
                }
            }
            _events.emit(GMEvent.ConversationDeleted(source, conversationId.substringAfter(':', conversationId)))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "deleteThread failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun sendReaction(
        messageId: String,
        conversationId: String,
        emoji: String,
        add: Boolean,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        val msgId = messageId.substringAfterLast('_').toIntOrNull() ?: return false
        return try {
            val reactions = if (add) {
                if (emoji.startsWith("custom:")) {
                    val docId = emoji.removePrefix("custom:").toLongOrNull() ?: 0L
                    listOf(InputMessageReactionCustomEmoji(docId))
                } else {
                    listOf(InputMessageReactionEmoji(fullyQualifyEmoji(emoji)))
                }
            } else emptyList<TlObject>()
            client.invoke(MessagesSendReaction(peer, msgId, reactions)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendReaction failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun sendTyping(conversationId: String, action: TlObject? = null): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        val topMsgId = extractTopicId(conversationId) ?: 0
        return try {
            client.invoke(MessagesSetTyping(peer, action, topMsgId)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendTyping failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun setDisappearingTimer(conversationId: String, seconds: Int): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        // Topics don't support disappearing timer (Issue 20)
        if (isTopicConversation(conversationId)) {
            Log.d(TAG, "Disappearing timer not supported for topics")
            return false
        }
        return try {
            client.invoke(MessagesSetHistoryTTL(peer, seconds)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setDisappearingTimer failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun setGroupName(conversationId: String, newName: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        val topicId = extractTopicId(conversationId)
        return try {
            when {
                topicId != null && peer is InputPeerChannel -> {
                    client.invoke(MessagesEditForumTopic(peer, topicId, newName)) { TlRegistry.decode(it) }
                }
                peer is InputPeerChat -> {
                    client.invoke(MessagesEditChatTitle(peer.chatId, newName)) { TlRegistry.decode(it) }
                }
                peer is InputPeerChannel -> {
                    client.invoke(ChannelsEditTitle(InputChannel(peer.channelId, peer.accessHash), newName)) { TlRegistry.decode(it) }
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setGroupName failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun togglePin(conversationId: String, pinned: Boolean): Boolean {
        if (_state.value !is State.Connected) return false
        // Topics don't support pin (Issue 21)
        if (isTopicConversation(conversationId)) {
            Log.d(TAG, "Pin not supported for topics")
            return false
        }
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            client.invoke(MessagesToggleDialogPin(peer, pinned)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "togglePin failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun setMute(conversationId: String, muteUntil: Int): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            // Use InputNotifyForumTopic for topics (Issue 21)
            if (isTopicConversation(conversationId)) {
                val topicId = extractTopicId(conversationId)
                if (topicId != null) {
                    client.invoke(AccountUpdateNotifyForumTopic(peer, topicId, muteUntil, muteUntil > 0)) { TlRegistry.decode(it) }
                } else {
                    client.invoke(AccountUpdateNotifySettings(peer, muteUntil, muteUntil > 0)) { TlRegistry.decode(it) }
                }
            } else {
                client.invoke(AccountUpdateNotifySettings(peer, muteUntil, muteUntil > 0)) { TlRegistry.decode(it) }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setMute failed: ${humanizeError(t)}")
            false
        }
    }

    fun fetchMessages(conversationId: String, count: Int = 50) {
        if (_state.value !is State.Connected) return
        scope.launch {
            val client = apiClient ?: return@launch
            val peer = resolvePeer(conversationId) ?: return@launch
            val chatIdStr = conversationId.substringAfter(':', conversationId)
            try {
                val result = client.invoke(MessagesGetHistory(peer, limit = count)) { TlRegistry.decode(it) }
                val messages = extractMessages(result)
                for (msg in messages) {
                    when (msg) {
                        is Message -> _events.emit(msg.toMessageUpdate(chatIdStr))
                        is MessageService -> handleServiceMessageEvent(msg, chatIdStr)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "fetchMessages failed: ${t.message}")
            }
        }
    }

    suspend fun sendNewThread(recipients: List<String>, body: String): String? {
        if (recipients.isEmpty()) return null
        if (_state.value !is State.Connected) return null
        val client = apiClient ?: return null
        return try {
            val found = client.invoke(ContactsSearch(recipients.first())) { TlRegistry.decode(it) }
            val users = extractUsers(found)
            val user = users.filterIsInstance<User>().firstOrNull() ?: return null
            val peer = InputPeerUser(user.id, user.accessHash)
            peerCache[user.id] = peer
            client.invoke(MessagesSendMessage(peer, body, random.nextLong())) { TlRegistry.decode(it) }
            kickoffBackfill()
            "${source.idPrefix}:${user.id}"
        } catch (t: Throwable) {
            Log.w(TAG, "sendNewThread failed: ${humanizeError(t)}")
            null
        }
    }

    suspend fun searchContacts(query: String): List<ContactSuggestion> {
        if (_state.value !is State.Connected) return emptyList()
        val client = apiClient ?: return emptyList()
        return try {
            val result = client.invoke(ContactsSearch(query)) { TlRegistry.decode(it) }
            val users = extractUsers(result)
            users.filterIsInstance<User>().mapNotNull { user ->
                val name = "${user.firstName} ${user.lastName}".trim()
                if (name.isBlank()) return@mapNotNull null
                // Cache peer + photo so the avatar download can resolve the InputPeer.
                peerCache[user.id] = InputPeerUser(user.id, user.accessHash)
                if (user.photoId != 0L) photoCache[user.id] = ProfilePhoto(user.photoId, user.photoDcId)
                val avatar = runCatching { downloadPeerAvatar(user.id) }.getOrNull()
                ContactSuggestion(
                    displayName = name,
                    phoneE164 = user.phone.takeIf { it.isNotBlank() }?.let { "+$it" },
                    avatarUrl = avatar,
                    source = MessageSource.TELEGRAM,
                    username = user.username.takeIf { it.isNotBlank() },
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "searchContacts failed: ${t.message}")
            emptyList()
        }
    }

    // ----------------------------------------------------------------
    // Internals
    // ----------------------------------------------------------------

    private suspend fun ensureClient(): TelegramApiClient {
        apiClient?.let { if (it.isConnected) return it }
        val client = TelegramApiClient()
        client.onDisconnected = { handleDisconnect() }
        client.connect()
        apiClient = client
        startUpdateListener(client)
        return client
    }

    private suspend fun bootSession(auth: TelegramAuthData) {
        _state.value = State.Connecting
        try {
            val authKey = Base64.decode(auth.authKey, Base64.NO_WRAP)
            val authKeyId = Base64.decode(auth.authKeyId, Base64.NO_WRAP)
            val computedId = java.security.MessageDigest.getInstance("SHA-1").digest(authKey)
                .copyOfRange(12, 20)
            require(computedId.contentEquals(authKeyId)) { "Corrupted auth key" }
            val client = TelegramApiClient()
            client.connect(
                dc = auth.dc ?: 2,
                existingAuthKey = authKey,
                existingAuthKeyId = authKeyId,
                existingSalt = auth.salt ?: 0L,
                existingSessionId = auth.sessionId,
            )
            apiClient = client
            client.onDisconnected = { handleDisconnect() }
            // Persist the MTProto session id so reconnects resume the same session
            // (and its server-side state) instead of re-backfilling from scratch.
            if (auth.sessionId == null) {
                runCatching { auth.copy(sessionId = client.sessionId).save(appContext) }
            }
            reconnectAttempt = 0
            _state.value = State.Connected
            startUpdateListener(client)
            kickoffBackfill()
            fetchUpdatesState(client)
        } catch (e: Exception) {
            Log.e(TAG, "bootSession failed: ${e.message}")
            _state.value = State.Disconnected("Boot failed: ${e.message}")
        }
    }

    private fun onAuthorized(user: User) {
        isPremium = user.premium
        _state.value = State.Connected
        reconnectAttempt = 0
        qrRefreshJob?.cancel()
        val client = apiClient ?: return
        scope.launch {
            TelegramAuthData(
                phoneNumber = pendingPhone?.takeIf { it.isNotBlank() }
                    ?: user.phone.takeIf { it.isNotBlank() }?.let { "+${it.trimStart('+')}" }
                    ?: "",
                loggedIn = true,
                authKey = Base64.encodeToString(client.authKey, Base64.NO_WRAP),
                authKeyId = Base64.encodeToString(client.authKeyId, Base64.NO_WRAP),
                salt = client.salt,
                dc = client.dc,
                serverAddress = null,
            ).save(appContext)
        }
        kickoffBackfill()
        scope.launch { fetchUpdatesState(client) }
    }

    private fun startUpdateListener(client: TelegramApiClient) {
        updateJob?.cancel()
        updateJob = scope.launch {
            client.updates.collect { update ->
                handleUpdate(update)
            }
        }
    }

    private fun handleDisconnect() {
        if (_state.value !is State.Connected) return
        _state.value = State.Disconnected("Connection lost")
        scope.launch {
            val client = apiClient ?: return@launch
            val maxDelay = 60_000L
            val baseDelay = 1_000L
            while (true) {
                val delayMs = minOf(baseDelay * (1L shl minOf(reconnectAttempt, 20)), maxDelay)
                reconnectAttempt++
                Log.i(TAG, "Reconnect attempt $reconnectAttempt, delay ${delayMs}ms")
                delay(delayMs)
                try {
                    client.reconnect()
                    reconnectAttempt = 0
                    _state.value = State.Connected
                    startUpdateListener(client)
                    kickoffBackfill()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnect attempt $reconnectAttempt failed: ${e.message}")
                    _state.value = State.Disconnected("Reconnect failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleUpdate(update: TlObject) {
        when (update) {
            is Updates -> {
                if (!checkSeq(update.seq, update.seq)) return
                cacheUsers(update.users)
                cacheChats(update.chats)
                for (u in update.updates) handleSingleUpdate(u)
                currentDate = update.date
            }
            is UpdatesCombined -> {
                if (!checkSeq(update.seqStart, update.seq)) return
                cacheUsers(update.users)
                cacheChats(update.chats)
                for (u in update.updates) handleSingleUpdate(u)
                currentDate = update.date
            }
            is UpdateShort -> {
                handleSingleUpdate(update.update)
                currentDate = update.date
            }
            is UpdateShortMessage -> {
                when (checkCommonPts(update.pts, update.ptsCount)) {
                    PtsAction.SKIP -> {}
                    PtsAction.GAP -> scheduleGapRecovery()
                    PtsAction.APPLY -> {
                        val chatId = update.userId.toString()
                        val msgId = "${chatId}_${update.id}"
                        _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, update.message, update.out, update.date.toLong() * 1000, null))
                        if (!update.out) {
                            _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, update.message, userNameCache[update.userId], null, update.date.toLong() * 1000))
                        }
                    }
                }
            }
            is UpdateShortChatMessage -> {
                when (checkCommonPts(update.pts, update.ptsCount)) {
                    PtsAction.SKIP -> {}
                    PtsAction.GAP -> scheduleGapRecovery()
                    PtsAction.APPLY -> {
                        val chatId = update.chatId.toString()
                        val msgId = "${chatId}_${update.id}"
                        _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, update.message, update.out, update.date.toLong() * 1000, userNameCache[update.fromId]))
                        if (!update.out) {
                            _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, update.message, userNameCache[update.fromId], null, update.date.toLong() * 1000))
                        }
                    }
                }
            }
            is UpdatesTooLong -> scheduleGapRecovery()
        }
    }

    /**
     * Common-state seq gap check for Updates/UpdatesCombined containers.
     * Returns true if the container should be applied. On a gap it triggers getDifference.
     */
    private fun checkSeq(seqStart: Int, seq: Int): Boolean {
        if (seq == 0) return true // seq=0 => no sequencing (apply, don't bump local seq)
        return when {
            currentSeq != 0 && seqStart > currentSeq + 1 -> { scheduleGapRecovery(); false } // gap
            seq <= currentSeq -> false // already applied
            else -> { currentSeq = seq; true }
        }
    }

    private fun checkCommonPts(pts: Int, ptsCount: Int): PtsAction {
        if (currentPts == 0) { currentPts = pts; return PtsAction.APPLY }
        val expected = currentPts + ptsCount
        return when {
            expected == pts -> { currentPts = pts; PtsAction.APPLY }
            expected > pts -> PtsAction.SKIP // duplicate / already applied
            else -> PtsAction.GAP // local_pts + pts_count < pts => missing updates
        }
    }

    private fun checkChannelPts(channelId: Long, pts: Int, ptsCount: Int): PtsAction {
        val local = channelPts[channelId] ?: 0
        if (local == 0) { channelPts[channelId] = pts; return PtsAction.APPLY }
        val expected = local + ptsCount
        return when {
            expected == pts -> { channelPts[channelId] = pts; PtsAction.APPLY }
            expected > pts -> PtsAction.SKIP
            else -> PtsAction.GAP
        }
    }

    private fun scheduleGapRecovery() {
        scope.launch { recoverGap() }
    }

    private fun scheduleChannelGapRecovery(channelId: Long) {
        scope.launch { recoverChannelGap(channelId) }
    }

    private suspend fun handleSingleUpdate(update: TlObject, fromDiff: Boolean = false) {
        when (update) {
            is UpdateLoginToken -> {
                // The QR was scanned/accepted on another device — re-export to
                // pick up the loginTokenMigrateTo / loginTokenSuccess result.
                if (_state.value is State.AwaitingQrScan) {
                    qrRefreshJob?.cancel()
                    scope.launch { runCatching { refreshLoginToken() } }
                }
            }
            is UpdateNewMessage -> {
                when (if (fromDiff) PtsAction.APPLY else checkCommonPts(update.pts, update.ptsCount)) {
                    PtsAction.SKIP -> {}
                    PtsAction.GAP -> scheduleGapRecovery()
                    PtsAction.APPLY -> when (val msg = update.message) {
                        is Message -> {
                            val chatId = peerToId(msg.peerId)
                            _events.emit(msg.toMessageUpdate(chatId))
                            launchMediaFetch(msg, chatId)
                            if (!msg.out) {
                                _events.emit(GMEvent.IncomingMessage(source, chatId, "${chatId}_${msg.id}", renderBody(msg), senderName(msg.fromId), null, msg.date.toLong() * 1000))
                            }
                        }
                        is MessageService -> {
                            val chatId = peerToId(msg.peerId)
                            handleServiceMessageEvent(msg, chatId)
                        }
                    }
                }
            }
            is UpdateNewChannelMessage -> {
                val channelId = channelIdOfMessage(update.message)
                when (if (fromDiff || channelId == null) PtsAction.APPLY else checkChannelPts(channelId, update.pts, update.ptsCount)) {
                    PtsAction.SKIP -> {}
                    PtsAction.GAP -> if (channelId != null) scheduleChannelGapRecovery(channelId)
                    PtsAction.APPLY -> when (val msg = update.message) {
                        is Message -> {
                            val chatId = peerToId(msg.peerId)
                            _events.emit(msg.toMessageUpdate(chatId))
                            launchMediaFetch(msg, chatId)
                            if (!msg.out) {
                                _events.emit(GMEvent.IncomingMessage(source, chatId, "${chatId}_${msg.id}", renderBody(msg), senderName(msg.fromId), null, msg.date.toLong() * 1000))
                            }
                        }
                        is MessageService -> {
                            val chatId = peerToId(msg.peerId)
                            handleServiceMessageEvent(msg, chatId)
                        }
                    }
                }
            }
            is UpdateDeleteMessages -> {
                when (if (fromDiff) PtsAction.APPLY else checkCommonPts(update.pts, update.ptsCount)) {
                    PtsAction.SKIP -> {}
                    PtsAction.GAP -> scheduleGapRecovery()
                    PtsAction.APPLY -> for (id in update.messages) {
                        _events.emit(GMEvent.MessageDeleted(source, id.toString()))
                    }
                }
            }
            is UpdateDeleteChannelMessages -> {
                when (if (fromDiff) PtsAction.APPLY else checkChannelPts(update.channelId, update.pts, update.ptsCount)) {
                    PtsAction.SKIP -> {}
                    PtsAction.GAP -> scheduleChannelGapRecovery(update.channelId)
                    PtsAction.APPLY -> {
                        val chatId = update.channelId.toString()
                        for (id in update.messages) {
                            _events.emit(GMEvent.MessageDeleted(source, "${chatId}_$id"))
                        }
                    }
                }
            }
            is UpdateEditMessage -> {
                when (if (fromDiff) PtsAction.APPLY else checkCommonPts(update.pts, update.ptsCount)) {
                    PtsAction.SKIP -> {}
                    PtsAction.GAP -> scheduleGapRecovery()
                    PtsAction.APPLY -> {
                        val msg = update.message as? Message ?: return
                        val chatId = peerToId(msg.peerId)
                        if (msg.editDate > 0) {
                            _events.emit(GMEvent.MessageEdited(source, chatId, "${chatId}_${msg.id}", renderBody(msg), msg.editDate.toLong() * 1000))
                        }
                        _events.emit(msg.toMessageUpdate(chatId))
                    }
                }
            }
            is UpdateEditChannelMessage -> {
                val channelId = channelIdOfMessage(update.message)
                when (if (fromDiff || channelId == null) PtsAction.APPLY else checkChannelPts(channelId, update.pts, update.ptsCount)) {
                    PtsAction.SKIP -> {}
                    PtsAction.GAP -> if (channelId != null) scheduleChannelGapRecovery(channelId)
                    PtsAction.APPLY -> {
                        val msg = update.message as? Message ?: return
                        val chatId = peerToId(msg.peerId)
                        if (msg.editDate > 0) {
                            _events.emit(GMEvent.MessageEdited(source, chatId, "${chatId}_${msg.id}", renderBody(msg), msg.editDate.toLong() * 1000))
                        }
                        _events.emit(msg.toMessageUpdate(chatId))
                    }
                }
            }
            is UpdateReadHistoryInbox -> {
                val chatId = peerToId(update.peer)
                _events.emit(GMEvent.ConversationUpdate(source, chatId, null, null, null, null, 0, 0))
            }
            is UpdateReadHistoryOutbox -> {
                val chatId = peerToId(update.peer)
                if (update.peer is PeerUser) {
                    _events.emit(GMEvent.ReadReceipt(source, chatId, "${chatId}_${update.maxId}", null, System.currentTimeMillis()))
                }
            }
            is UpdateReadChannelInbox -> {
                val chatId = update.channelId.toString()
                _events.emit(GMEvent.ConversationUpdate(source, chatId, null, null, null, null, 0, 0))
            }
            is UpdateReadChannelOutbox -> {
                // Go does not emit read receipts for channel outbox reads.
                // Channels show aggregate read counts, not per-user receipts.
            }
            is UpdateChannel -> {
                val chatId = update.channelId.toString()
                _events.emit(GMEvent.ConversationUpdate(source, chatId, null, null, null, null, 0, 0))
            }
            is UpdateUserTyping -> {
                val chatId = update.userId.toString()
                val isTyping = update.actionTypeId != 0xfd5ec8f5.toInt()
                _events.emit(GMEvent.TypingIndicator(source, chatId, update.userId.toString(), isTyping))
            }
            is UpdateChatUserTyping -> {
                val chatId = update.chatId.toString()
                val senderId = peerToId(update.fromId)
                val isTyping = update.actionTypeId != 0xfd5ec8f5.toInt()
                _events.emit(GMEvent.TypingIndicator(source, chatId, senderId, isTyping))
            }
            is UpdateChannelUserTyping -> {
                val chatId = update.channelId.toString()
                val senderId = peerToId(update.fromId)
                val isTyping = update.actionTypeId != 0xfd5ec8f5.toInt()
                _events.emit(GMEvent.TypingIndicator(source, chatId, senderId, isTyping))
            }
            is UpdateMessageReactions -> {
                val chatId = peerToId(update.peer)
                _events.emit(GMEvent.ConversationUpdate(source, chatId, null, null, null, null, 0, 0))
            }
            is UpdateBotMessageReaction -> {
                val chatId = peerToId(update.peer)
                _events.emit(GMEvent.ConversationUpdate(source, chatId, null, null, null, null, 0, 0))
            }
            is UpdateChatDefaultBannedRights -> {
                val chatId = peerToId(update.peer)
                _events.emit(GMEvent.ConversationUpdate(source, chatId, null, null, null, null, 0, 0))
            }
            is UpdatePeerBlocked -> {
                Log.d(TAG, "Peer blocked/unblocked: ${peerToId(update.peerId)} blocked=${update.blocked}")
            }
            is UpdatePhoneCall -> {
                Log.d(TAG, "Phone call update: callId=${update.phoneCallId}")
            }
            is UpdateUserStatus -> {
                // Intentionally ignored, same as Go bridge
            }
            is UpdateUserName -> {
                val name = "${update.firstName} ${update.lastName}".trim()
                if (name.isNotBlank()) userNameCache[update.userId] = name
            }
            is UpdateNotifySettings -> {
                val chatId = peerToId(update.peer)
                if (chatId != "0") {
                    _events.emit(GMEvent.ConversationUpdate(source, chatId, null, null, null, null, 0, 0))
                }
            }
            is UpdatePinnedDialogs -> {
                kickoffBackfill()
            }
            is UpdateChatTitle -> {
                val chatId = update.chatId.toString()
                _events.emit(GMEvent.ConversationNameChanged(source, chatId, update.title))
            }
        }
    }

    private fun classifyTypingAction(actionTypeId: Int): String = when (actionTypeId) {
        0x16bf744e.toInt() -> "text"                // SendMessageTypingAction
        0xa187d66f.toInt(), 0xd52f73f7.toInt(),     // RecordVideo, RecordAudio
        0x88f27fbc.toInt() -> "recording"            // RecordRound
        0xe9763aec.toInt(), 0xf351d7ab.toInt(),     // UploadVideo, UploadAudio
        0xaa0cd9e4.toInt(), 0xd1d739de.toInt(),     // UploadDocument, UploadPhoto
        0x243e1c66.toInt() -> "uploading"            // UploadRound
        else -> "text"
    }

    private suspend fun handleServiceMessageEvent(msg: MessageService, chatId: String) {
        val action = msg.action ?: return
        val senderName = senderName(msg.fromId)
        when (action) {
            is MessageActionChatEditTitle -> {
                _events.emit(GMEvent.ConversationNameChanged(source, chatId, action.title))
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} changed the group name to \"${action.title}\"",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatEditPhoto -> {
                _events.emit(GMEvent.ConversationAvatarChanged(source, chatId, null))
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} changed the group photo",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatDeletePhoto -> {
                _events.emit(GMEvent.ConversationAvatarChanged(source, chatId, null))
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} removed the group photo",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatAddUser -> {
                for (userId in action.users) {
                    _events.emit(GMEvent.ParticipantAdded(source, chatId, userId.toString()))
                }
                val addedNames = action.users.joinToString(", ") { userNameCache[it] ?: it.toString() }
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} added $addedNames",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatDeleteUser -> {
                _events.emit(GMEvent.ParticipantRemoved(source, chatId, action.userId.toString()))
                val isSelfLeave = msg.fromId is PeerUser && (msg.fromId as PeerUser).userId == action.userId
                val body = if (isSelfLeave) {
                    val name = userNameCache[action.userId] ?: action.userId.toString()
                    "$name left the group"
                } else {
                    val removedName = userNameCache[action.userId] ?: action.userId.toString()
                    "${senderName ?: "Someone"} removed $removedName"
                }
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    body, msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatJoinedByLink -> {
                val joinedUserId = if (msg.fromId is PeerUser) (msg.fromId as PeerUser).userId else 0L
                if (joinedUserId != 0L) {
                    _events.emit(GMEvent.ParticipantAdded(source, chatId, joinedUserId.toString()))
                }
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} joined via invite link",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionSetMessagesTTL -> {
                val body = if (action.period > 0) {
                    "${senderName ?: "Someone"} set messages to auto-delete in ${formatTTL(action.period)}"
                } else {
                    "${senderName ?: "Someone"} disabled auto-delete"
                }
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    body, msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionPhoneCall -> {
                val reasonText = when (action.reason) {
                    0x85e42301.toInt() -> "missed"
                    0xe095c1a0.toInt() -> "disconnected"
                    0x57adc690.toInt() -> "ended"
                    0xfaf7e8c9.toInt() -> "rejected"
                    else -> {
                        Log.w(TAG, "Unknown call end reason: ${action.reason}")
                        return
                    }
                }
                val body = buildString {
                    if (action.video) append("Video call ") else append("Call ")
                    append(reasonText)
                    if (action.duration > 0) append(" (${formatDuration(action.duration)})")
                }
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    body, msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatCreate -> {
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} created the group \"${action.title}\"",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatMigrateTo -> {
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "Group upgraded to supergroup",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionPinMessage -> {
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} pinned a message",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionTopicCreate -> {
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "Topic \"${action.title}\" created",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionGroupCall -> {
                val body = if (action.duration == 0) {
                    "Started a video chat"
                } else {
                    "Ended the video chat (${formatDuration(action.duration)})"
                }
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    body, msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChannelCreate -> {
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "Channel created", msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionTopicEdit -> {
                val body = if (action.title.isNotBlank()) {
                    "${senderName ?: "Someone"} renamed the topic to \"${action.title}\""
                } else if (action.iconChanged) {
                    "${senderName ?: "Someone"} changed the topic icon"
                } else {
                    "${senderName ?: "Someone"} edited the topic"
                }
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    body, msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionInviteToGroupCall -> {
                val names = action.users.joinToString(", ") { userNameCache[it] ?: it.toString() }
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} invited $names to the video chat",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionGroupCallScheduled -> {
                val date = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
                    .format(java.util.Date(action.scheduleDate.toLong() * 1000))
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "Video chat scheduled for $date",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionChatJoinedByRequest -> {
                val joinedUserId = if (msg.fromId is PeerUser) (msg.fromId as PeerUser).userId else 0L
                if (joinedUserId != 0L) {
                    _events.emit(GMEvent.ParticipantAdded(source, chatId, joinedUserId.toString()))
                }
                _events.emit(GMEvent.MessageUpdate(source, chatId, "${chatId}_${msg.id}",
                    "${senderName ?: "Someone"} joined via approval",
                    msg.out, msg.date.toLong() * 1000, senderName))
            }
            is MessageActionUnknown -> {}
        }
    }

    private fun formatTTL(seconds: Int): String = when {
        seconds >= 86400 * 7 -> "${seconds / (86400 * 7)} week(s)"
        seconds >= 86400 -> "${seconds / 86400} day(s)"
        seconds >= 3600 -> "${seconds / 3600} hour(s)"
        seconds >= 60 -> "${seconds / 60} minute(s)"
        else -> "$seconds second(s)"
    }

    private fun formatLocation(lat: Double, long: Double): String {
        val latChar = if (lat > 0) "N" else "S"
        val longChar = if (long > 0) "E" else "W"
        val body = "%.4f° %s, %.4f° %s".format(lat, latChar, long, longChar)
        val url = "https://maps.google.com/?q=%f,%f".format(lat, long)
        return "$body\n$url"
    }

    private fun formatDuration(seconds: Int): String = buildString {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        if (h > 0) append("${h}h ")
        if (m > 0) append("${m}m ")
        if (s > 0 || (h == 0 && m == 0)) append("${s}s")
    }.trim()

    private fun kickoffBackfill() {
        backfillJob?.cancel()
        backfillJob = scope.launch {
            val client = apiClient ?: return@launch
            Log.i(TAG, "kickoffBackfill")
            try {
                val result = client.invoke(
                    MessagesGetDialogs(offsetPeer = InputPeerSelf, limit = 200)
                ) { TlRegistry.decode(it) }

                val users = extractUsers(result)
                val chats = extractChats(result)
                cacheUsers(users)
                cacheChats(chats)

                val dialogs = extractDialogs(result)
                val messages = extractMessages(result)

                val messageMap = mutableMapOf<Int, Message>()
                for (m in messages) {
                    if (m is Message) messageMap[m.id] = m
                }

                for (dialog in dialogs) {
                    val chatId = peerToId(dialog.peer)
                    val lastMsg = messageMap[dialog.topMessage]
                    val preview = lastMsg?.let { extractPreview(it) }
                    val tsMs = (lastMsg?.date?.toLong() ?: 0L) * 1000L
                    val isGroup = dialog.peer is PeerChat || dialog.peer is PeerChannel
                    val name = resolvePeerName(dialog.peer)
                    val convType = when (dialog.peer) {
                        is PeerChannel -> {
                            val ch = channelMetaCache[dialog.peer.channelId]
                            when {
                                ch?.forum == true -> "Forum"
                                ch?.megagroup == true -> "Supergroup"
                                else -> "Channel"
                            }
                        }
                        is PeerChat -> "Group"
                        else -> "Telegram"
                    }

                    val peerNumericId = peerNumericId(dialog.peer)

                    fun emitConv(avatar: String?) = _events.tryEmit(
                        GMEvent.ConversationUpdate(
                            source = source,
                            conversationId = chatId,
                            peerName = name,
                            peerPhone = null,
                            avatarUrl = avatar,
                            lastPreview = preview,
                            lastTimestamp = tsMs,
                            unreadCount = dialog.unreadCount,
                            isGroup = isGroup,
                            conversationType = convType,
                        )
                    )

                    // Emit immediately (with any already-cached avatar), then fetch the avatar
                    // asynchronously and re-emit so the conversation list isn't blocked on downloads.
                    val pid = peerNumericId
                    emitConv(pid?.let { avatarPathCache[it] })
                    if (pid != null && photoCache[pid] != null) {
                        scope.launch {
                            val avatar = runCatching { downloadPeerAvatar(pid) }.getOrNull()
                            if (avatar != null) emitConv(avatar)
                        }
                    }

                    cachePeerFromDialog(dialog.peer)
                    if (dialog.peer is PeerChannel && dialog.pts > 0) {
                        channelPts[dialog.peer.channelId] = dialog.pts
                    }
                }

                for (dialog in dialogs) {
                    val chatId = peerToId(dialog.peer)
                    val peer = resolvePeer("${source.idPrefix}:$chatId") ?: continue
                    try {
                        val histResult = client.invoke(MessagesGetHistory(peer, limit = 50)) { TlRegistry.decode(it) }
                        val histMsgs = extractMessages(histResult)
                        cacheUsers(extractUsers(histResult))
                        for (msg in histMsgs) {
                            when (msg) {
                                is Message -> _events.emit(msg.toMessageUpdate(chatId))
                                is MessageService -> handleServiceMessageEvent(msg, chatId)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "backfill history for $chatId failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "backfill failed: ${t.message}")
            }
        }
    }

    private suspend fun fetchUpdatesState(client: TelegramApiClient) {
        try {
            val state = client.invoke(UpdatesGetState) { TlRegistry.decode(it) }
            if (state is UpdatesState) {
                currentPts = state.pts
                currentQts = state.qts
                currentDate = state.date
                currentSeq = state.seq
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getState failed: ${t.message}")
        }
    }

    private suspend fun recoverGap() {
        if (currentPts == 0) return
        if (!gapRecovering.compareAndSet(false, true)) return // already recovering
        val client = apiClient ?: run { gapRecovering.set(false); return }
        try {
            var fetching = true
            while (fetching) {
                val diff = client.invoke(UpdatesGetDifference(currentPts, currentDate, currentQts)) { TlRegistry.decode(it) }
                when (diff) {
                    is UpdatesDifference -> {
                        processDiffMessages(diff.newMessages, diff.users, diff.chats, diff.otherUpdates)
                        currentPts = diff.state.pts
                        currentQts = diff.state.qts
                        currentDate = diff.state.date
                        currentSeq = diff.state.seq
                        fetching = false
                    }
                    is UpdatesDifferenceSlice -> {
                        processDiffMessages(diff.newMessages, diff.users, diff.chats, diff.otherUpdates)
                        currentPts = diff.intermediateState.pts
                        currentQts = diff.intermediateState.qts
                        currentDate = diff.intermediateState.date
                        currentSeq = diff.intermediateState.seq
                    }
                    is UpdatesDifferenceTooLong -> {
                        currentPts = diff.pts
                        kickoffBackfill()
                        fetching = false
                    }
                    is UpdatesDifferenceEmpty -> {
                        currentDate = diff.date
                        currentSeq = diff.seq
                        fetching = false
                    }
                    else -> fetching = false
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recoverGap failed: ${t.message}")
        } finally {
            gapRecovering.set(false)
        }
    }

    /**
     * Per-channel gap recovery via channels.getChannelDifference, looping over slices until final.
     */
    private suspend fun recoverChannelGap(channelId: Long) {
        if (channelGapRecovering.putIfAbsent(channelId, true) != null) return // already running
        val client = apiClient ?: run { channelGapRecovering.remove(channelId); return }
        try {
            val ch = channelMetaCache[channelId] ?: return
            var localPts = channelPts[channelId] ?: 0
            if (localPts == 0) localPts = 1 // force a fresh fetch when we have no baseline
            var fetching = true
            while (fetching) {
                val diff = client.invoke(
                    UpdatesGetChannelDifference(channelId, ch.accessHash, localPts)
                ) { TlRegistry.decode(it) }
                when (diff) {
                    is ChannelDifference -> {
                        cacheUsers(diff.users)
                        cacheChats(diff.chats)
                        for (msg in diff.newMessages) emitDiffMessage(msg)
                        for (u in diff.otherUpdates) handleSingleUpdate(u, fromDiff = true)
                        channelPts[channelId] = diff.pts
                        localPts = diff.pts
                        fetching = !diff.final
                    }
                    is ChannelDifferenceEmpty -> {
                        channelPts[channelId] = diff.pts
                        fetching = false
                    }
                    is ChannelDifferenceTooLong -> {
                        cacheUsers(diff.users)
                        cacheChats(diff.chats)
                        for (msg in diff.messages) emitDiffMessage(msg)
                        if (diff.pts > 0) channelPts[channelId] = diff.pts
                        fetching = false
                    }
                    else -> fetching = false
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recoverChannelGap($channelId) failed: ${t.message}")
        } finally {
            channelGapRecovering.remove(channelId)
        }
    }

    private suspend fun emitDiffMessage(msg: TlObject) {
        when (msg) {
            is Message -> {
                val chatId = peerToId(msg.peerId)
                _events.emit(msg.toMessageUpdate(chatId))
            }
            is MessageService -> {
                val chatId = peerToId(msg.peerId)
                handleServiceMessageEvent(msg, chatId)
            }
        }
    }

    private suspend fun processDiffMessages(
        newMessages: List<TlObject>,
        users: List<TlObject>,
        chats: List<TlObject>,
        otherUpdates: List<TlObject>,
    ) {
        cacheUsers(users)
        cacheChats(chats)
        for (msg in newMessages) {
            when (msg) {
                is Message -> {
                    val chatId = peerToId(msg.peerId)
                    _events.emit(msg.toMessageUpdate(chatId))
                }
                is MessageService -> {
                    val chatId = peerToId(msg.peerId)
                    handleServiceMessageEvent(msg, chatId)
                }
            }
        }
        for (u in otherUpdates) handleSingleUpdate(u, fromDiff = true)
    }

    // ----------------------------------------------------------------
    // File / avatar downloader
    // ----------------------------------------------------------------

    private const val DOWNLOAD_CHUNK = 512 * 1024 // must be a multiple of 4096

    /**
     * Returns a TelegramApiClient connected & authorized to [dc]. For the home DC this is the
     * primary [apiClient]; for other DCs a secondary connection is created and authorized via
     * auth.exportAuthorization (home) -> auth.importAuthorization (target), then cached.
     * UNVERIFIED: cross-DC export/import flow is exercised only against a live server.
     */
    private suspend fun clientForDc(dc: Int): TelegramApiClient? {
        val main = apiClient ?: return null
        if (dc == main.dc) return main
        dcClients[dc]?.let { if (it.isConnected) return it }
        return try {
            val exported = main.invoke(AuthExportAuthorization(dc)) { TlRegistry.decode(it) }
                    as? AuthExportedAuthorization ?: return null
            val sub = TelegramApiClient()
            sub.connect(dc)
            sub.invoke(AuthImportAuthorization(exported.id, exported.bytes)) { TlRegistry.decode(it) }
            dcClients[dc] = sub
            sub
        } catch (t: Throwable) {
            Log.w(TAG, "clientForDc($dc) failed: ${t.message}")
            null
        }
    }

    /**
     * Chunked upload.getFile download loop. Returns the raw file bytes, or null on failure.
     * CDN-redirected files are not supported and return null. // UNVERIFIED
     */
    private suspend fun downloadFileBytes(location: TlObject, dc: Int): ByteArray? {
        val client = clientForDc(dc) ?: return null
        val out = java.io.ByteArrayOutputStream()
        var offset = 0L
        return try {
            while (true) {
                val res = client.invoke(UploadGetFile(location, offset, DOWNLOAD_CHUNK)) { TlRegistry.decode(it) }
                when (res) {
                    is UploadFile -> {
                        out.write(res.bytes)
                        if (res.bytes.size < DOWNLOAD_CHUNK) break
                        offset += res.bytes.size
                    }
                    is UploadFileCdnRedirect -> {
                        Log.w(TAG, "CDN redirect for file download not supported (dc=${res.dcId})")
                        return null
                    }
                    else -> break
                }
            }
            out.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "downloadFileBytes failed: ${humanizeError(t)}")
            null
        }
    }

    /** Downloads [location] (if not already cached) and returns a file:// path. */
    private suspend fun downloadToCache(location: TlObject, dc: Int, cacheKey: String, ext: String): String? {
        val dir = java.io.File(appContext.cacheDir, "telegram_media")
        dir.mkdirs()
        val file = java.io.File(dir, "$cacheKey.$ext")
        if (file.exists() && file.length() > 0) return "file://${file.absolutePath}"
        val bytes = downloadFileBytes(location, dc) ?: return null
        if (bytes.isEmpty()) return null
        return try {
            file.writeBytes(bytes)
            "file://${file.absolutePath}"
        } catch (t: Throwable) {
            Log.w(TAG, "downloadToCache write failed: ${t.message}")
            null
        }
    }

    /**
     * Downloads the small profile photo for [peerId] using the captured photo_id + dc_id and the
     * cached InputPeer, returning a file:// path (or null if no photo / download failed).
     */
    private suspend fun downloadPeerAvatar(peerId: Long): String? {
        avatarPathCache[peerId]?.let { return it }
        val photo = photoCache[peerId] ?: return null
        val peer = peerCache[peerId] ?: return null
        val location = InputPeerPhotoFileLocation(peer = peer, photoId = photo.photoId, big = false)
        val dc = if (photo.dcId > 0) photo.dcId else (apiClient?.dc ?: 2)
        val path = downloadToCache(location, dc, "avatar_${peerId}_${photo.photoId}", "jpg")
        if (path != null) avatarPathCache[peerId] = path
        return path
    }

    private const val MAX_MEDIA_DOWNLOAD = 25L * 1024 * 1024 // skip auto-download above 25 MB

    /**
     * Downloads message media (photos + bounded non-sticker documents). Returns
     * (bytes, mime, fileName) or null when there is nothing downloadable. Stickers and very large
     * files are skipped. // UNVERIFIED against a live server.
     */
    private suspend fun downloadMessageMedia(media: TlObject?): Triple<ByteArray, String, String>? {
        return when (media) {
            is MessageMediaPhoto -> {
                if (media.photoId == 0L) return null
                val loc = InputPhotoFileLocation(
                    media.photoId, media.accessHash, media.fileReference,
                    media.thumbSize.ifEmpty { "x" },
                )
                val dc = if (media.dcId > 0) media.dcId else (apiClient?.dc ?: 2)
                val bytes = downloadFileBytes(loc, dc) ?: return null
                if (bytes.isEmpty()) null else Triple(bytes, "image/jpeg", "photo_${media.photoId}.jpg")
            }
            is MessageMediaDocument -> {
                if (media.docId == 0L || media.isSticker) return null
                if (media.size in 1 until MAX_MEDIA_DOWNLOAD || media.size == 0L) {
                    val loc = InputDocumentFileLocation(media.docId, media.accessHash, media.fileReference, "")
                    val dc = if (media.dcId > 0) media.dcId else (apiClient?.dc ?: 2)
                    val bytes = downloadFileBytes(loc, dc) ?: return null
                    val mime = media.mimeType.ifEmpty { "application/octet-stream" }
                    val name = media.fileName.ifEmpty { "file_${media.docId}" }
                    if (bytes.isEmpty()) null else Triple(bytes, mime, name)
                } else null
            }
            else -> null
        }
    }

    /**
     * For a freshly-arrived message with downloadable media, downloads it off the hot path and
     * re-emits a MessageUpdate carrying the bytes (same messageId so the row is updated in place).
     */
    private fun launchMediaFetch(msg: Message, chatId: String) {
        val media = msg.media
        if (media !is MessageMediaPhoto && media !is MessageMediaDocument) return
        scope.launch {
            val result = runCatching { downloadMessageMedia(media) }.getOrNull() ?: return@launch
            _events.emit(
                GMEvent.MessageUpdate(
                    source = source,
                    conversationId = chatId,
                    messageId = "${chatId}_${msg.id}",
                    body = renderBody(msg),
                    outgoing = msg.out,
                    timestamp = msg.date.toLong() * 1000L,
                    senderName = senderName(msg.fromId),
                    mediaData = result.first,
                    mediaMime = result.second,
                    mediaName = result.third,
                )
            )
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun resolvePeer(conversationId: String): TlObject? {
        val idStr = conversationId.substringAfter(':', conversationId)
        val id = idStr.toLongOrNull() ?: return null
        return peerCache[id]
    }

    private fun peerToId(peer: TlObject): String = when (peer) {
        is PeerUser -> peer.userId.toString()
        is PeerChat -> peer.chatId.toString()
        is PeerChannel -> peer.channelId.toString()
        else -> "0"
    }

    private fun peerNumericId(peer: TlObject): Long? = when (peer) {
        is PeerUser -> peer.userId
        is PeerChat -> peer.chatId
        is PeerChannel -> peer.channelId
        else -> null
    }

    private fun channelIdOfMessage(message: TlObject): Long? {
        val peer = when (message) {
            is Message -> message.peerId
            is MessageService -> message.peerId
            else -> null
        }
        return (peer as? PeerChannel)?.channelId
    }

    private fun senderName(fromId: TlObject?): String? {
        if (fromId is PeerUser) return userNameCache[fromId.userId]
        return null
    }

    private fun resolvePeerName(peer: TlObject): String? = when (peer) {
        is PeerUser -> userNameCache[peer.userId]
        is PeerChat -> userNameCache[peer.chatId]
        is PeerChannel -> userNameCache[peer.channelId]
        else -> null
    }

    private fun cachePeerFromDialog(peer: TlObject) {
        when (peer) {
            is PeerUser -> {
                // Already cached from users list during backfill
            }
            is PeerChat -> {
                peerCache[peer.chatId] = InputPeerChat(peer.chatId)
            }
            is PeerChannel -> {
                if (peerCache[peer.channelId] == null) {
                    val ch = channelMetaCache[peer.channelId]
                    if (ch != null) {
                        peerCache[peer.channelId] = InputPeerChannel(peer.channelId, ch.accessHash)
                    }
                }
            }
        }
    }

    private fun cacheUsers(users: List<TlObject>) {
        for (u in users) {
            if (u is User) {
                val name = "${u.firstName} ${u.lastName}".trim()
                if (name.isNotBlank()) userNameCache[u.id] = name
                peerCache[u.id] = InputPeerUser(u.id, u.accessHash)
                if (u.photoId != 0L) photoCache[u.id] = ProfilePhoto(u.photoId, u.photoDcId)
                else photoCache.remove(u.id)
            }
        }
    }

    private fun cacheChats(chats: List<TlObject>) {
        for (c in chats) {
            when (c) {
                is Chat -> {
                    userNameCache[c.id] = c.title
                    peerCache[c.id] = InputPeerChat(c.id)
                    if (c.photoId != 0L) photoCache[c.id] = ProfilePhoto(c.photoId, c.photoDcId)
                    else photoCache.remove(c.id)
                }
                is Channel -> {
                    userNameCache[c.id] = c.title
                    peerCache[c.id] = InputPeerChannel(c.id, c.accessHash)
                    channelMetaCache[c.id] = c
                    if (c.photoId != 0L) photoCache[c.id] = ProfilePhoto(c.photoId, c.photoDcId)
                    else photoCache.remove(c.id)
                }
            }
        }
    }

    private fun extractMessages(result: TlObject): List<TlObject> = when (result) {
        is MessagesMessages -> result.messages
        is MessagesMessagesSlice -> result.messages
        is MessagesChannelMessages -> result.messages
        else -> emptyList()
    }

    private fun extractUsers(result: TlObject): List<TlObject> = when (result) {
        is MessagesMessages -> result.users
        is MessagesMessagesSlice -> result.users
        is MessagesChannelMessages -> result.users
        is MessagesDialogs -> result.users
        is MessagesDialogsSlice -> result.users
        is ContactsFound -> result.users
        else -> emptyList()
    }

    private fun extractChats(result: TlObject): List<TlObject> = when (result) {
        is MessagesDialogs -> result.chats
        is MessagesDialogsSlice -> result.chats
        is MessagesMessages -> result.chats
        is MessagesMessagesSlice -> result.chats
        else -> emptyList()
    }

    private fun extractDialogs(result: TlObject): List<Dialog> = when (result) {
        is MessagesDialogs -> result.dialogs
        is MessagesDialogsSlice -> result.dialogs
        else -> emptyList()
    }

    private fun renderBody(msg: Message): String {
        val text = msg.message
        if (text.isBlank()) return extractPreview(msg) ?: ""
        if (msg.entities.isEmpty()) return buildBodyWithContext(msg, text)

        val sb = StringBuilder()
        var lastEnd = 0
        val sorted = msg.entities.sortedBy { it.offset }
        for (entity in sorted) {
            val start = entity.offset.coerceAtMost(text.length)
            val end = (entity.offset + entity.length).coerceAtMost(text.length)
            if (start < lastEnd) continue
            if (start > lastEnd) sb.append(text, lastEnd, start)
            val slice = text.substring(start, end)
            when (entity.type) {
                "bold" -> sb.append("*").append(slice).append("*")
                "italic" -> sb.append("_").append(slice).append("_")
                "code" -> sb.append("`").append(slice).append("`")
                "pre" -> sb.append("```").append(slice).append("```")
                "strikethrough" -> sb.append("~").append(slice).append("~")
                "underline" -> sb.append(slice)
                "textUrl" -> sb.append(slice).append(" (").append(entity.url).append(")")
                "spoiler" -> sb.append("||").append(slice).append("||")
                "blockquote" -> sb.append("> ").append(slice)
                else -> sb.append(slice)
            }
            lastEnd = end
        }
        if (lastEnd < text.length) sb.append(text, lastEnd, text.length)

        return buildBodyWithContext(msg, sb.toString())
    }

    private fun buildBodyWithContext(msg: Message, body: String): String {
        val parts = mutableListOf<String>()
        msg.fwdFrom?.let { fwd ->
            val fwdName = fwd.fromName.ifBlank {
                fwd.fromId?.let {
                    if (it is PeerUser) userNameCache[it.userId] else null
                } ?: "Unknown"
            }
            parts.add("Forwarded from $fwdName:")
        }
        if (msg.replyToMsgId != 0) {
            parts.add("[Reply to ${msg.replyToMsgId}]")
        }
        parts.add(body)
        val media = msg.media
        if (media != null && media !is MessageMediaEmpty) {
            val mediaText = when (media) {
                is MessageMediaWebPage -> if (media.url.isNotBlank()) "[Link: ${media.url}]" else null
                is MessageMediaPhoto -> "[Photo]"
                is MessageMediaDocument -> when {
                    (media as MessageMediaDocument).isSticker -> {
                        val alt = media.stickerAlt.ifBlank { null }
                        val stickerType = when {
                            media.mimeType == "application/x-tgsticker" -> "animated"
                            media.mimeType == "video/webm" -> "video"
                            else -> "static"
                        }
                        alt ?: "[Sticker ($stickerType)]"
                    }
                    media.isVoice -> "[Voice message]"
                    media.isRoundVideo -> "[Video message]"
                    media.isVideo -> "[Video]"
                    media.isAnimated -> "[GIF]"
                    media.mimeType.startsWith("audio/") -> "[Audio]"
                    media.mimeType.startsWith("image/") -> "[Image]"
                    media.fileName.isNotBlank() -> "[File: ${media.fileName}]"
                    else -> "[File]"
                }
                is MessageMediaContact -> {
                    val name = "${media.firstName} ${media.lastName}".trim()
                    val phone = "+${media.phoneNumber.trimStart('+')}"
                    "Shared contact info for ${name.ifBlank { "Unknown" }}: $phone"
                }
                is MessageMediaGeo -> "Location: ${formatLocation(media.lat, media.long)}"
                is MessageMediaGeoLive -> "Live Location (see your Telegram client for live updates): ${formatLocation(media.lat, media.long)}"
                is MessageMediaVenue -> if (media.title.isNotBlank()) "${media.title}: ${media.address.ifBlank { "" }} (${formatLocation(media.lat, media.long)})" else "Location: ${formatLocation(media.lat, media.long)}"
                is MessageMediaPoll -> renderPoll(media)
                is MessageMediaDice -> renderDice(media)
                else -> null
            }
            if (mediaText != null && body.isBlank()) {
                parts.clear()
                msg.fwdFrom?.let { fwd ->
                    val fwdName = fwd.fromName.ifBlank { "Unknown" }
                    parts.add("Forwarded from $fwdName:")
                }
                parts.add(mediaText)
            } else if (mediaText != null) {
                parts.add(mediaText)
            }
        }
        return parts.joinToString("\n")
    }

    private fun renderPoll(poll: MessageMediaPoll): String {
        val sb = StringBuilder()
        sb.append("Poll: ").append(poll.pollQuestion.ifBlank { "Unknown" })
        for ((i, opt) in poll.pollOptions.withIndex()) {
            sb.append("\n").append(i + 1).append(". ").append(opt)
        }
        sb.append("\nOpen the Telegram app to vote.")
        return sb.toString()
    }

    private fun renderDice(dice: MessageMediaDice): String {
        val result: String?
        val label = when (dice.emoticon) {
            "🎯" -> { result = null; "Dart throw" }
            "🎲" -> { result = null; "Dice roll" }
            "🏀" -> { result = null; "Basketball throw" }
            "⚽" -> { result = footballResult(dice.value); "Football kick" }
            "🎳" -> { result = bowlingResult(dice.value); "Bowling" }
            "🎰" -> { result = slotMachineResult(dice.value); "Slot machine" }
            else -> { result = null; "${dice.emoticon}" }
        }
        return if (result != null) {
            "${dice.emoticon} $label result: $result (${dice.value})"
        } else {
            "${dice.emoticon} $label result: ${dice.value}"
        }
    }

    private fun footballResult(value: Int): String = when (value) {
        1 -> "miss"
        2 -> "hit the woodwork"
        3 -> "goal"
        4 -> "goal"
        5 -> "goal \uD83C\uDF89"
        else -> "$value"
    }

    private fun bowlingResult(value: Int): String = when (value) {
        1 -> "miss"
        2 -> "1 pin down"
        3 -> "3 pins down, split"
        4 -> "4 pins down, split"
        5 -> "5 pins down"
        6 -> "strike \uD83C\uDF89"
        else -> "$value"
    }

    private fun slotMachineResult(value: Int): String {
        val slotEmojis = mapOf(0 to "\uD83C\uDF6B", 1 to "\uD83C\uDF52", 2 to "\uD83C\uDF4B", 3 to "7\uFE0F\u20E3")
        val res = value - 1
        val r1 = slotEmojis[res % 4] ?: "?"
        val r2 = slotEmojis[res / 4 % 4] ?: "?"
        val r3 = slotEmojis[res / 16] ?: "?"
        return "$r1 $r2 $r3"
    }

    private fun extractPreview(msg: Message): String? {
        val text = msg.message
        if (text.isNotBlank()) {
            if (msg.entities.isNotEmpty()) return renderBody(msg)
            return buildBodyWithContext(msg, text)
        }
        val media = msg.media
        return when (media) {
            is MessageMediaPhoto -> "[Photo]"
            is MessageMediaDocument -> when {
                media.isSticker -> {
                    val alt = media.stickerAlt.ifBlank { null }
                    val stickerType = when {
                        media.mimeType == "application/x-tgsticker" -> "animated"
                        media.mimeType == "video/webm" -> "video"
                        else -> "static"
                    }
                    alt ?: "[Sticker ($stickerType)]"
                }
                media.isVoice -> "[Voice message]"
                media.isRoundVideo -> "[Video message]"
                media.isVideo -> "[Video]"
                media.isAnimated -> "[GIF]"
                media.mimeType.startsWith("audio/") -> "[Audio]"
                media.mimeType.startsWith("image/") -> "[Image]"
                media.fileName.isNotBlank() -> "[File: ${media.fileName}]"
                else -> "[File]"
            }
            is MessageMediaContact -> {
                val name = "${media.firstName} ${media.lastName}".trim()
                val phone = "+${media.phoneNumber.trimStart('+')}"
                "Shared contact info for ${name.ifBlank { "Unknown" }}: $phone"
            }
            is MessageMediaGeo -> "Location: ${formatLocation(media.lat, media.long)}"
            is MessageMediaGeoLive -> "Live Location (see your Telegram client for live updates): ${formatLocation(media.lat, media.long)}"
            is MessageMediaVenue -> if (media.title.isNotBlank()) "${media.title}: ${media.address.ifBlank { "" }} (${formatLocation(media.lat, media.long)})" else "Location: ${formatLocation(media.lat, media.long)}"
            is MessageMediaPoll -> renderPoll(media)
            is MessageMediaDice -> renderDice(media)
            is MessageMediaWebPage -> if (media.url.isNotBlank()) "[Link: ${media.url}]" else "[Link Preview]"
            is MessageMediaUnsupported -> "[Unsupported message]"
            else -> if (msg.mediaTypeId != 0) "[Media]" else null
        }
    }

    private fun Message.toMessageUpdate(chatId: String): GMEvent.MessageUpdate {
        val msgId = "${chatId}_${this.id}"
        val body = renderBody(this)
        val tsMs = this.date.toLong() * 1000L
        return GMEvent.MessageUpdate(
            source = source,
            conversationId = chatId,
            messageId = msgId,
            body = body,
            outgoing = this.out,
            timestamp = tsMs,
            senderName = senderName(this.fromId),
        )
    }

    // ----------------------------------------------------------------
    // Membership handling (Issue 11)
    // ----------------------------------------------------------------

    suspend fun inviteMember(conversationId: String, userIds: List<Long>): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            when (peer) {
                is InputPeerChannel -> {
                    val inputUsers = userIds.mapNotNull { uid ->
                        val cached = peerCache[uid]
                        if (cached is InputPeerUser) InputUser(cached.userId, cached.accessHash) else null
                    }
                    client.invoke(ChannelsInviteToChannel(
                        InputChannel(peer.channelId, peer.accessHash), inputUsers
                    )) { TlRegistry.decode(it) }
                }
                is InputPeerChat -> {
                    for (uid in userIds) {
                        val cached = peerCache[uid]
                        if (cached is InputPeerUser) {
                            client.invoke(MessagesAddChatUser(
                                peer.chatId, InputUser(cached.userId, cached.accessHash)
                            )) { TlRegistry.decode(it) }
                        }
                    }
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "inviteMember failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun kickMember(conversationId: String, userId: Long): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        val cached = peerCache[userId]
        val inputUser = if (cached is InputPeerUser) InputUser(cached.userId, cached.accessHash) else return false
        return try {
            when (peer) {
                is InputPeerChannel -> {
                    val inputChannel = InputChannel(peer.channelId, peer.accessHash)
                    client.invoke(ChannelsEditBanned(
                        inputChannel, inputUser, bannedRights = 1L
                    )) { TlRegistry.decode(it) }
                    client.invoke(ChannelsEditBanned(
                        inputChannel, inputUser
                    )) { TlRegistry.decode(it) }
                }
                is InputPeerChat -> {
                    client.invoke(MessagesDeleteChatUser(peer.chatId, inputUser)) { TlRegistry.decode(it) }
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "kickMember failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun banMember(conversationId: String, userId: Long): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        if (peer !is InputPeerChannel) return false
        val cached = peerCache[userId]
        val inputUser = if (cached is InputPeerUser) InputUser(cached.userId, cached.accessHash) else return false
        return try {
            client.invoke(ChannelsEditBanned(
                InputChannel(peer.channelId, peer.accessHash), inputUser, bannedRights = 1L
            )) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "banMember failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun leaveGroup(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            when (peer) {
                is InputPeerChannel -> {
                    client.invoke(ChannelsLeaveChannel(
                        InputChannel(peer.channelId, peer.accessHash)
                    )) { TlRegistry.decode(it) }
                }
                is InputPeerChat -> {
                    client.invoke(MessagesDeleteChatUser(
                        peer.chatId, InputUserSelf
                    )) { TlRegistry.decode(it) }
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "leaveGroup failed: ${humanizeError(t)}")
            false
        }
    }

    // ----------------------------------------------------------------
    // Forum/topic helpers (Issue 12)
    // ----------------------------------------------------------------

    private fun isTopicConversation(conversationId: String): Boolean {
        return conversationId.contains("_topic_")
    }

    private fun extractTopicId(conversationId: String): Int? {
        val parts = conversationId.split("_topic_")
        return if (parts.size == 2) parts[1].toIntOrNull() else null
    }

    // ----------------------------------------------------------------
    // Markdown parsing for outbound entities (Issue 13)
    // ----------------------------------------------------------------

    private data class ParsedMarkdown(val text: String, val entities: List<MessageEntity>)

    private fun parseMarkdown(raw: String): ParsedMarkdown {
        data class RawMatch(val start: Int, val end: Int, val content: String, val type: String, val url: String = "")

        val matches = mutableListOf<RawMatch>()
        val patterns = listOf(
            "\\*\\*(.+?)\\*\\*" to "bold",
            "__(.+?)__" to "underline",
            "~~(.+?)~~" to "strikethrough",
            "\\|\\|(.+?)\\|\\|" to "spoiler",
            "```(.+?)```" to "pre",
            "`(.+?)`" to "code",
            "\\*(.+?)\\*" to "italic",
        )

        for ((pattern, type) in patterns) {
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            for (match in regex.findAll(raw)) {
                val overlaps = matches.any { it.start < match.range.last + 1 && match.range.first < it.end }
                if (!overlaps) {
                    matches.add(RawMatch(match.range.first, match.range.last + 1, match.groupValues[1], type))
                }
            }
        }

        val urlRegex = Regex("\\[(.+?)]\\((.+?)\\)")
        for (match in urlRegex.findAll(raw)) {
            val overlaps = matches.any { it.start < match.range.last + 1 && match.range.first < it.end }
            if (!overlaps) {
                matches.add(RawMatch(match.range.first, match.range.last + 1, match.groupValues[1], "textUrl", match.groupValues[2]))
            }
        }

        matches.sortBy { it.start }

        val stripped = StringBuilder()
        val entities = mutableListOf<MessageEntity>()
        var lastEnd = 0

        for (m in matches) {
            if (m.start > lastEnd) stripped.append(raw, lastEnd, m.start)
            val offset = stripped.length
            stripped.append(m.content)
            entities.add(MessageEntity(m.type, offset, m.content.length, url = m.url))
            lastEnd = m.end
        }
        if (lastEnd < raw.length) stripped.append(raw, lastEnd, raw.length)

        return if (entities.isEmpty()) ParsedMarkdown(raw, emptyList())
        else ParsedMarkdown(stripped.toString(), entities.sortedBy { it.offset })
    }

    // ----------------------------------------------------------------
    // Error humanization (Issue 17)
    // ----------------------------------------------------------------

    private fun humanizeError(t: Throwable): String {
        val msg = t.message ?: return t.toString()
        return when {
            msg.contains("2FA_CONFIRM_WAIT") -> "The account is 2FA protected so it will be deleted in a week"
            msg.contains("ABOUT_TOO_LONG") -> "The provided bio is too long"
            msg.contains("ACCESS_TOKEN_EXPIRED") -> "Bot token expired"
            msg.contains("ACCESS_TOKEN_INVALID") -> "The provided token is not valid"
            msg.contains("ACTIVE_USER_REQUIRED") -> "The method is only available to already activated users"
            msg.contains("ADMINS_TOO_MUCH") -> "Too many admins"
            msg.contains("ADMIN_ID_INVALID") -> "The specified admin ID is invalid"
            msg.contains("ADMIN_RANK_EMOJI_NOT_ALLOWED") -> "Emoji are not allowed in admin titles or ranks"
            msg.contains("ADMIN_RANK_INVALID") -> "The given admin title or rank was invalid"
            msg.contains("ALBUM_PHOTOS_TOO_MANY") -> "Too many photos were included in the album"
            msg.contains("API_ID_INVALID") -> "The api_id/api_hash combination is invalid"
            msg.contains("API_ID_PUBLISHED_FLOOD") -> "This API id was published somewhere, you can't use it now"
            msg.contains("ARTICLE_TITLE_EMPTY") -> "The title of the article is empty"
            msg.contains("AUTH_BYTES_INVALID") -> "The provided authorization is invalid"
            msg.contains("AUTH_KEY_DUPLICATED") -> "The authorization key was used under two different IP addresses simultaneously"
            msg.contains("AUTH_KEY_INVALID") -> "The key is invalid"
            msg.contains("AUTH_KEY_PERM_EMPTY") -> "The method is unavailable for temporary authorization key"
            msg.contains("AUTH_KEY_UNREGISTERED") -> "The key is not registered in the system"
            msg.contains("AUTH_RESTART") -> "Restart the authorization process"
            msg.contains("AUTH_TOKEN_ALREADY_ACCEPTED") -> "The authorization token was already used"
            msg.contains("AUTH_TOKEN_EXPIRED") -> "The provided authorization token has expired and the updated QR-code must be re-scanned"
            msg.contains("AUTH_TOKEN_INVALID") -> "An invalid authorization token was provided"
            msg.contains("BANNED_RIGHTS_INVALID") -> "You cannot use that set of permissions in this request"
            msg.contains("BOTS_TOO_MUCH") -> "There are too many bots in this chat/channel"
            msg.contains("BOT_CHANNELS_NA") -> "Bots can't edit admin privileges"
            msg.contains("BOT_COMMAND_DESCRIPTION_INVALID") -> "The command description was empty, too long or had invalid characters"
            msg.contains("BOT_COMMAND_INVALID") -> "The specified command is invalid"
            msg.contains("BOT_DOMAIN_INVALID") -> "The domain used for the auth button does not match the one configured in @BotFather"
            msg.contains("BOT_GAMES_DISABLED") -> "Bot games cannot be used in this type of chat"
            msg.contains("BOT_GROUPS_BLOCKED") -> "This bot can't be added to groups"
            msg.contains("BOT_INLINE_DISABLED") -> "This bot can't be used in inline mode"
            msg.contains("BOT_INVALID") -> "This is not a valid bot"
            msg.contains("BOT_METHOD_INVALID") -> "The API access for bot users is restricted"
            msg.contains("BOT_MISSING") -> "This method can only be run by a bot"
            msg.contains("BOT_PAYMENTS_DISABLED") -> "This method can only be run by a bot"
            msg.contains("BOT_POLLS_DISABLED") -> "You cannot create polls under a bot account"
            msg.contains("BOT_RESPONSE_TIMEOUT") -> "The bot did not answer to the callback query in time"
            msg.contains("BROADCAST_FORBIDDEN") -> "The request cannot be used in broadcast channels"
            msg.contains("BROADCAST_ID_INVALID") -> "The channel is invalid"
            msg.contains("BROADCAST_PUBLIC_VOTERS_FORBIDDEN") -> "You cannot broadcast polls where the voters are public"
            msg.contains("BROADCAST_REQUIRED") -> "The request can only be used with a broadcast channel"
            msg.contains("BUTTON_DATA_INVALID") -> "The provided button data is invalid"
            msg.contains("BUTTON_TEXT_INVALID") -> "The specified button text is invalid"
            msg.contains("BUTTON_TYPE_INVALID") -> "The type of one of the buttons you provided is invalid"
            msg.contains("BUTTON_URL_INVALID") -> "Button URL invalid"
            msg.contains("CALL_ALREADY_ACCEPTED") -> "The call was already accepted"
            msg.contains("CALL_ALREADY_DECLINED") -> "The call was already declined"
            msg.contains("CALL_OCCUPY_FAILED") -> "The call failed because the user is already making another call"
            msg.contains("CALL_PEER_INVALID") -> "The provided call peer object is invalid"
            msg.contains("CHANNELS_ADMIN_LOCATED_TOO_MUCH") -> "The user has reached the limit of public geogroups"
            msg.contains("CHANNELS_ADMIN_PUBLIC_TOO_MUCH") -> "You're admin of too many public channels"
            msg.contains("CHANNELS_TOO_MUCH") -> "You have joined too many channels/supergroups"
            msg.contains("CHANNEL_BANNED") -> "The channel is banned"
            msg.contains("CHANNEL_FORUM_MISSING") -> "This channel is not a forum"
            msg.contains("CHANNEL_ID_INVALID") -> "The specified supergroup ID is invalid"
            msg.contains("CHANNEL_INVALID") -> "Invalid channel object"
            msg.contains("CHANNEL_PARICIPANT_MISSING") -> "The current user is not in the channel"
            msg.contains("CHANNEL_PRIVATE") -> "This channel is private and you lack permission to access it"
            msg.contains("CHANNEL_TOO_LARGE") -> "Channel is too large to be deleted"
            msg.contains("CHAT_ABOUT_NOT_MODIFIED") -> "About text has not changed"
            msg.contains("CHAT_ABOUT_TOO_LONG") -> "Chat about too long"
            msg.contains("CHAT_ADMIN_INVITE_REQUIRED") -> "You do not have the rights to do this"
            msg.contains("CHAT_ADMIN_REQUIRED") -> "Admin privileges are required"
            msg.contains("CHAT_FORBIDDEN") -> "You cannot write in this chat"
            msg.contains("CHAT_FORWARDS_RESTRICTED") -> "You can't forward messages from a protected chat"
            msg.contains("CHAT_GUEST_SEND_FORBIDDEN") -> "You need to join the discussion group before commenting"
            msg.contains("CHAT_ID_EMPTY") -> "The provided chat ID is empty"
            msg.contains("CHAT_ID_GENERATE_FAILED") -> "Failure while generating the chat ID"
            msg.contains("CHAT_ID_INVALID") -> "Invalid object ID for a chat"
            msg.contains("CHAT_INVALID") -> "The chat is invalid for this request"
            msg.contains("CHAT_INVITE_PERMANENT") -> "You can't set an expiration date on permanent invite links"
            msg.contains("CHAT_LINK_EXISTS") -> "The chat is linked to a channel and cannot be used in that request"
            msg.contains("CHAT_NOT_MODIFIED") -> "The chat or channel wasn't modified"
            msg.contains("CHAT_RESTRICTED") -> "The chat is restricted and cannot be used"
            msg.contains("CHAT_REVOKE_DATE_UNSUPPORTED") -> "min_date and max_date are not available for using with non-user peers"
            msg.contains("CHAT_SEND_GAME_FORBIDDEN") -> "You can't send a game to this chat"
            msg.contains("CHAT_SEND_GIFS_FORBIDDEN") -> "You can't send GIFs in this chat"
            msg.contains("CHAT_SEND_INLINE_FORBIDDEN") -> "You cannot send inline results in this chat"
            msg.contains("CHAT_SEND_MEDIA_FORBIDDEN") -> "You can't send media in this chat"
            msg.contains("CHAT_SEND_POLL_FORBIDDEN") -> "You can't send polls in this chat"
            msg.contains("CHAT_SEND_STICKERS_FORBIDDEN") -> "You can't send stickers in this chat"
            msg.contains("CHAT_TITLE_EMPTY") -> "No chat title provided"
            msg.contains("CHAT_WRITE_FORBIDDEN") -> "You can't write in this chat"
            msg.contains("CODE_EMPTY") -> "The provided code is empty"
            msg.contains("CODE_HASH_INVALID") -> "Code hash invalid"
            msg.contains("CODE_INVALID") -> "Code invalid"
            msg.contains("CONNECTION_API_ID_INVALID") -> "The provided API id is invalid"
            msg.contains("CONNECTION_LAYER_INVALID") -> "The very first request must always be InvokeWithLayerRequest"
            msg.contains("CONTACT_ADD_MISSING") -> "Contact to add is missing"
            msg.contains("CONTACT_ID_INVALID") -> "The provided contact ID is invalid"
            msg.contains("CONTACT_NAME_EMPTY") -> "The provided contact name cannot be empty"
            msg.contains("CREATE_CALL_FAILED") -> "An error occurred while creating the call"
            msg.contains("DATA_INVALID") -> "Encrypted data invalid"
            msg.contains("DATA_JSON_INVALID") -> "The provided JSON data is invalid"
            msg.contains("DATA_TOO_LONG") -> "Data too long"
            msg.contains("DC_ID_INVALID") -> "This occurs when an authorization is tried to be exported for the same data center"
            msg.contains("DOCUMENT_INVALID") -> "The document file was invalid"
            msg.contains("EDIT_BOT_INVITE_FORBIDDEN") -> "Normal users can't edit invites that were created by bots"
            msg.contains("EMAIL_HASH_EXPIRED") -> "The email hash expired"
            msg.contains("EMAIL_INVALID") -> "The given email is invalid"
            msg.contains("EMAIL_VERIFY_EXPIRED") -> "The verification email has expired"
            msg.contains("EMOJI_INVALID") -> "The specified theme emoji is invalid"
            msg.contains("EMOTICON_EMPTY") -> "The emoticon field cannot be empty"
            msg.contains("EMOTICON_INVALID") -> "The specified emoticon cannot be used"
            msg.contains("ENCRYPTED_MESSAGE_INVALID") -> "Encrypted message invalid"
            msg.contains("ENCRYPTION_DECLINED") -> "The secret chat was declined"
            msg.contains("ENCRYPTION_ID_INVALID") -> "The provided secret chat ID is invalid"
            msg.contains("ENTITIES_TOO_LONG") -> "The message formatting entities are too long"
            msg.contains("ENTITY_BOUNDS_INVALID") -> "Invalid formatting entity bounds"
            msg.contains("ENTITY_MENTION_USER_INVALID") -> "You can't mention this user"
            msg.contains("EXPIRE_DATE_INVALID") -> "The specified expiration date is invalid"
            msg.contains("EXTERNAL_URL_INVALID") -> "External URL invalid"
            msg.contains("FILEREF_UPGRADE_NEEDED") -> "The file reference needs to be refreshed"
            msg.contains("FILE_CONTENT_TYPE_INVALID") -> "File content-type is invalid"
            msg.contains("FILE_ID_INVALID") -> "The provided file id is invalid"
            msg.contains("FILE_PARTS_INVALID") -> "The number of file parts is invalid"
            msg.contains("FILE_PART_0_MISSING") -> "File part 0 missing"
            msg.contains("FILE_PART_EMPTY") -> "The provided file part is empty"
            msg.contains("FILE_PART_INVALID") -> "The file part number is invalid"
            msg.contains("FILE_PART_LENGTH_INVALID") -> "The length of a file part is invalid"
            msg.contains("FILE_PART_SIZE_CHANGED") -> "The file part size cannot change during upload"
            msg.contains("FILE_PART_SIZE_INVALID") -> "Invalid file part size"
            msg.contains("FILE_PART_TOO_BIG") -> "The uploaded file part is too big"
            msg.contains("FILE_REFERENCE_EMPTY") -> "The file reference must exist to access the media"
            msg.contains("FILE_REFERENCE_EXPIRED") -> "The file reference has expired"
            msg.contains("FILE_REFERENCE_INVALID") -> "The file reference is invalid"
            msg.contains("FILTER_ID_INVALID") -> "The specified filter ID is invalid"
            msg.contains("FILTER_TITLE_EMPTY") -> "The title field of the filter is empty"
            msg.contains("FIRSTNAME_INVALID") -> "The first name is invalid"
            msg.contains("FLOOD_WAIT") -> "Too many requests, please wait"
            msg.contains("FLOOD_PREMIUM_WAIT") -> "Too many requests, please wait (non-premium)"
            msg.contains("FOLDER_ID_EMPTY") -> "The folder you tried to delete was already empty"
            msg.contains("FOLDER_ID_INVALID") -> "The folder you tried to use was not valid"
            msg.contains("FRESH_CHANGE_ADMINS_FORBIDDEN") -> "Recently logged-in users cannot add or change admins"
            msg.contains("FRESH_CHANGE_PHONE_FORBIDDEN") -> "Recently logged-in users cannot use this request"
            msg.contains("FRESH_RESET_AUTHORISATION_FORBIDDEN") -> "The current session is too new"
            msg.contains("FROM_PEER_INVALID") -> "The given from_user peer cannot be used"
            msg.contains("GAME_BOT_INVALID") -> "You cannot send that game with the current bot"
            msg.contains("GEO_POINT_INVALID") -> "Invalid geoposition provided"
            msg.contains("GIF_CONTENT_TYPE_INVALID") -> "GIF content-type invalid"
            msg.contains("GIF_ID_INVALID") -> "The provided GIF ID is invalid"
            msg.contains("GROUPCALL_ALREADY_DISCARDED") -> "The group call was already discarded"
            msg.contains("GROUPCALL_FORBIDDEN") -> "The group call has already ended"
            msg.contains("GROUPCALL_INVALID") -> "The specified group call is invalid"
            msg.contains("GROUPCALL_JOIN_MISSING") -> "You haven't joined this group call"
            msg.contains("GROUPED_MEDIA_INVALID") -> "Invalid grouped media"
            msg.contains("HASH_INVALID") -> "The provided hash is invalid"
            msg.contains("HISTORY_GET_FAILED") -> "Fetching of history failed"
            msg.contains("IMAGE_PROCESS_FAILED") -> "Failure while processing image"
            msg.contains("IMPORT_FILE_INVALID") -> "The file is too large to be imported"
            msg.contains("IMPORT_FORMAT_UNRECOGNIZED") -> "Unknown import format"
            msg.contains("IMPORT_ID_INVALID") -> "The specified import ID is invalid"
            msg.contains("INLINE_BOT_REQUIRED") -> "The action must be performed through an inline bot callback"
            msg.contains("INLINE_RESULT_EXPIRED") -> "The inline query expired"
            msg.contains("INPUT_CONSTRUCTOR_INVALID") -> "The provided constructor is invalid"
            msg.contains("INPUT_FETCH_ERROR") -> "An error occurred while deserializing TL parameters"
            msg.contains("INPUT_FETCH_FAIL") -> "Failed deserializing TL payload"
            msg.contains("INPUT_FILTER_INVALID") -> "The search query filter is invalid"
            msg.contains("INPUT_LAYER_INVALID") -> "The provided layer is invalid"
            msg.contains("INPUT_METHOD_INVALID") -> "The invoked method does not exist anymore"
            msg.contains("INPUT_REQUEST_TOO_LONG") -> "The input request was too long"
            msg.contains("INPUT_TEXT_EMPTY") -> "The specified text is empty"
            msg.contains("INPUT_USER_DEACTIVATED") -> "The specified user was deleted"
            msg.contains("INVITE_FORBIDDEN_WITH_JOINAS") -> "Anonymous channel users can't invite others to calls"
            msg.contains("INVITE_HASH_EMPTY") -> "The invite hash is empty"
            msg.contains("INVITE_HASH_EXPIRED") -> "The invite link has expired"
            msg.contains("INVITE_HASH_INVALID") -> "The invite link is invalid"
            msg.contains("INVITE_REQUEST_SENT") -> "You have successfully requested to join this chat or channel"
            msg.contains("INVITE_REVOKED_MISSING") -> "The specified invite link was already revoked or is invalid"
            msg.contains("LANG_CODE_INVALID") -> "The specified language code is invalid"
            msg.contains("LANG_PACK_INVALID") -> "The provided language pack is invalid"
            msg.contains("LASTNAME_INVALID") -> "The last name is invalid"
            msg.contains("LIMIT_INVALID") -> "An invalid limit was provided"
            msg.contains("LOCATION_INVALID") -> "The location given for a file was invalid"
            msg.contains("MAX_ID_INVALID") -> "The provided max ID is invalid"
            msg.contains("MAX_QTS_INVALID") -> "The provided QTS were invalid"
            msg.contains("MD5_CHECKSUM_INVALID") -> "The MD5 check-sums do not match"
            msg.contains("MEDIA_CAPTION_TOO_LONG") -> "The caption is too long"
            msg.contains("MEDIA_EMPTY") -> "The provided media object is invalid"
            msg.contains("MEDIA_GROUPED_INVALID") -> "You tried to send media of different types in an album"
            msg.contains("MEDIA_INVALID") -> "Media invalid"
            msg.contains("MEDIA_NEW_INVALID") -> "The new media to edit the message with is invalid"
            msg.contains("MEDIA_PREV_INVALID") -> "The old media cannot be edited with anything else"
            msg.contains("MEGAGROUP_ID_INVALID") -> "The group is invalid"
            msg.contains("MEGAGROUP_REQUIRED") -> "The request can only be used with a megagroup channel"
            msg.contains("MESSAGE_AUTHOR_REQUIRED") -> "Message author required"
            msg.contains("MESSAGE_DELETE_FORBIDDEN") -> "You can't delete this message"
            msg.contains("MESSAGE_EDIT_TIME_EXPIRED") -> "You can't edit this message anymore"
            msg.contains("MESSAGE_EMPTY") -> "Message is empty"
            msg.contains("MESSAGE_IDS_EMPTY") -> "No message ids were provided"
            msg.contains("MESSAGE_ID_INVALID") -> "The specified message ID is invalid"
            msg.contains("MESSAGE_NOT_MODIFIED") -> "Message content was not changed"
            msg.contains("MESSAGE_POLL_CLOSED") -> "The poll was closed and can no longer be voted on"
            msg.contains("MESSAGE_TOO_LONG") -> "Message was too long"
            msg.contains("METHOD_INVALID") -> "The API method is invalid"
            msg.contains("MULTI_MEDIA_TOO_LONG") -> "Too many media files in the album"
            msg.contains("NEW_SALT_INVALID") -> "The new salt is invalid"
            msg.contains("NEW_SETTINGS_INVALID") -> "The new settings are invalid"
            msg.contains("OFFSET_INVALID") -> "The given offset was invalid"
            msg.contains("OFFSET_PEER_ID_INVALID") -> "The provided offset peer is invalid"
            msg.contains("OPTIONS_TOO_MUCH") -> "You defined too many options for the poll"
            msg.contains("OPTION_INVALID") -> "The option specified is invalid"
            msg.contains("PACK_SHORT_NAME_INVALID") -> "Invalid sticker pack name"
            msg.contains("PACK_SHORT_NAME_OCCUPIED") -> "A stickerpack with this name already exists"
            msg.contains("PARTICIPANTS_TOO_FEW") -> "Not enough participants"
            msg.contains("PARTICIPANT_ID_INVALID") -> "The specified participant ID is invalid"
            msg.contains("PASSWORD_EMPTY") -> "The provided password is empty"
            msg.contains("PASSWORD_HASH_INVALID") -> "The password you entered is invalid"
            msg.contains("PASSWORD_MISSING") -> "The account must have 2-factor authentication enabled"
            msg.contains("PASSWORD_RECOVERY_EXPIRED") -> "The recovery code has expired"
            msg.contains("PASSWORD_RECOVERY_NA") -> "No email was set, can't recover password via email"
            msg.contains("PASSWORD_REQUIRED") -> "The account must have 2-factor authentication enabled"
            msg.contains("PAYMENT_PROVIDER_INVALID") -> "The payment provider was not recognised or its token was invalid"
            msg.contains("PEER_FLOOD") -> "Too many requests"
            msg.contains("PEER_ID_INVALID") -> "Invalid peer: the user or chat could not be found"
            msg.contains("PEER_ID_NOT_SUPPORTED") -> "The provided peer ID is not supported"
            msg.contains("PHONE_CODE_EMPTY") -> "The phone code is missing"
            msg.contains("PHONE_CODE_EXPIRED") -> "The verification code has expired"
            msg.contains("PHONE_CODE_HASH_EMPTY") -> "The phone code hash is missing"
            msg.contains("PHONE_CODE_INVALID") -> "The verification code is invalid"
            msg.contains("PHONE_HASH_EXPIRED") -> "An invalid or expired phone_code_hash was provided"
            msg.contains("PHONE_NOT_OCCUPIED") -> "No user is associated to the specified phone number"
            msg.contains("PHONE_NUMBER_APP_SIGNUP_FORBIDDEN") -> "You can't sign up using this app"
            msg.contains("PHONE_NUMBER_BANNED") -> "This phone number has been banned"
            msg.contains("PHONE_NUMBER_FLOOD") -> "You asked for the code too many times"
            msg.contains("PHONE_NUMBER_INVALID") -> "The phone number is invalid"
            msg.contains("PHONE_NUMBER_OCCUPIED") -> "The phone number is already in use"
            msg.contains("PHONE_NUMBER_UNOCCUPIED") -> "The phone number is not yet being used"
            msg.contains("PHONE_PASSWORD_FLOOD") -> "You have tried logging in too many times"
            msg.contains("PHONE_PASSWORD_PROTECTED") -> "This phone is password protected"
            msg.contains("PHOTO_CONTENT_TYPE_INVALID") -> "Photo mime-type invalid"
            msg.contains("PHOTO_CROP_SIZE_SMALL") -> "Photo is too small"
            msg.contains("PHOTO_EXT_INVALID") -> "The extension of the photo is invalid"
            msg.contains("PHOTO_FILE_MISSING") -> "Profile photo file missing"
            msg.contains("PHOTO_ID_INVALID") -> "Photo id is invalid"
            msg.contains("PHOTO_INVALID") -> "Photo invalid"
            msg.contains("PHOTO_INVALID_DIMENSIONS") -> "The photo dimensions are invalid"
            msg.contains("PHOTO_SAVE_FILE_INVALID") -> "The photo is too large to send"
            msg.contains("PINNED_DIALOGS_TOO_MUCH") -> "Too many pinned dialogs"
            msg.contains("PIN_RESTRICTED") -> "You can't pin messages in private chats with other people"
            msg.contains("POLL_ANSWERS_INVALID") -> "The poll did not have enough answers or had too many"
            msg.contains("POLL_ANSWER_INVALID") -> "One of the poll answers is not acceptable"
            msg.contains("POLL_OPTION_DUPLICATE") -> "A duplicate option was sent in the same poll"
            msg.contains("POLL_OPTION_INVALID") -> "A poll option used invalid data"
            msg.contains("POLL_QUESTION_INVALID") -> "The poll question was either empty or too long"
            msg.contains("POLL_UNSUPPORTED") -> "This layer does not support polls"
            msg.contains("POLL_VOTE_REQUIRED") -> "Cast a vote in the poll before calling this method"
            msg.contains("PREMIUM_ACCOUNT_REQUIRED") -> "A premium account is required to execute this action"
            msg.contains("PRIVACY_KEY_INVALID") -> "The privacy key is invalid"
            msg.contains("PRIVACY_TOO_LONG") -> "Cannot add that many entities in a single request"
            msg.contains("PRIVACY_VALUE_INVALID") -> "The privacy value is invalid"
            msg.contains("RANDOM_ID_DUPLICATE") -> "Duplicate message ID, please try again"
            msg.contains("RANDOM_ID_EMPTY") -> "Random ID empty"
            msg.contains("RANDOM_ID_INVALID") -> "A provided random ID is invalid"
            msg.contains("REACTIONS_TOO_MANY") -> "Too many different reactions on this message"
            msg.contains("REACTION_EMPTY") -> "No reaction provided"
            msg.contains("REACTION_INVALID") -> "Invalid reaction"
            msg.contains("REPLY_MARKUP_INVALID") -> "The provided reply markup is invalid"
            msg.contains("REPLY_MARKUP_TOO_LONG") -> "The data embedded in the reply markup buttons was too much"
            msg.contains("RESULTS_TOO_MUCH") -> "You sent too many results"
            msg.contains("RESULT_ID_DUPLICATE") -> "Duplicated IDs on the sent results"
            msg.contains("RIGHT_FORBIDDEN") -> "Your admin rights do not allow you to do this"
            msg.contains("RIGHTS_NOT_MODIFIED") -> "The new admin rights are equal to the old rights"
            msg.contains("RPC_CALL_FAIL") -> "Telegram is having internal issues, please try again later"
            msg.contains("RPC_MCGET_FAIL") -> "Telegram is having internal issues, please try again later"
            msg.contains("SCHEDULE_DATE_INVALID") -> "Invalid schedule date provided"
            msg.contains("SCHEDULE_DATE_TOO_LATE") -> "The date you tried to schedule is too far in the future"
            msg.contains("SCHEDULE_TOO_MUCH") -> "You cannot schedule more messages in this chat"
            msg.contains("SEARCH_QUERY_EMPTY") -> "The search query is empty"
            msg.contains("SEND_AS_PEER_INVALID") -> "You can't send messages as the specified peer"
            msg.contains("SEND_CODE_UNAVAILABLE") -> "All available verification options have been used"
            msg.contains("SEND_MESSAGE_MEDIA_INVALID") -> "The message media was invalid or not specified"
            msg.contains("SEND_MESSAGE_TYPE_INVALID") -> "The message type is invalid"
            msg.contains("SESSION_EXPIRED") -> "The authorization has expired"
            msg.contains("SESSION_PASSWORD_NEEDED") -> "Two-factor authentication is required"
            msg.contains("SESSION_REVOKED") -> "The authorization has been invalidated"
            msg.contains("SHA256_HASH_INVALID") -> "The provided SHA256 hash is invalid"
            msg.contains("SLOWMODE_WAIT") -> "Slow mode is active, please wait"
            msg.contains("SLOWMODE_MULTI_MSGS_DISABLED") -> "Slowmode is enabled, you cannot forward multiple messages"
            msg.contains("SRP_ID_INVALID") -> "Invalid SRP ID, please try again"
            msg.contains("SRP_PASSWORD_CHANGED") -> "Password has changed"
            msg.contains("STICKERSET_INVALID") -> "The provided sticker set is invalid"
            msg.contains("STICKERS_TOO_MUCH") -> "There are too many stickers in this stickerpack"
            msg.contains("STICKER_DOCUMENT_INVALID") -> "The sticker file was invalid"
            msg.contains("STICKER_EMOJI_INVALID") -> "Sticker emoji invalid"
            msg.contains("STICKER_FILE_INVALID") -> "Sticker file invalid"
            msg.contains("STICKER_INVALID") -> "The provided sticker is invalid"
            msg.contains("TAKEOUT_INVALID") -> "The takeout session has been invalidated"
            msg.contains("TAKEOUT_REQUIRED") -> "You must initialize a takeout request first"
            msg.contains("TIMEOUT") -> "A timeout occurred while fetching data"
            msg.contains("TOKEN_INVALID") -> "The provided token is invalid"
            msg.contains("TOPIC_DELETED") -> "The topic was deleted"
            msg.contains("TTL_DAYS_INVALID") -> "The provided TTL is invalid"
            msg.contains("TTL_MEDIA_INVALID") -> "The provided media cannot be used with a TTL"
            msg.contains("TTL_PERIOD_INVALID") -> "The provided TTL Period is invalid"
            msg.contains("UNKNOWN_METHOD") -> "The method you tried to call cannot be called on non-CDN DCs"
            msg.contains("URL_INVALID") -> "The URL used was invalid"
            msg.contains("USERNAME_INVALID") -> "Nobody is using this username, or the username is unacceptable"
            msg.contains("USERNAME_NOT_MODIFIED") -> "The username is not different from the current username"
            msg.contains("USERNAME_NOT_OCCUPIED") -> "The username is not in use by anyone else yet"
            msg.contains("USERNAME_OCCUPIED") -> "The username is already taken"
            msg.contains("USERS_TOO_FEW") -> "Not enough users"
            msg.contains("USERS_TOO_MUCH") -> "The maximum number of users has been exceeded"
            msg.contains("USER_ADMIN_INVALID") -> "Either you're not an admin or you tried to ban an admin that you didn't promote"
            msg.contains("USER_ALREADY_INVITED") -> "You have already invited this user"
            msg.contains("USER_ALREADY_PARTICIPANT") -> "The user is already a member of this chat"
            msg.contains("USER_BANNED_IN_CHANNEL") -> "You're banned from sending messages in this channel"
            msg.contains("USER_BLOCKED") -> "User blocked"
            msg.contains("USER_BOT_INVALID") -> "This method can only be called by a bot"
            msg.contains("USER_BOT_REQUIRED") -> "This method can only be called by a bot"
            msg.contains("USER_CHANNELS_TOO_MUCH") -> "One of the users you tried to add is already in too many channels"
            msg.contains("USER_CREATOR") -> "You can't leave this channel, because you're its creator"
            msg.contains("USER_DEACTIVATED_BAN") -> "The user has been deleted/deactivated"
            msg.contains("USER_DEACTIVATED") -> "The user has been deleted/deactivated"
            msg.contains("USER_DELETED") -> "You can't send this message because the other participant deleted their account"
            msg.contains("USER_ID_INVALID") -> "Invalid object ID for a user"
            msg.contains("USER_INVALID") -> "The given user was invalid"
            msg.contains("USER_IS_BLOCKED") -> "User is blocked"
            msg.contains("USER_IS_BOT") -> "Bots can't send messages to other bots"
            msg.contains("USER_KICKED") -> "This user was kicked from this chat"
            msg.contains("USER_NOT_MUTUAL_CONTACT") -> "The provided user is not a mutual contact"
            msg.contains("USER_NOT_PARTICIPANT") -> "You are not a member of this chat"
            msg.contains("USER_PRIVACY_RESTRICTED") -> "The user's privacy settings don't allow this"
            msg.contains("USER_RESTRICTED") -> "You're spamreported, you can't create channels or chats"
            msg.contains("VIDEO_CONTENT_TYPE_INVALID") -> "The video content type is not supported"
            msg.contains("VIDEO_FILE_INVALID") -> "The given video cannot be used"
            msg.contains("VOICE_MESSAGES_FORBIDDEN") -> "This user doesn't allow voice messages"
            msg.contains("WALLPAPER_FILE_INVALID") -> "The given file cannot be used as a wallpaper"
            msg.contains("WALLPAPER_INVALID") -> "The input wallpaper was not valid"
            msg.contains("WEBDOCUMENT_INVALID") -> "Invalid webdocument URL provided"
            msg.contains("WEBPAGE_CURL_FAILED") -> "Failure while fetching the webpage"
            msg.contains("WORKER_BUSY_TOO_LONG_RETRY") -> "Telegram workers are too busy to respond immediately"
            msg.contains("YOU_BLOCKED_USER") -> "You blocked this user"
            msg.contains("FROZEN_METHOD_INVALID") -> "You tried to use a method that is not available for frozen accounts"
            msg.contains("FROZEN_PARTICIPANT_MISSING") -> "Your account is frozen and can't access the chat"
            else -> msg
        }
    }

    suspend fun sendLocation(conversationId: String, lat: Double, long: Double): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            val media = InputMediaGeoPoint(InputGeoPoint(lat, long))
            client.invoke(MessagesSendMedia(peer, media, "", random.nextLong())) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendLocation failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun unbanMember(conversationId: String, userId: Long): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        if (peer !is InputPeerChannel) return false
        val cached = peerCache[userId]
        val inputUser = if (cached is InputPeerUser) InputUser(cached.userId, cached.accessHash) else return false
        return try {
            client.invoke(ChannelsEditBanned(
                InputChannel(peer.channelId, peer.accessHash), inputUser
            )) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "unbanMember failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun joinChannel(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        if (peer !is InputPeerChannel) return false
        return try {
            client.invoke(ChannelsJoinChannel(
                InputChannel(peer.channelId, peer.accessHash)
            )) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "joinChannel failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun setGroupAvatar(conversationId: String, imageBytes: ByteArray): Boolean {
        if (_state.value !is State.Connected) return false
        if (isTopicConversation(conversationId)) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        if (peer is InputPeerUser) return false
        return try {
            val fileId = random.nextLong()
            val parts = uploadFileParts(client, fileId, imageBytes, useBig = false)
            val inputFile = InputFile(fileId, parts, "avatar.jpg")
            val inputPhoto = InputChatUploadedPhoto(inputFile)
            when (peer) {
                is InputPeerChat -> {
                    client.invoke(MessagesEditChatPhoto(peer.chatId, inputPhoto)) { TlRegistry.decode(it) }
                }
                is InputPeerChannel -> {
                    client.invoke(ChannelsEditPhoto(InputChannel(peer.channelId, peer.accessHash), inputPhoto)) { TlRegistry.decode(it) }
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setGroupAvatar failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun removeGroupAvatar(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        if (isTopicConversation(conversationId)) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        if (peer is InputPeerUser) return false
        return try {
            val emptyPhoto = InputChatPhotoEmpty
            when (peer) {
                is InputPeerChat -> {
                    client.invoke(MessagesEditChatPhoto(peer.chatId, emptyPhoto)) { TlRegistry.decode(it) }
                }
                is InputPeerChannel -> {
                    client.invoke(ChannelsEditPhoto(InputChannel(peer.channelId, peer.accessHash), emptyPhoto)) { TlRegistry.decode(it) }
                }
                else -> return false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "removeGroupAvatar failed: ${humanizeError(t)}")
            false
        }
    }

    suspend fun deleteMessage(
        conversationId: String,
        messageId: Int,
        revokeForEveryone: Boolean = true,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            when (peer) {
                is InputPeerChannel -> {
                    val inputChannel = InputChannel(peer.channelId, peer.accessHash)
                    client.invoke(ChannelsDeleteMessages(inputChannel, listOf(messageId))) { TlRegistry.decode(it) }
                }
                else -> {
                    client.invoke(MessagesDeleteMessages(listOf(messageId), revokeForEveryone)) { TlRegistry.decode(it) }
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "deleteMessage failed: ${humanizeError(t)}")
            false
        }
    }

    private fun fullyQualifyEmoji(emoji: String): String {
        if (emoji.contains("\uFE0F")) return emoji
        val sb = StringBuilder()
        var i = 0
        while (i < emoji.length) {
            val cp = Character.codePointAt(emoji, i)
            val charCount = Character.charCount(cp)
            sb.appendCodePoint(cp)
            val nextIdx = i + charCount
            val nextIsVS16 = nextIdx < emoji.length && emoji[nextIdx] == '\uFE0F'
            if (!nextIsVS16 && needsVS16(cp)) {
                sb.append('\uFE0F')
            }
            i = nextIdx
        }
        return sb.toString()
    }

    private fun needsVS16(cp: Int): Boolean = cp in vs16Codepoints

    private val vs16Codepoints = setOf(
        0x00A9, 0x00AE,
        0x203C, 0x2049, 0x2122, 0x2139,
        0x2194, 0x2195, 0x2196, 0x2197, 0x2198, 0x2199,
        0x21A9, 0x21AA,
        0x231A, 0x231B,
        0x2328,
        0x23CF,
        0x23E9, 0x23EA, 0x23EB, 0x23EC, 0x23ED, 0x23EE, 0x23EF,
        0x23F0, 0x23F1, 0x23F2, 0x23F3, 0x23F8, 0x23F9, 0x23FA,
        0x24C2,
        0x25AA, 0x25AB, 0x25B6, 0x25C0,
        0x25FB, 0x25FC, 0x25FD, 0x25FE,
        0x2600, 0x2601, 0x2602, 0x2603, 0x2604,
        0x260E, 0x2611, 0x2614, 0x2615, 0x2618,
        0x261D,
        0x2620, 0x2622, 0x2623, 0x2626, 0x262A, 0x262E, 0x262F,
        0x2638, 0x2639, 0x263A,
        0x2640, 0x2642,
        0x2648, 0x2649, 0x264A, 0x264B, 0x264C, 0x264D, 0x264E, 0x264F,
        0x2650, 0x2651, 0x2652, 0x2653,
        0x265F, 0x2660, 0x2663, 0x2665, 0x2666, 0x2668,
        0x267B, 0x267E, 0x267F,
        0x2692, 0x2693, 0x2694, 0x2695, 0x2696, 0x2697,
        0x2699, 0x269B, 0x269C,
        0x26A0, 0x26A1, 0x26A7,
        0x26AA, 0x26AB,
        0x26B0, 0x26B1,
        0x26BD, 0x26BE,
        0x26C4, 0x26C5, 0x26C8,
        0x26CE, 0x26CF,
        0x26D1, 0x26D3, 0x26D4,
        0x26E9, 0x26EA,
        0x26F0, 0x26F1, 0x26F2, 0x26F3, 0x26F4, 0x26F5,
        0x26F7, 0x26F8, 0x26F9, 0x26FA,
        0x26FD,
        0x2702, 0x2705, 0x2708, 0x2709, 0x270A, 0x270B, 0x270C, 0x270D, 0x270F,
        0x2712, 0x2714, 0x2716, 0x271D,
        0x2721, 0x2728,
        0x2733, 0x2734,
        0x2744, 0x2747,
        0x274C, 0x274E,
        0x2753, 0x2754, 0x2755, 0x2757,
        0x2763, 0x2764,
        0x2795, 0x2796, 0x2797,
        0x27A1, 0x27B0, 0x27BF,
        0x2934, 0x2935,
        0x2B05, 0x2B06, 0x2B07,
        0x2B1B, 0x2B1C, 0x2B50, 0x2B55,
        0x3030, 0x303D, 0x3297, 0x3299,
    )
}
