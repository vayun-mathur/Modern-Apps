package com.vayunmathur.messages.util

import android.content.Context
import android.util.Log
import com.vayunmathur.messages.data.Conversation
import com.vayunmathur.messages.data.Message
import com.vayunmathur.messages.data.MessageAttachment
import com.vayunmathur.messages.data.MessageDirection
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.data.MessageState
import com.vayunmathur.messages.data.MessagesDatabase
import com.vayunmathur.messages.data.buildMessagesDatabase
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.gmessages.GMessagesClient
import com.vayunmathur.messages.gvoice.GVoiceClient
import com.vayunmathur.messages.meta.InstagramClient
import com.vayunmathur.messages.meta.MetaClient
import com.vayunmathur.messages.signal.SignalClient
import com.vayunmathur.messages.telegram.TelegramClient
import com.vayunmathur.messages.whatsapp.WhatsAppClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
        // Drop the disconnected source's cached threads so stale conversations don't linger.
        scope.launch {
            db.messageDao().deleteAllForConvPrefix("${source.idPrefix}:%")
            db.conversationDao().deleteAllForSource(source)
        }
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
        touchConversationOutgoing(conversationId, body)
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
        touchConversationOutgoing(conversationId, previewBody)
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

    /**
     * Create a poll on [conversationId]. Inserts a PENDING preview row so
     * the UI updates immediately, then routes to the per-source client's
     * canonical `sendPoll` (see MEDIA_FEATURES_CONTRACTS.md §2b). Sources
     * whose protocol has no poll concept (SMS/RCS) return false.
     */
    suspend fun sendPoll(
        conversationId: String,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
    ): Boolean {
        val source = sourceFor(conversationId) ?: return false
        val pendingId = "${source.idPrefix}:pending:${System.currentTimeMillis()}"
        db.messageDao().upsert(
            Message(
                id = pendingId,
                conversationId = conversationId,
                body = "📊 $question",
                direction = MessageDirection.OUTGOING,
                state = MessageState.PENDING,
                timestamp = System.currentTimeMillis(),
                senderName = null,
            )
        )
        touchConversationOutgoing(conversationId, "📊 $question")
        val ok = when (source) {
            MessageSource.MESSAGES_WEB -> GMessagesClient.sendPoll(conversationId, question, options, allowMultiple)
            MessageSource.VOICE -> GVoiceClient.sendPoll(conversationId, question, options, allowMultiple)
            MessageSource.TELEGRAM -> TelegramClient.sendPoll(conversationId, question, options, allowMultiple)
            MessageSource.SIGNAL -> SignalClient.sendPoll(conversationId, question, options, allowMultiple)
            MessageSource.WHATSAPP -> WhatsAppClient.sendPoll(conversationId, question, options, allowMultiple)
            MessageSource.MESSENGER -> MetaClient.sendPoll(conversationId, question, options, allowMultiple)
            MessageSource.INSTAGRAM -> InstagramClient.sendPoll(conversationId, question, options, allowMultiple)
        }
        db.messageDao().updateState(pendingId, if (ok) MessageState.SENT else MessageState.FAILED)
        return ok
    }

    /**
     * Share a location on [conversationId]. Location is ALWAYS sent as the
     * FindFamily share URL [text] (minted at send time by the UI) via the
     * normal text path — no native location pins on any platform, per
     * product. Platform clients' own sendLocation methods are left unused.
     */
    suspend fun sendLocation(conversationId: String, text: String): Boolean {
        sourceFor(conversationId) ?: return false
        return sendMessage(conversationId, text)
    }

    /**
     * Mark [conversationId] read. Clears the local unread badge immediately
     * (so the UI updates even offline), then routes to the platform client's
     * [sendReadReceipt]. The receipt is ONLY actually sent when read receipts
     * are enabled for that platform — there is no app-level toggle, so each
     * platform gates internally on its own read-receipt/privacy setting and
     * returns false when suppressed. The newest local message row supplies
     * the "up to" id + timestamp. Server-side failures are non-fatal; the
     * local state already reflects "user has seen this".
     */
    suspend fun markConversationRead(conversationId: String) {
        db.conversationDao().markRead(conversationId)
        val source = sourceFor(conversationId) ?: return
        val all = db.messageDao().observeForConversation(conversationId)
            .firstOrNull()
            .orEmpty()
        val latest = all.maxByOrNull { it.timestamp }
        val lastMessageId = latest?.id
        val lastTimestamp = latest?.timestamp ?: 0L
        when (source) {
            MessageSource.MESSAGES_WEB ->
                GMessagesClient.sendReadReceipt(conversationId, lastMessageId, lastTimestamp)
            MessageSource.VOICE ->
                GVoiceClient.sendReadReceipt(conversationId, lastMessageId, lastTimestamp)
            MessageSource.TELEGRAM ->
                TelegramClient.sendReadReceipt(conversationId, lastMessageId, lastTimestamp)
            MessageSource.SIGNAL ->
                SignalClient.sendReadReceipt(conversationId, lastMessageId, lastTimestamp)
            MessageSource.WHATSAPP -> {
                // WhatsApp ignores read receipts that point at our own messages, so the
                // "up to" pointer must be the newest INCOMING message. In groups the
                // receipt also needs that sender as the participant.
                val lastIncoming = all
                    .filter { it.direction == MessageDirection.INCOMING }
                    .maxByOrNull { it.timestamp }
                WhatsAppClient.sendReadReceipt(
                    conversationId,
                    lastIncoming?.id,
                    lastIncoming?.timestamp ?: 0L,
                    senderJid = lastIncoming?.senderId,
                )
            }
            MessageSource.MESSENGER ->
                MetaClient.sendReadReceipt(conversationId, lastMessageId, lastTimestamp)
            MessageSource.INSTAGRAM ->
                InstagramClient.sendReadReceipt(conversationId, lastMessageId, lastTimestamp)
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
            MessageSource.WHATSAPP -> false
            MessageSource.MESSENGER -> false
            MessageSource.INSTAGRAM -> false
        }
        if (ok) db.conversationDao().deleteById(conversationId)
        return ok
    }

    /**
     * Accept a message request on [conversationId]. Routes to the
     * per-source client (Signal/Messenger/Instagram support this; others
     * return false). On success the message-request flag is cleared from
     * the local row's serviceData so the Accept/Block bar disappears.
     */
    suspend fun acceptMessageRequest(conversationId: String): Boolean {
        val source = sourceFor(conversationId) ?: return false
        val ok = when (source) {
            MessageSource.SIGNAL -> SignalClient.acceptMessageRequest(conversationId)
            MessageSource.MESSENGER -> MetaClient.acceptMessageRequest(conversationId)
            MessageSource.INSTAGRAM -> InstagramClient.acceptMessageRequest(conversationId)
            else -> false
        }
        if (ok) {
            val existing = db.conversationDao().get(conversationId)
            if (existing != null) {
                db.conversationDao().upsert(
                    existing.copy(serviceData = withMessageRequestFlag(existing.serviceData, false))
                )
            }
        }
        return ok
    }

    /**
     * Block (and drop) a message-request conversation. For Signal this
     * sends a MessageRequestResponse DELETE via
     * [SignalClient.deleteThread] with `fromMessageRequest = true`; other
     * sources have no block path yet. The local row is removed on success.
     */
    suspend fun blockConversation(conversationId: String): Boolean {
        val source = sourceFor(conversationId) ?: return false
        val ok = when (source) {
            MessageSource.SIGNAL ->
                SignalClient.deleteThread(conversationId, fromMessageRequest = true)
            else -> false
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
                val targetFromMe = msg.direction == MessageDirection.OUTGOING
                val remove = action == ReactionAction.REMOVE
                val ok = if (remove) {
                    WhatsAppClient.removeReaction(
                        msg.conversationId, messageId, targetFromMe, msg.senderId,
                    )
                } else {
                    WhatsAppClient.sendReaction(
                        msg.conversationId, messageId, emoji, targetFromMe, msg.senderId,
                    )
                }
                // WhatsApp doesn't echo our own sends back to this device, so reflect the
                // reaction locally right away (senderId "self") for immediate UI feedback.
                if (ok) applyReaction(messageId, "self", if (remove) null else emoji)
                ok
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
            MessageSource.WHATSAPP -> false
            MessageSource.MESSENGER -> false
            MessageSource.INSTAGRAM -> false
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
            .map { it.conversation }
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
            MessageSource.WHATSAPP -> null
            MessageSource.MESSENGER -> null
            MessageSource.INSTAGRAM -> null
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
            MessageSource.WHATSAPP -> Unit
            MessageSource.MESSENGER -> Unit
            MessageSource.INSTAGRAM -> Unit
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
        collectorJobs += scope.launch {
            WhatsAppClient.state.collect { s ->
                _connectionStates.value =
                    _connectionStates.value + (MessageSource.WHATSAPP to s.toUnified())
            }
        }
        collectorJobs += scope.launch {
            MetaClient.state.collect { s ->
                _connectionStates.value =
                    _connectionStates.value + (MessageSource.MESSENGER to s.toUnified())
            }
        }
        collectorJobs += scope.launch {
            InstagramClient.state.collect { s ->
                _connectionStates.value =
                    _connectionStates.value + (MessageSource.INSTAGRAM to s.toUnified())
            }
        }
        collectorJobs += scope.launch {
            WhatsAppClient.events.collect { handleEvent(it) }
        }
        collectorJobs += scope.launch {
            MetaClient.events.collect { handleEvent(it) }
        }
        collectorJobs += scope.launch {
            InstagramClient.events.collect { handleEvent(it) }
        }
    }

    private suspend fun handleEvent(event: GMEvent) {
        when (event) {
            is GMEvent.ConversationUpdate -> {
                val id = "${event.source.idPrefix}:${event.conversationId}"
                val existing = db.conversationDao().get(id)
                // Persist the message-request flag into serviceData JSON
                // (no schema bump). Sources that signal via serviceData
                // directly (Signal) already carry it; honor the dedicated
                // event field too without clobbering an existing flag.
                // Merge serviceData key-by-key so independent producers
                // (group participantNames vs. the message-request flag from a
                // separate event) don't clobber each other. Incoming wins.
                val baseServiceData = mergeServiceData(existing?.serviceData, event.serviceData)
                val mergedServiceData = if (event.isMessageRequest) {
                    withMessageRequestFlag(baseServiceData, true)
                } else {
                    baseServiceData
                }
                val merged = Conversation(
                    id = id,
                    source = event.source,
                    peerName = event.peerName ?: existing?.peerName,
                    peerPhoneE164 = event.peerPhone ?: existing?.peerPhoneE164,
                    avatarUrl = event.avatarUrl ?: existing?.avatarUrl,
                    lastMessagePreview = event.lastPreview ?: existing?.lastMessagePreview,
                    unreadCount = event.unreadCount,
                    isGroup = event.isGroup,
                    participantCount = event.participantCount,
                    conversationType = event.conversationType,
                    outgoingId = event.outgoingId ?: existing?.outgoingId,
                    serviceData = mergedServiceData,
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
                        timestamp = toEpochMillis(event.timestamp),
                        senderName = event.senderName,
                        senderId = event.senderId,
                        reactionsJson = event.reactionsJson,
                        serviceData = event.serviceData,
                        mediaJson = attachmentsToJson(event.attachments),
                    )
                )
            }
            is GMEvent.IncomingMessage -> {
                val convId = "${event.source.idPrefix}:${event.conversationId}"
                val msgId = "${event.source.idPrefix}:${event.messageId}"
                // Whether we've already stored this exact message. Meta platforms
                // (Instagram/Messenger) re-inject their entire history through
                // IncomingMessage on every reconnect/backfill, so without this we
                // re-notify for the whole message history. Only genuinely new
                // messages (not yet in the DB) should fire a notification below.
                val alreadySeen = db.messageDao().get(msgId) != null
                // Ensure the conversation row exists (messages FK to it) and refresh its preview /
                // unread, otherwise the insert crashes and the message never shows in the thread.
                val existing = db.conversationDao().get(convId)
                db.conversationDao().upsert(
                    Conversation(
                        id = convId,
                        source = event.source,
                        // ConversationUpdate is the authoritative source for the
                        // conversation title (group name / peer). An incoming
                        // message must NOT rename an established conversation to
                        // its per-message sender — otherwise a named group gets
                        // retitled to whoever sent last, and a chat where you sent
                        // last gets named after you. Only use the event's name as
                        // a first-time fallback when there is no existing title.
                        peerName = existing?.peerName ?: event.peerName,
                        peerPhoneE164 = event.peerPhone ?: existing?.peerPhoneE164,
                        avatarUrl = existing?.avatarUrl,
                        lastMessagePreview = event.body,
                        // Don't re-count a message we've already seen — otherwise a
                        // history re-sync inflates the unread badge on every reconnect.
                        unreadCount = (existing?.unreadCount ?: 0) + if (alreadySeen) 0 else 1,
                        isGroup = existing?.isGroup ?: false,
                        participantCount = existing?.participantCount ?: 0,
                        conversationType = existing?.conversationType,
                        outgoingId = existing?.outgoingId,
                        serviceData = existing?.serviceData,
                    )
                )
                db.messageDao().upsert(
                    Message(
                        id = msgId,
                        conversationId = convId,
                        body = event.body,
                        direction = MessageDirection.INCOMING,
                        state = MessageState.DELIVERED,
                        timestamp = toEpochMillis(event.timestamp),
                        // Per-message sender (shown in group bubbles). Never fall
                        // back to the conversation/group name here — an unknown
                        // sender leaves this null rather than mislabeling the
                        // message as being from the group/peer.
                        senderName = event.senderName,
                        senderId = event.senderId,
                        mediaJson = attachmentsToJson(event.attachments),
                    )
                )
                if (backfillComplete[event.source] == true && !alreadySeen) {
                    _incoming.tryEmit(event)
                }
            }
            is GMEvent.ConversationDeleted -> {
                db.conversationDao().deleteById("${event.source.idPrefix}:${event.conversationId}")
            }
            is GMEvent.MessageRequestReceived -> {
                // Meta/Instagram signal message requests out-of-band; flag
                // the existing conversation row's serviceData so the UI can
                // show the Accept/Block bar.
                val convId = "${event.source.idPrefix}:${event.conversationId}"
                val existing = db.conversationDao().get(convId)
                if (existing != null) {
                    db.conversationDao().upsert(
                        existing.copy(
                            serviceData = withMessageRequestFlag(existing.serviceData, true),
                        )
                    )
                }
            }
            is GMEvent.ReadReceipt -> {
                // A "self" read receipt means the thread was read on another of
                // the user's own devices (e.g. Meta LSMarkThreadRead) — clear
                // the local unread badge to match. Peer read receipts (senderId
                // is a contact) are "Seen" indicators for our OUTGOING messages
                // and must not touch our own unread count. The convId is built
                // exactly as the other handlers store it.
                if (event.senderId == "self") {
                    db.conversationDao()
                        .markRead("${event.source.idPrefix}:${event.conversationId}")
                }
            }
            is GMEvent.ReactionReceived -> {
                applyReaction(
                    "${event.source.idPrefix}:${event.messageId}",
                    event.senderId,
                    event.emoji,
                )
            }
            is GMEvent.ReactionRemoved -> {
                applyReaction(
                    "${event.source.idPrefix}:${event.messageId}",
                    event.senderId,
                    null,
                )
            }
            else -> Unit
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

    private fun attachmentsToJson(attachments: List<MessageAttachment>): String? =
        if (attachments.isEmpty()) null
        else Json.encodeToString(ListSerializer(MessageAttachment.serializer()), attachments)

    /**
     * Coerce any platform timestamp to epoch-MILLISECONDS. The inbox is
     * sorted by each thread's most recent message timestamp, so every
     * persisted message timestamp must share one scale or the ordering
     * interleaves and stops being newest-first. Platforms currently
     * WhatsApp `*1000`, gmessages `µs/1000`, Signal/Meta/GVoice native
     * ms), but this is the single choke point that guarantees it — and
     * protects against a future source that forgets. Scales down from
     * µs/ns (≥1e15) and up from seconds (<1e12); leaves 0/ms untouched.
     */
    private fun toEpochMillis(raw: Long): Long {
        if (raw <= 0L) return 0L
        var ts = raw
        while (ts >= 1_000_000_000_000_000L) ts /= 1000L // µs / ns → ms
        if (ts < 1_000_000_000_000L) ts *= 1000L         // seconds → ms
        return ts
    }

    /**
     * Refresh a conversation's preview when the LOCAL user sends. The
     * inbox's sort order and displayed time now derive from the message
     * rows themselves (the outgoing row is inserted with the send time),
     * so we only need to keep the preview text in sync here.
     */
    private suspend fun touchConversationOutgoing(conversationId: String, preview: String) {
        val existing = db.conversationDao().get(conversationId) ?: return
        db.conversationDao().upsert(
            existing.copy(
                lastMessagePreview = preview,
            )
        )
    }

    /**
     * Apply a single sender's reaction to a stored message. Tracks the reaction
     * per-sender in the message's serviceData and rewrites the derived
     * reactions_json the UI renders. A null [emoji] removes [senderId]'s reaction.
     * No-op when the target message isn't stored locally yet.
     */
    private suspend fun applyReaction(dbMessageId: String, senderId: String, emoji: String?) {
        val msg = db.messageDao().get(dbMessageId) ?: return
        val newServiceData = applyReactionToServiceData(msg.serviceData, senderId, emoji)
        val newReactionsJson = reactionsJsonFromServiceData(newServiceData)
        db.messageDao().upsert(
            msg.copy(serviceData = newServiceData, reactionsJson = newReactionsJson)
        )
    }
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
