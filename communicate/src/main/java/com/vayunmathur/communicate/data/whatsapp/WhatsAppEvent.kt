package com.vayunmathur.communicate.data.whatsapp

/**
 * communicate-native event surface for the WhatsApp primary client. Replaces the messages-module
 * `GMEvent` bus: the ported [WhatsAppClient] emits these instead, so `communicate` has no dependency
 * on `messages`. The message-bearing subtypes mirror the original `GMEvent` shapes verbatim (so the
 * port is a mechanical rename), plus primary-client additions: [StateChanged], [HistorySync],
 * [PresenceUpdate].
 */
sealed interface WhatsAppEvent {
    val source: MessageSource

    /** Transport/login lifecycle change. */
    data class StateChanged(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val state: WhatsAppState,
        val detail: String? = null,
    ) : WhatsAppEvent

    /** A conversation-list row changed: latest preview, unread count and peer metadata. */
    data class ConversationUpdate(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val peerName: String?,
        val peerPhone: String?,
        val avatarUrl: String?,
        val lastPreview: String?,
        val lastTimestamp: Long,
        val unreadCount: Int,
        val isGroup: Boolean = false,
        val participantCount: Int = 0,
        val conversationType: String? = null,
        val outgoingId: String? = null,
        val serviceData: String? = null,
        val isMessageRequest: Boolean = false,
    ) : WhatsAppEvent

    /** A message row changed (new, edited or status-updated) in a conversation. */
    data class MessageUpdate(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val messageId: String,
        val body: String,
        val outgoing: Boolean,
        val timestamp: Long,
        val senderName: String?,
        val senderId: String? = null,
        val reactionsJson: String? = null,
        val mediaData: ByteArray? = null,
        val mediaMime: String? = null,
        val mediaName: String? = null,
        val statusType: String? = null,
        val serviceData: String? = null,
        val attachments: List<MessageAttachment> = emptyList(),
    ) : WhatsAppEvent

    /** Inbound message (was GMEvent.IncomingMessage). */
    data class IncomingMessage(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val messageId: String,
        val body: String,
        val peerName: String?,
        val peerPhone: String?,
        val timestamp: Long,
        val senderName: String? = null,
        val senderId: String? = null,
        val attachments: List<MessageAttachment> = emptyList(),
        val serviceData: String? = null,
        val pollQuestion: String? = null,
        val pollOptions: List<String> = emptyList(),
    ) : WhatsAppEvent

    /** Message revoked/deleted (was GMEvent.MessageDeleted). */
    data class MessageDeleted(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val messageId: String,
        val conversationId: String? = null,
        val timestamp: Long = 0L,
    ) : WhatsAppEvent

    /** A message was edited; the row now shows the replacement body. */
    data class MessageEdited(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String? = null,
        val messageId: String,
        val newBody: String,
        val timestamp: Long = 0L,
    ) : WhatsAppEvent

    /** A delivery or read receipt for one of our sent messages. */
    data class ReadReceipt(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val messageId: String? = null,
        val senderId: String? = null,
        val timestampMs: Long = 0L,
        val timestamp: Long = 0L,
        val isDelivery: Boolean = false,
    ) : WhatsAppEvent

    /** A conversation was deleted. */
    data class ConversationDeleted(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
    ) : WhatsAppEvent

    /** Typing indicator (was GMEvent.TypingIndicator). */
    data class TypingIndicator(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val senderId: String,
        val isTyping: Boolean,
    ) : WhatsAppEvent

    /** An emoji reaction was added to a message. */
    data class ReactionReceived(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val messageId: String,
        val senderId: String,
        val emoji: String,
    ) : WhatsAppEvent

    /** An emoji reaction was removed from a message. */
    data class ReactionRemoved(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val messageId: String,
        val senderId: String,
    ) : WhatsAppEvent

    /** A vote was cast on a poll message. */
    data class PollVote(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val pollMessageId: String,
        val voterId: String,
        val optionNames: List<String>,
    ) : WhatsAppEvent

    /** A conversation (group) was renamed. */
    data class ConversationNameChanged(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val newName: String,
    ) : WhatsAppEvent

    /** A conversation avatar changed. */
    data class ConversationAvatarChanged(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val avatarUrl: String?,
    ) : WhatsAppEvent

    /** A participant joined a group conversation. */
    data class ParticipantAdded(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val participantId: String,
    ) : WhatsAppEvent

    /** A participant left or was removed from a group conversation. */
    data class ParticipantRemoved(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val participantId: String,
    ) : WhatsAppEvent

    /** A conversation mute setting changed; muteExpireTimeMs is when the mute lapses. */
    data class MuteSettingChanged(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val muteExpireTimeMs: Long,
    ) : WhatsAppEvent

    /** A message request arrived from an unknown sender. */
    data class MessageRequestReceived(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
    ) : WhatsAppEvent

    /** Sending a message failed. */
    data class SendFailed(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val messageId: String? = null,
        val tmpId: String? = null,
        val errorMessage: String,
    ) : WhatsAppEvent

    /** An inbound message could not be decrypted (session or identity problem). */
    data class DecryptionError(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val senderAci: String,
        val senderDeviceId: Int,
        val timestamp: Long,
        val errorMessage: String? = null,
    ) : WhatsAppEvent

    /** The primary line was logged out server-side (was GMEvent.SourceLoggedOut). */
    data class SourceLoggedOut(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val reason: String? = null,
    ) : WhatsAppEvent

    /** Presence/last-seen update for a peer. */
    data class PresenceUpdate(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversationId: String,
        val isOnline: Boolean,
        val lastSeen: Long = 0L,
    ) : WhatsAppEvent

    /** A batch of history from the server's initial/on-demand history sync. */
    data class HistorySync(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val conversations: List<WhatsAppHistoryConversation>,
    ) : WhatsAppEvent

    // ---- Calling (Phase D/E) ----

    /** An inbound call offer arrived (ringing). */
    data class CallOffer(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val callId: String,
        val from: String,
        val callCreator: String,
        val isVideo: Boolean,
        val peerName: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
    ) : WhatsAppEvent

    /** A call's lifecycle phase changed (dialing/ringing/active/…). [phase] is a WhatsAppCallPhase name. */
    data class CallStateChanged(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val callId: String,
        val phase: String,
        val isVideo: Boolean = false,
    ) : WhatsAppEvent

    /** A call ended. [reason] mirrors the terminate reason (or a local cause). */
    data class CallEnded(
        override val source: MessageSource = MessageSource.WHATSAPP,
        val callId: String,
        val reason: String,
        val durationSeconds: Long = 0L,
    ) : WhatsAppEvent
}

/** Transport/login lifecycle states for the primary client. */
enum class WhatsAppState {
    Disconnected,
    Connecting,
    Registering,
    AwaitingCode,
    Connected,
    Syncing,
    Ready,
}

/** Decoded media descriptor (keys + url) for on-demand download. */
data class WhatsAppMediaInfo(
    val url: String,
    val directPath: String?,
    val mediaKey: ByteArray,
    val mimeType: String?,
    val fileLength: Long,
    val fileSha256: ByteArray?,
    val fileEncSha256: ByteArray?,
    val mediaType: String,
)

/** One conversation's worth of history-sync payload. */
data class WhatsAppHistoryConversation(
    val conversationId: String,
    val name: String?,
    val isGroup: Boolean,
    val messages: List<WhatsAppHistoryMessage>,
)

/** One message inside a history-sync conversation payload. */
data class WhatsAppHistoryMessage(
    val messageId: String,
    val body: String,
    val timestamp: Long,
    val outgoing: Boolean,
    val senderId: String?,
    val senderName: String?,
    val serviceData: String? = null,
)
