package com.vayunmathur.messages.util

import android.content.Context
import android.util.Log
import com.vayunmathur.messages.data.Conversation
import com.vayunmathur.messages.data.Message
import com.vayunmathur.messages.data.MessageDirection
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.data.MessageState
import com.vayunmathur.messages.data.MessagesDatabase
import com.vayunmathur.messages.data.buildMessagesDatabase
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.gmessages.GMessagesClient
import com.vayunmathur.messages.gvoice.GVoiceClient
import com.vayunmathur.messages.signal.SignalClient
import com.vayunmathur.messages.telegram.TelegramClient
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges [GMessagesClient] + [GVoiceClient]'s state + event streams
 * into the same Room writes and notification triggers.
 *
 * [connectionStates] is the per-source unified state map (see
 * [SourceConnectionState]) and [incoming] is the new-message fanout for
 * the notification path. Adding a new source = subscribing to its state
 * + event flow here; no consumer needs to change.
 */
object MessagesSessionManager {

    private const val TAG = "MessagesSession"

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private lateinit var db: MessagesDatabase

/** Per-source unified connection state. */
    private val _connectionStates = MutableStateFlow<Map<MessageSource, SourceConnectionState>>(
        mapOf(
            MessageSource.MESSAGES_WEB to SourceConnectionState.Idle,
            MessageSource.VOICE to SourceConnectionState.Idle,
            MessageSource.TELEGRAM to SourceConnectionState.Idle,
            MessageSource.SIGNAL to SourceConnectionState.Idle,
            MessageSource.WHATSAPP to SourceConnectionState.Idle,
            MessageSource.MESSENGER to SourceConnectionState.Idle,
            MessageSource.INSTAGRAM to SourceConnectionState.Idle,
        )
    )
    val connectionStates: StateFlow<Map<MessageSource, SourceConnectionState>> =
        _connectionStates.asStateFlow()

    /** Stream of "you just got a new message" events for the service to
     *  turn into MessagingStyle notifications. */
    private val _incoming = MutableSharedFlow<GMEvent.IncomingMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<GMEvent.IncomingMessage> = _incoming.asSharedFlow()

    private val collectorJobs = mutableListOf<Job>()

    /** Don't fire incoming-message notifications during the initial scan. */
    private val backfillComplete = mutableMapOf(
        MessageSource.MESSAGES_WEB to false,
        MessageSource.VOICE to false,
        MessageSource.TELEGRAM to false,
        MessageSource.SIGNAL to false,
        MessageSource.WHATSAPP to false,
        MessageSource.MESSENGER to false,
        MessageSource.INSTAGRAM to false,
    )

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        db = buildMessagesDatabase(appContext)
        GMessagesClient.init(appContext)
        GVoiceClient.init(appContext)
        TelegramClient.init(appContext)
        SignalClient.init(appContext)
        com.vayunmathur.messages.whatsapp.WhatsAppClient.init(appContext)
        com.vayunmathur.messages.meta.MetaClient.init(appContext)
        com.vayunmathur.messages.meta.InstagramClient.init(appContext)
        Log.i(TAG, "init")
        wireCollectors()
    }

    fun database(): MessagesDatabase = db

    fun start() {
        if (!initialized.get()) return
        GMessagesClient.start()
        GVoiceClient.start()
        TelegramClient.start()
        SignalClient.start()
        com.vayunmathur.messages.whatsapp.WhatsAppClient.start()
        com.vayunmathur.messages.meta.MetaClient.start()
        com.vayunmathur.messages.meta.InstagramClient.start()
    }

    fun stop() {
        GMessagesClient.stop()
        GVoiceClient.stop()
        TelegramClient.stop()
        SignalClient.stop()
        com.vayunmathur.messages.whatsapp.WhatsAppClient.stop()
        com.vayunmathur.messages.meta.MetaClient.stop()
        com.vayunmathur.messages.meta.InstagramClient.stop()
        backfillComplete[MessageSource.MESSAGES_WEB] = false
        backfillComplete[MessageSource.VOICE] = false
        backfillComplete[MessageSource.TELEGRAM] = false
        backfillComplete[MessageSource.SIGNAL] = false
        backfillComplete[MessageSource.WHATSAPP] = false
        backfillComplete[MessageSource.MESSENGER] = false
        backfillComplete[MessageSource.INSTAGRAM] = false
    }

    /** Stop one source independently — used from the per-source
     *  Disconnect button in Settings. */
    fun stop(source: MessageSource) {
        when (source) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.stop()
            MessageSource.VOICE -> GVoiceClient.stop()
            MessageSource.TELEGRAM -> TelegramClient.stop()
            MessageSource.SIGNAL -> SignalClient.stop()
            MessageSource.WHATSAPP -> com.vayunmathur.messages.whatsapp.WhatsAppClient.stop()
            MessageSource.MESSENGER -> com.vayunmathur.messages.meta.MetaClient.stop()
            MessageSource.INSTAGRAM -> com.vayunmathur.messages.meta.InstagramClient.stop()
        }
        backfillComplete[source] = false
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        val source = sourceFor(conversationId) ?: return false
        // Insert PENDING row immediately so the UI updates.
        val pendingId = "${source.idPrefix}:pending:${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        db.messageDao().upsert(
            Message(
                id = pendingId,
                conversationId = conversationId,
                body = body,
                direction = MessageDirection.OUTGOING,
                state = MessageState.PENDING,
                timestamp = now,
                senderName = null,
            )
        )
        val ok = when (source) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.sendMessage(conversationId, body)
            MessageSource.VOICE -> GVoiceClient.sendMessage(conversationId, body)
            MessageSource.TELEGRAM -> TelegramClient.sendMessage(conversationId, body)
            MessageSource.SIGNAL -> SignalClient.sendMessage(conversationId, body)
            MessageSource.WHATSAPP -> com.vayunmathur.messages.whatsapp.WhatsAppClient.sendMessage(conversationId, body)
            MessageSource.MESSENGER -> com.vayunmathur.messages.meta.MetaClient.sendMessage(conversationId, body)
            MessageSource.INSTAGRAM -> com.vayunmathur.messages.meta.InstagramClient.sendMessage(conversationId, body)
        }
        db.messageDao().updateState(
            pendingId,
            if (ok) MessageState.SENT else MessageState.FAILED,
        )
        return ok
    }

    /**
     * Send an image (or other supported media) on [conversationId].
     * Inserts a PENDING row carrying [caption] (or "[Image]" if blank)
     * so the UI gets immediate feedback, then routes the actual upload
     * to the per-source client.
     */
    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String,
        caption: String?,
    ): Boolean {
        val source = sourceFor(conversationId) ?: return false
        val previewBody = caption?.takeIf { it.isNotBlank() } ?: "[Image]"
        val pendingId = "${source.idPrefix}:pending:${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        db.messageDao().upsert(
            Message(
                id = pendingId,
                conversationId = conversationId,
                body = previewBody,
                direction = MessageDirection.OUTGOING,
                state = MessageState.PENDING,
                timestamp = now,
                senderName = null,
            )
        )
        val ok = when (source) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.sendMedia(
                conversationId = conversationId,
                data = bytes,
                mime = mime,
                fileName = fileName,
                caption = caption,
            )
            MessageSource.VOICE -> GVoiceClient.sendMedia(
                conversationId = conversationId,
                data = bytes,
                mime = mime,
                caption = caption,
            )
            MessageSource.TELEGRAM -> TelegramClient.sendMedia(
                conversationId = conversationId,
                bytes = bytes,
                mime = mime,
                fileName = fileName,
                caption = caption,
            )
            MessageSource.SIGNAL -> SignalClient.sendMedia(
                conversationId = conversationId,
                bytes = bytes,
                mime = mime,
                fileName = fileName,
                caption = caption,
            )
            MessageSource.WHATSAPP -> com.vayunmathur.messages.whatsapp.WhatsAppClient.sendMedia(
                conversationId = conversationId,
                bytes = bytes,
                mimeType = mime,
                fileName = fileName
            )
            MessageSource.MESSENGER -> com.vayunmathur.messages.meta.MetaClient.sendMedia(
                conversationId = conversationId,
                bytes = bytes,
                mimeType = mime,
                fileName = fileName
            )
            MessageSource.INSTAGRAM -> com.vayunmathur.messages.meta.InstagramClient.sendMedia(
                conversationId = conversationId,
                bytes = bytes,
                mimeType = mime,
                fileName = fileName
            )
        }
        db.messageDao().updateState(
            pendingId,
            if (ok) MessageState.SENT else MessageState.FAILED,
        )
        return ok
    }

    suspend fun markRead(conversationId: String) {
        // Always update the local row immediately so the unread badge clears.
        db.conversationDao().markRead(conversationId)
        // Then propagate to the server. Failures are non-fatal — the
        // local state already reflects "user has seen this".
        val source = sourceFor(conversationId) ?: return
        when (source) {
            MessageSource.MESSAGES_WEB -> {
                // gmessages MarkRead is per-message; pick the newest
                // message in the thread (same convention Messages-for-
                // Web uses when the user opens a chat).
                val latest = db.messageDao().observeForConversation(conversationId)
                    .firstOrNull()
                    ?.maxByOrNull { it.timestamp }
                val webMsgId = latest?.id?.substringAfter(':', latest.id)
                GMessagesClient.markRead(conversationId, webMsgId)
            }
            MessageSource.VOICE -> {
                GVoiceClient.markRead(conversationId)
            }
            MessageSource.TELEGRAM -> {
                TelegramClient.markRead(conversationId)
            }
            MessageSource.SIGNAL -> {
                SignalClient.markRead(conversationId)
            }
            MessageSource.WHATSAPP -> {
                com.vayunmathur.messages.whatsapp.WhatsAppClient.markRead(conversationId)
            }
            MessageSource.MESSENGER -> {
                com.vayunmathur.messages.meta.MetaClient.markRead(conversationId)
            }
            MessageSource.INSTAGRAM -> {
                com.vayunmathur.messages.meta.InstagramClient.markRead(conversationId)
            }
        }
    }

    /**
     * Delete [conversationId] on the server. The local row is removed
     * (and cascades clear its messages) once the per-source client
     * confirms — partial deletes leave the row in place so the user
     * can retry from the UI.
     */
    suspend fun deleteConversation(conversationId: String): Boolean {
        val source = sourceFor(conversationId) ?: return false
        val existing = db.conversationDao().get(conversationId)
        val ok = when (source) {
            MessageSource.MESSAGES_WEB -> {
                GMessagesClient.deleteConversation(conversationId, existing?.peerPhoneE164)
            }
            MessageSource.VOICE -> {
                GVoiceClient.deleteThread(conversationId)
            }
            MessageSource.TELEGRAM -> {
                TelegramClient.deleteThread(conversationId)
            }
            MessageSource.SIGNAL -> {
                SignalClient.deleteThread(conversationId)
            }
        }
        if (ok) db.conversationDao().deleteById(conversationId)
        return ok
    }

    /**
     * Add/remove/switch a reaction on a message. Only Google Messages
     * supports reactions — Voice ignores the call and returns false.
     */
    suspend fun sendReaction(messageId: String, emoji: String, action: ReactionAction): Boolean {
        val msg = db.messageDao().get(messageId) ?: return false
        val source = sourceFor(msg.conversationId) ?: return false
        return when (source) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.sendReaction(
                messageId = messageId,
                emoji = emoji,
                action = when (action) {
                    ReactionAction.ADD ->
                        client.Client.SendReactionRequest.Action.ADD
                    ReactionAction.REMOVE ->
                        client.Client.SendReactionRequest.Action.REMOVE
                    ReactionAction.SWITCH ->
                        client.Client.SendReactionRequest.Action.SWITCH
                },
            )
            MessageSource.VOICE -> false
            MessageSource.TELEGRAM -> TelegramClient.sendReaction(
                messageId = messageId,
                conversationId = msg.conversationId,
                emoji = emoji,
                add = action == ReactionAction.ADD || action == ReactionAction.SWITCH,
            )
            MessageSource.SIGNAL -> SignalClient.sendReaction(
                messageId = messageId,
                conversationId = msg.conversationId,
                emoji = emoji,
                add = action == ReactionAction.ADD || action == ReactionAction.SWITCH,
            )
            MessageSource.WHATSAPP -> {
                com.vayunmathur.messages.whatsapp.WhatsAppClient.sendReaction(
                    msg.conversationId,
                    messageId,
                    emoji
                )
                true
            }
            MessageSource.MESSENGER -> {
                com.vayunmathur.messages.meta.MetaClient.sendReaction(
                    msg.conversationId,
                    messageId,
                    emoji
                )
                true
            }
            MessageSource.INSTAGRAM -> {
                com.vayunmathur.messages.meta.InstagramClient.sendReaction(
                    msg.conversationId,
                    messageId,
                    emoji
                )
                true
            }
        }
    }

    /**
     * Notify the peer that the local user is typing.
     * Only Google Messages exposes a typing endpoint; Voice has none.
     */
    suspend fun sendTyping(conversationId: String): Boolean {
        val source = sourceFor(conversationId) ?: return false
        return when (source) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.sendTyping(conversationId)
            MessageSource.VOICE -> false
            MessageSource.TELEGRAM -> TelegramClient.sendTyping(conversationId)
            MessageSource.SIGNAL -> SignalClient.sendTyping(conversationId)
        }
    }

    /**
     * Search device contacts only (no server-side queries).
     * Used by the new-conversation flow when the source is pre-selected.
     */
    suspend fun searchDeviceContacts(query: String): List<ContactSuggestion> {
        val q = query.trim()
        val isPhone = q.startsWith("+") && q.length >= 4
        val results = ContactResolver.search(appContext, q).map { dc ->
            ContactSuggestion(
                displayName = dc.displayName,
                phoneE164 = dc.phoneE164,
                avatarUrl = dc.photoUri,
                source = null,
            )
        }.toMutableList()
        if (isPhone && results.none { it.phoneE164 == q }) {
            results += ContactSuggestion(displayName = q, phoneE164 = q, avatarUrl = null, source = null)
        }
        return results
    }

    /**
     * Search contacts across all available sources.
     *
     * Merges (and deduplicates by phone) hits from:
     *  - **gmessages** server-side contact list (when connected). The
     *    server doesn't accept a query filter so we pull once and
     *    filter client-side. The list is short (a few hundred).
     *  - **gvoice** server-side autocomplete (when connected). Empty
     *    [query] pulls the top ~500; non-empty narrows server-side.
     *  - **Device contacts** via [ContactResolver] for name-only and
     *    number-only matches not already covered by either backend.
     *
     * Returned in stable order: matches whose [ContactSuggestion.source]
     * is non-null come first (we know they're reachable on at least one
     * source), then device-only entries. Within each group, prefix
     * matches on the display name rank above substring matches.
     */
    suspend fun searchContacts(query: String): List<ContactSuggestion> {
        val q = query.trim()
        val isPhone = q.startsWith("+") && q.length >= 4
        val results = mutableListOf<ContactSuggestion>()

        // gmessages: pull the top-contacts list once when query is
        // empty (initial picker open), otherwise fall through to a
        // filter on the full contacts list. The list isn't reactive
        // so we just do a one-shot fetch each call.
        if (GMessagesClient.state.value is com.vayunmathur.messages.gmessages.GMessagesClient.State.Connected) {
            val list = if (q.isEmpty()) GMessagesClient.listTopContacts() else GMessagesClient.listContacts()
            results += list.filter { c -> q.isEmpty() || matches(c, q) }
        }
        // gvoice: server-side filter for non-empty queries; full list
        // for empty.
        if (GVoiceClient.state.value is com.vayunmathur.messages.gvoice.GVoiceClient.State.Connected) {
            results += GVoiceClient.autocompleteContacts(q)
                .filter { c -> q.isEmpty() || matches(c, q) }
        }
        if (TelegramClient.state.value is TelegramClient.State.Connected) {
            results += TelegramClient.searchContacts(q)
                .filter { c -> q.isEmpty() || matches(c, q) }
        }
        if (SignalClient.state.value is SignalClient.State.Connected) {
            results += SignalClient.searchContacts(q)
                .filter { c -> q.isEmpty() || matches(c, q) }
        }
        // Always include device contact matches so users see names from
        // their phone even when neither backend's contact list knows
        // about them. ContactResolver.search supports both name+number.
        results += ContactResolver.search(appContext, q).map { dc ->
            ContactSuggestion(
                displayName = dc.displayName,
                phoneE164 = dc.phoneE164,
                avatarUrl = dc.photoUri,
                source = null,
            )
        }
        // Also: literal-number entry. If the user typed something that
        // looks like a phone number and it isn't already in the list,
        // add a "Send to {number}" row so they can send to a brand-new
        // contact without first saving them.
        if (isPhone && results.none { it.phoneE164 == q }) {
            results += ContactSuggestion(displayName = q, phoneE164 = q, avatarUrl = null, source = null)
        }
        // Dedupe by phone, preferring entries that carry a source.
        return results
            .groupBy { it.phoneE164 ?: it.displayName }
            .map { (_, group) -> group.minByOrNull { if (it.source == null) 1 else 0 }!! }
    }

    private fun matches(c: ContactSuggestion, q: String): Boolean {
        val needle = q.lowercase()
        return c.displayName.lowercase().contains(needle) ||
            (c.phoneE164?.lowercase()?.contains(needle) == true)
    }

    /**
     * Which sources already have an existing thread for [phoneE164]?
     * Drives the "smart routing" decision in the new-conversation
     * picker: zero sources → user picks; one source → route there;
     * two sources → user picks.
     */
    suspend fun resolveSourcesForNumber(phoneE164: String): Set<MessageSource> {
        if (phoneE164.isBlank()) return emptySet()
        // Normalize for comparison: strip non-digits + leading '+'.
        val needle = normalizePhone(phoneE164)
        val all = db.conversationDao().observeAll().firstOrNull().orEmpty()
        return all.asSequence()
            .filter { !it.isGroup && it.peerPhoneE164 != null }
            .filter { normalizePhone(it.peerPhoneE164!!) == needle }
            .map { it.source }
            .toSet()
    }

    private fun normalizePhone(raw: String): String =
        raw.filter { it.isDigit() }.trimStart('0')

    /**
     * Start a brand-new conversation (and send the first text + optional
     * media in the same call).
     *
     * gmessages: GET_OR_CREATE_CONVERSATION → SEND_MESSAGE/SEND_MEDIA.
     * gvoice: builds a ReqSendSMS with `recipients` set so the server
     * creates the thread in one round trip.
     *
     * Returns the new conversation id on success, or null on failure.
     */
    suspend fun sendNewMessage(
        source: MessageSource,
        recipients: List<String>,
        body: String?,
        media: NewMediaPart? = null,
    ): String? {
        if (recipients.isEmpty()) return null
        return when (source) {
            MessageSource.MESSAGES_WEB -> {
                val convId = GMessagesClient.getOrCreateConversation(recipients) ?: return null
                val ok = if (media != null) {
                    GMessagesClient.sendMedia(
                        conversationId = convId,
                        data = media.bytes,
                        mime = media.mime,
                        fileName = media.fileName,
                        caption = body,
                    )
                } else {
                    GMessagesClient.sendMessage(convId, body.orEmpty())
                }
                if (ok) convId else null
            }
            MessageSource.VOICE -> {
                if (media != null) {
                    GVoiceClient.sendNewThreadMedia(
                        recipients = recipients,
                        mime = media.mime,
                        data = media.bytes,
                        caption = body,
                    )
                } else {
                    GVoiceClient.sendNewThread(recipients, body.orEmpty())
                }
            }
            MessageSource.TELEGRAM -> {
                if (media != null) {
                    val convId = TelegramClient.sendNewThread(recipients, body.orEmpty())
                    if (convId != null) {
                        TelegramClient.sendMedia(
                            conversationId = convId,
                            bytes = media.bytes,
                            mime = media.mime,
                            fileName = media.fileName,
                            caption = body,
                        )
                    }
                    convId
                } else {
                    TelegramClient.sendNewThread(recipients, body.orEmpty())
                }
            }
            MessageSource.SIGNAL -> {
                if (media != null) {
                    val convId = SignalClient.sendNewThread(recipients, body.orEmpty())
                    if (convId != null) {
                        SignalClient.sendMedia(
                            conversationId = convId,
                            bytes = media.bytes,
                            mime = media.mime,
                            fileName = media.fileName,
                            caption = body,
                        )
                    }
                    convId
                } else {
                    SignalClient.sendNewThread(recipients, body.orEmpty())
                }
            }
        }
    }

    /**
     * Bulk-write a backfill batch of messages in ONE transaction.
     * Called by the protocol clients when LIST_MESSAGES / GetThread
     * returns — saves dozens of separate Flow notifications when
     * populating a thread.
     */
    suspend fun bulkUpsertMessages(messages: List<Message>) {
        if (messages.isEmpty()) return
        db.messageDao().upsertAll(messages)
    }

    fun forceResync() {
        GMessagesClient.forceResync()
        GVoiceClient.forceResync()
        TelegramClient.forceResync()
        SignalClient.forceResync()
    }

    fun fetchMessages(conversationId: String) {
        when (sourceFor(conversationId)) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.fetchMessages(conversationId)
            MessageSource.VOICE -> GVoiceClient.fetchMessages(conversationId)
            MessageSource.TELEGRAM -> TelegramClient.fetchMessages(conversationId)
            MessageSource.SIGNAL -> SignalClient.fetchMessages(conversationId)
            null -> Unit
        }
    }

    private fun wireCollectors() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs.clear()

        collectorJobs += scope.launch {
            GMessagesClient.state.collect { s ->
                _connectionStates.value =
                    _connectionStates.value + (MessageSource.MESSAGES_WEB to s.toUnified())
            }
        }
        collectorJobs += scope.launch {
            GVoiceClient.state.collect { s ->
                _connectionStates.value =
                    _connectionStates.value + (MessageSource.VOICE to s.toUnified())
            }
        }
        collectorJobs += scope.launch {
            TelegramClient.state.collect { s ->
                _connectionStates.value =
                    _connectionStates.value + (MessageSource.TELEGRAM to s.toUnified())
            }
        }
        collectorJobs += scope.launch {
            SignalClient.state.collect { s ->
                _connectionStates.value =
                    _connectionStates.value + (MessageSource.SIGNAL to s.toUnified())
            }
        }
        collectorJobs += scope.launch {
            GMessagesClient.events.collect { handleEvent(it) }
        }
        collectorJobs += scope.launch {
            GVoiceClient.events.collect { handleEvent(it) }
        }
        collectorJobs += scope.launch {
            TelegramClient.events.collect { handleEvent(it) }
        }
        collectorJobs += scope.launch {
            SignalClient.events.collect { handleEvent(it) }
        }
    }

    private suspend fun handleEvent(event: GMEvent) {
        when (event) {
            is GMEvent.ConversationUpdate -> {
                val id = "${event.source.idPrefix}:${event.conversationId}"
                val existing = db.conversationDao().get(id)
                // One-shot guard: rows persisted before the gmessages
                // microseconds→milliseconds fix have last_ts > 1e14
                // (≈ year 5138 in ms). Treat those as "no existing
                // timestamp" so the first refresh after the fix lands
                // immediately re-anchors to the correct scale rather
                // than holding the stale huge value via maxOf.
                val priorTs = existing?.lastMessageTimestamp
                    ?.takeIf { it < STALE_MICROSECOND_THRESHOLD_MS } ?: 0L
                val merged = Conversation(
                    id = id,
                    source = event.source,
                    peerName = event.peerName ?: existing?.peerName,
                    peerPhoneE164 = event.peerPhone ?: existing?.peerPhoneE164,
                    avatarUrl = event.avatarUrl ?: existing?.avatarUrl,
                    lastMessagePreview = event.lastPreview ?: existing?.lastMessagePreview,
                    lastMessageTimestamp = maxOf(event.lastTimestamp, priorTs),
                    unreadCount = event.unreadCount,
                    isGroup = event.isGroup,
                    participantCount = event.participantCount,
                    conversationType = event.conversationType,
                    outgoingId = event.outgoingId ?: existing?.outgoingId,
                )
                db.conversationDao().upsert(merged)
                backfillComplete[event.source] = true
            }
            is GMEvent.MessageUpdate -> {
                val convId = "${event.source.idPrefix}:${event.conversationId}"
                val msgId = "${event.source.idPrefix}:${event.messageId}"
                db.messageDao().upsert(
                    Message(
                        id = msgId,
                        conversationId = convId,
                        body = event.body,
                        direction = if (event.outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                        state = if (event.outgoing) MessageState.SENT else MessageState.DELIVERED,
                        timestamp = event.timestamp,
                        senderName = event.senderName,
                        reactionsJson = event.reactionsJson,
                    )
                )
            }
            is GMEvent.IncomingMessage -> {
                if (backfillComplete[event.source] == true) {
                    _incoming.tryEmit(event)
                }
                val convId = "${event.source.idPrefix}:${event.conversationId}"
                val msgId = "${event.source.idPrefix}:${event.messageId}"
                db.messageDao().upsert(
                    Message(
                        id = msgId,
                        conversationId = convId,
                        body = event.body,
                        direction = MessageDirection.INCOMING,
                        state = MessageState.DELIVERED,
                        timestamp = event.timestamp,
                        senderName = event.peerName,
                    )
                )
            }
            is GMEvent.MessageDeleted -> {
                db.messageDao().deleteById("${event.source.idPrefix}:${event.messageId}")
            }
            is GMEvent.ConversationDeleted -> {
                db.conversationDao().deleteById("${event.source.idPrefix}:${event.conversationId}")
            }
        }
    }

    private fun sourceFor(conversationId: String): MessageSource? = when {
        conversationId.startsWith("${MessageSource.MESSAGES_WEB.idPrefix}:") -> MessageSource.MESSAGES_WEB
        conversationId.startsWith("${MessageSource.VOICE.idPrefix}:") -> MessageSource.VOICE
        conversationId.startsWith("${MessageSource.TELEGRAM.idPrefix}:") -> MessageSource.TELEGRAM
        conversationId.startsWith("${MessageSource.SIGNAL.idPrefix}:") -> MessageSource.SIGNAL
        conversationId.startsWith("${MessageSource.WHATSAPP.idPrefix}:") -> MessageSource.WHATSAPP
        conversationId.startsWith("${MessageSource.MESSENGER.idPrefix}:") -> MessageSource.MESSENGER
        conversationId.startsWith("${MessageSource.INSTAGRAM.idPrefix}:") -> MessageSource.INSTAGRAM
        else -> null
    }

    /** ≈ year 5138 in epoch-ms. Any persisted `last_ts` larger than this
     *  is almost certainly a stale microsecond value from before the
     *  gmessages timestamp fix; treat it as missing. */
    private const val STALE_MICROSECOND_THRESHOLD_MS = 100_000_000_000_000L
}

/** Action passed to [MessagesSessionManager.sendReaction]. Mirrors the
 *  three-state add/remove/switch enum in [SendReactionRequest.Action]
 *  without exposing the protobuf type to non-protocol callers. */
enum class ReactionAction { ADD, REMOVE, SWITCH }

/** Media payload for [MessagesSessionManager.sendNewMessage]. Keeping
 *  bytes + meta together so callers don't need a 4-arg overload. */
data class NewMediaPart(
    val bytes: ByteArray,
    val mime: String,
    val fileName: String,
) {
    // ByteArray equality is reference-based by default; provide proper
    // value equality so test fixtures and === sites behave sanely.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NewMediaPart) return false
        return mime == other.mime && fileName == other.fileName && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }
}
