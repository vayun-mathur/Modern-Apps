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
import com.vayunmathur.messages.telegram.mtproto.rpc.RpcException
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject
import com.vayunmathur.messages.util.ContactSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object TelegramClient {

    private const val TAG = "TelegramClient"
    private const val API_ID = 94575
    private const val API_HASH = "a3406de8d171bb422bb6ddf3bbd800e2"

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data class AwaitingCode(val phone: String) : State
        data class AwaitingPassword(val phone: String, val hint: String) : State
        data object Connecting : State
        data object Connected : State
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
    private var pendingPhone: String? = null
    private var phoneCodeHash: String? = null

    private val peerCache = ConcurrentHashMap<Long, TlObject>()
    private val userNameCache = ConcurrentHashMap<Long, String>()

    private var currentPts = 0
    private var currentQts = 0
    private var currentDate = 0
    private var currentSeq = 0

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Log.i(TAG, "init")
        runBlocking {
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
        scope.launch {
            runCatching { apiClient?.invoke(AuthLogOut) { TlRegistry.decode(it) } }
        }
        apiClient?.disconnect()
        apiClient = null
        pendingPhone = null
        phoneCodeHash = null
        peerCache.clear()
        userNameCache.clear()
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
                if (result is AuthAuthorization) {
                    onAuthorized(result.user)
                }
            } catch (e: RpcException) {
                if (e.message.contains("SESSION_PASSWORD_NEEDED")) {
                    try {
                        val client = apiClient ?: return@launch
                        val pwd = client.invoke(AccountGetPassword) { TlRegistry.decode(it) }
                        if (pwd is AuthPassword) {
                            _state.value = State.AwaitingPassword(phone, pwd.hint)
                        }
                    } catch (e2: Exception) {
                        Log.e(TAG, "getPassword failed: ${e2.message}")
                        _state.value = State.AwaitingCode(phone)
                    }
                } else {
                    Log.e(TAG, "submitCode failed: ${e.message}")
                    _state.value = State.AwaitingCode(phone)
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitCode failed: ${e.message}")
                _state.value = State.AwaitingCode(phone)
            }
        }
    }

    fun submitPassword(password: String) {
        val phone = pendingPhone ?: return
        scope.launch {
            try {
                val client = apiClient ?: return@launch
                val pwd = client.invoke(AccountGetPassword) { TlRegistry.decode(it) } as? AuthPassword
                    ?: return@launch
                val algo = pwd.currentAlgo ?: return@launch
                val randomBytes = ByteArray(256)
                random.nextBytes(randomBytes)
                val answer = Srp.computeAnswer(
                    password = password.toByteArray(Charsets.UTF_8),
                    srpB = pwd.srpB,
                    randomA = randomBytes,
                    salt1 = algo.salt1,
                    salt2 = algo.salt2,
                    g = algo.g,
                    p = algo.p,
                )
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

    fun forceResync() {
        if (_state.value !is State.Connected) return
        kickoffBackfill()
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            client.invoke(MessagesSendMessage(peer, body, random.nextLong())) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendMessage failed: ${t.message}")
            false
        }
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
        return try {
            val fileId = random.nextLong()
            val partSize = 512 * 1024
            val parts = (bytes.size + partSize - 1) / partSize
            val useBig = bytes.size > 10 * 1024 * 1024

            for (i in 0 until parts) {
                val start = i * partSize
                val end = minOf(start + partSize, bytes.size)
                val chunk = bytes.copyOfRange(start, end)
                if (useBig) {
                    client.invoke(UploadSaveBigFilePart(fileId, i, parts, chunk)) { TlRegistry.decode(it) }
                } else {
                    client.invoke(UploadSaveFilePart(fileId, i, chunk)) { TlRegistry.decode(it) }
                }
            }

            val inputFile: TlObject = if (useBig) InputFileBig(fileId, parts, fileName)
            else InputFile(fileId, parts, fileName)

            val media: TlObject = if (mime.startsWith("image/")) {
                InputMediaUploadedPhoto(inputFile)
            } else {
                InputMediaUploadedDocument(inputFile, mime, listOf(DocumentAttributeFilename(fileName)))
            }

            client.invoke(MessagesSendMedia(peer, media, caption ?: "", random.nextLong())) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendMedia failed: ${t.message}")
            false
        }
    }

    suspend fun markRead(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            client.invoke(MessagesReadHistory(peer, Int.MAX_VALUE)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "markRead failed: ${t.message}")
            false
        }
    }

    suspend fun deleteThread(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            client.invoke(MessagesDeleteHistory(peer)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "deleteThread failed: ${t.message}")
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
            val reactions = if (add) listOf(InputMessageReactionEmoji(emoji)) else emptyList<TlObject>()
            client.invoke(MessagesSendReaction(peer, msgId, reactions)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendReaction failed: ${t.message}")
            false
        }
    }

    suspend fun sendTyping(conversationId: String): Boolean {
        if (_state.value !is State.Connected) return false
        val client = apiClient ?: return false
        val peer = resolvePeer(conversationId) ?: return false
        return try {
            client.invoke(MessagesSetTyping(peer)) { TlRegistry.decode(it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendTyping failed: ${t.message}")
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
                    if (msg is Message) {
                        _events.emit(msg.toMessageUpdate(chatIdStr))
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
            Log.w(TAG, "sendNewThread failed: ${t.message}")
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
                ContactSuggestion(
                    displayName = name,
                    phoneE164 = user.phone.takeIf { it.isNotBlank() }?.let { "+$it" },
                    avatarUrl = null,
                    source = source,
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
            val client = TelegramApiClient()
            client.connect(
                dc = auth.dc ?: 2,
                existingAuthKey = authKey,
                existingAuthKeyId = authKeyId,
                existingSalt = auth.salt ?: 0L,
                existingSessionId = null,
            )
            apiClient = client
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
        _state.value = State.Connected
        val client = apiClient ?: return
        scope.launch {
            TelegramAuthData(
                phoneNumber = pendingPhone ?: "",
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

    private suspend fun handleUpdate(update: TlObject) {
        when (update) {
            is Updates -> {
                cacheUsers(update.users)
                for (u in update.updates) handleSingleUpdate(u)
                currentDate = update.date
                currentSeq = update.seq
            }
            is UpdatesCombined -> {
                cacheUsers(update.users)
                for (u in update.updates) handleSingleUpdate(u)
                currentDate = update.date
                currentSeq = update.seq
            }
            is UpdateShort -> handleSingleUpdate(update.update)
            is UpdateShortMessage -> {
                val chatId = update.userId.toString()
                val msgId = "${chatId}_${update.id}"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, update.message, update.out, update.date.toLong() * 1000, null))
                if (!update.out) {
                    _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, update.message, userNameCache[update.userId], null, update.date.toLong() * 1000))
                }
                currentPts = update.pts
            }
            is UpdateShortChatMessage -> {
                val chatId = update.chatId.toString()
                val msgId = "${chatId}_${update.id}"
                _events.emit(GMEvent.MessageUpdate(source, chatId, msgId, update.message, update.out, update.date.toLong() * 1000, userNameCache[update.fromId]))
                if (!update.out) {
                    _events.emit(GMEvent.IncomingMessage(source, chatId, msgId, update.message, userNameCache[update.fromId], null, update.date.toLong() * 1000))
                }
                currentPts = update.pts
            }
            is UpdatesTooLong -> {
                scope.launch { recoverGap() }
            }
        }
    }

    private suspend fun handleSingleUpdate(update: TlObject) {
        when (update) {
            is UpdateNewMessage -> {
                val msg = update.message as? Message ?: return
                val chatId = peerToId(msg.peerId)
                _events.emit(msg.toMessageUpdate(chatId))
                if (!msg.out) {
                    _events.emit(GMEvent.IncomingMessage(source, chatId, "${chatId}_${msg.id}", msg.message, senderName(msg.fromId), null, msg.date.toLong() * 1000))
                }
                currentPts = update.pts
            }
            is UpdateNewChannelMessage -> {
                val msg = update.message as? Message ?: return
                val chatId = peerToId(msg.peerId)
                _events.emit(msg.toMessageUpdate(chatId))
                if (!msg.out) {
                    _events.emit(GMEvent.IncomingMessage(source, chatId, "${chatId}_${msg.id}", msg.message, senderName(msg.fromId), null, msg.date.toLong() * 1000))
                }
                currentPts = update.pts
            }
            is UpdateDeleteMessages -> {
                for (id in update.messages) {
                    _events.emit(GMEvent.MessageDeleted(source, "_$id"))
                }
                currentPts = update.pts
            }
            is UpdateEditMessage -> {
                val msg = update.message as? Message ?: return
                val chatId = peerToId(msg.peerId)
                _events.emit(msg.toMessageUpdate(chatId))
                currentPts = update.pts
            }
            is UpdateReadHistoryInbox -> {
                val chatId = peerToId(update.peer)
                _events.emit(GMEvent.ConversationUpdate(source, chatId, null, null, null, null, 0, 0))
            }
        }
    }

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

                    _events.emit(
                        GMEvent.ConversationUpdate(
                            source = source,
                            conversationId = chatId,
                            peerName = name,
                            peerPhone = null,
                            avatarUrl = null,
                            lastPreview = preview,
                            lastTimestamp = tsMs,
                            unreadCount = dialog.unreadCount,
                            isGroup = isGroup,
                            conversationType = "Telegram",
                        )
                    )

                    cachePeerFromDialog(dialog.peer)
                }

                for (dialog in dialogs) {
                    val chatId = peerToId(dialog.peer)
                    val peer = resolvePeer("${source.idPrefix}:$chatId") ?: continue
                    try {
                        val histResult = client.invoke(MessagesGetHistory(peer, limit = 50)) { TlRegistry.decode(it) }
                        val histMsgs = extractMessages(histResult)
                        cacheUsers(extractUsers(histResult))
                        for (msg in histMsgs) {
                            if (msg is Message) {
                                _events.emit(msg.toMessageUpdate(chatId))
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
        val client = apiClient ?: return
        try {
            val diff = client.invoke(UpdatesGetDifference(currentPts, currentDate, currentQts)) { TlRegistry.decode(it) }
            if (diff is UpdatesDifference) {
                cacheUsers(diff.users)
                for (msg in diff.newMessages) {
                    if (msg is Message) {
                        val chatId = peerToId(msg.peerId)
                        _events.emit(msg.toMessageUpdate(chatId))
                    }
                }
                for (u in diff.otherUpdates) handleSingleUpdate(u)
                currentPts = diff.state.pts
                currentQts = diff.state.qts
                currentDate = diff.state.date
                currentSeq = diff.state.seq
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recoverGap failed: ${t.message}")
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
                // Needs access hash from channel, try cache
            }
        }
    }

    private fun cacheUsers(users: List<TlObject>) {
        for (u in users) {
            if (u is User) {
                val name = "${u.firstName} ${u.lastName}".trim()
                if (name.isNotBlank()) userNameCache[u.id] = name
                peerCache[u.id] = InputPeerUser(u.id, u.accessHash)
            }
        }
    }

    private fun cacheChats(chats: List<TlObject>) {
        for (c in chats) {
            when (c) {
                is Chat -> {
                    userNameCache[c.id] = c.title
                    peerCache[c.id] = InputPeerChat(c.id)
                }
                is Channel -> {
                    userNameCache[c.id] = c.title
                    peerCache[c.id] = InputPeerChannel(c.id, c.accessHash)
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

    private fun extractPreview(msg: Message): String? {
        val text = msg.message
        if (text.isNotBlank()) return text
        return when (msg.mediaTypeId) {
            0x695150d7.toInt() -> "[Photo]"
            0x4cf4d72d.toInt() -> "[File]"
            0x70322949.toInt() -> "[Contact]"
            0x56e0d474.toInt() -> "[Location]"
            else -> if (msg.mediaTypeId != 0) "[Media]" else null
        }
    }

    private fun Message.toMessageUpdate(chatId: String): GMEvent.MessageUpdate {
        val msgId = "${chatId}_${this.id}"
        val body = extractPreview(this) ?: ""
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
}
