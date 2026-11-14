package com.vayunmathur.communicate.data.signal

/**
 * communicate-native event surface for the Signal primary client.
 * Mirrors [com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent] for Signal.
 */
sealed interface SignalEvent {
    val source: SignalSource

    /** Transport/login lifecycle change. */
    data class StateChanged(
        override val source: SignalSource = SignalSource.SIGNAL,
        val state: SignalState,
        val detail: String? = null,
    ) : SignalEvent

    /** A conversation-list row changed: latest preview, unread count and peer metadata. */
    data class ConversationUpdate(
        override val source: SignalSource = SignalSource.SIGNAL,
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
    ) : SignalEvent

    /** A message row changed (new, edited or status-updated) in a conversation. */
    data class MessageUpdate(
        override val source: SignalSource = SignalSource.SIGNAL,
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
        val attachments: List<SignalAttachment> = emptyList(),
    ) : SignalEvent

    /** Inbound message. */
    data class IncomingMessage(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val messageId: String,
        val body: String,
        val peerName: String?,
        val peerPhone: String?,
        val timestamp: Long,
        val senderName: String? = null,
        val senderId: String? = null,
        val attachments: List<SignalAttachment> = emptyList(),
        val serviceData: String? = null,
        val pollQuestion: String? = null,
        val pollOptions: List<String> = emptyList(),
    ) : SignalEvent

    /** Message revoked/deleted. */
    data class MessageDeleted(
        override val source: SignalSource = SignalSource.SIGNAL,
        val messageId: String,
        val conversationId: String? = null,
        val timestamp: Long = 0L,
    ) : SignalEvent

    /** A message was edited; the row now shows the replacement body. */
    data class MessageEdited(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String? = null,
        val messageId: String,
        val newBody: String,
        val timestamp: Long = 0L,
    ) : SignalEvent

    /** A delivery or read receipt for one of our sent messages. */
    data class ReadReceipt(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val messageId: String? = null,
        val senderId: String? = null,
        val timestampMs: Long = 0L,
        val timestamp: Long = 0L,
        val isDelivery: Boolean = false,
    ) : SignalEvent

    /** A conversation was deleted. */
    data class ConversationDeleted(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
    ) : SignalEvent

    /** Typing indicator. */
    data class TypingIndicator(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val senderId: String,
        val isTyping: Boolean,
    ) : SignalEvent

    /** An emoji reaction was added to a message. */
    data class ReactionReceived(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val messageId: String,
        val senderId: String,
        val emoji: String,
    ) : SignalEvent

    /** An emoji reaction was removed from a message. */
    data class ReactionRemoved(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val messageId: String,
        val senderId: String,
    ) : SignalEvent

    /** A vote was cast on a poll message. */
    data class PollVote(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val pollMessageId: String,
        val voterId: String,
        val optionNames: List<String>,
    ) : SignalEvent

    /** A conversation (group) was renamed. */
    data class ConversationNameChanged(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val newName: String,
    ) : SignalEvent

    /** A conversation avatar changed. */
    data class ConversationAvatarChanged(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val avatarUrl: String?,
    ) : SignalEvent

    /** A participant joined a group conversation. */
    data class ParticipantAdded(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val participantId: String,
    ) : SignalEvent

    /** A participant left or was removed from a group conversation. */
    data class ParticipantRemoved(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val participantId: String,
    ) : SignalEvent

    /** A conversation mute setting changed; muteExpireTimeMs is when the mute lapses. */
    data class MuteSettingChanged(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val muteExpireTimeMs: Long,
    ) : SignalEvent

    /** A message request arrived from an unknown sender. */
    data class MessageRequestReceived(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
    ) : SignalEvent

    /** Sending a message failed. */
    data class SendFailed(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val messageId: String? = null,
        val tmpId: String? = null,
        val errorMessage: String,
    ) : SignalEvent

    /** An inbound message could not be decrypted (session or identity problem). */
    data class DecryptionError(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val senderAci: String,
        val senderDeviceId: Int,
        val timestamp: Long,
        val errorMessage: String? = null,
    ) : SignalEvent

    /**
     * A peer's identity key no longer matches the one we recorded. Either they reinstalled or changed
     * devices, or someone is substituting keys — the two are indistinguishable without the user
     * comparing safety numbers out of band, so this must be surfaced rather than absorbed.
     */
    data class IdentityKeyChanged(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val peerAci: String,
        /** Hex of the identity key now being presented, for a safety-number comparison. */
        val newIdentityKeyHex: String,
        val timestamp: Long,
    ) : SignalEvent

    /** The primary line was logged out server-side. */
    data class SourceLoggedOut(
        override val source: SignalSource = SignalSource.SIGNAL,
        val reason: String? = null,
    ) : SignalEvent

    /** Presence/last-seen update for a peer. */
    data class PresenceUpdate(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversationId: String,
        val isOnline: Boolean,
        val lastSeen: Long = 0L,
    ) : SignalEvent

    /** A batch of history from the server's initial sync. */
    data class HistorySync(
        override val source: SignalSource = SignalSource.SIGNAL,
        val conversations: List<SignalHistoryConversation>,
    ) : SignalEvent

    // ---- Calling ----

    /** An inbound call offer arrived (ringing). */
    data class CallOffer(
        override val source: SignalSource = SignalSource.SIGNAL,
        val callId: String,
        val from: String,
        val callCreator: String,
        val isVideo: Boolean,
        val peerName: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
    ) : SignalEvent

    /** A call's lifecycle phase changed. [phase] is a SignalCallPhase name. */
    data class CallStateChanged(
        override val source: SignalSource = SignalSource.SIGNAL,
        val callId: String,
        val phase: String,
        val isVideo: Boolean = false,
    ) : SignalEvent

    /** A call ended. */
    data class CallEnded(
        override val source: SignalSource = SignalSource.SIGNAL,
        val callId: String,
        val reason: String,
        val durationSeconds: Long = 0L,
    ) : SignalEvent
}

/** Transport/login lifecycle states for the Signal primary client. */
enum class SignalState {
    Disconnected,
    Connecting,
    Registering,
    AwaitingCode,
    Connected,
    Syncing,
    Ready,
}

/** Which Signal line an event came from. */
enum class SignalSource { SIGNAL }

/** A media/share attachment carried on a Signal message. Mirrors WhatsApp's MessageAttachment. */
data class SignalAttachment(
    val url: String? = null,
    val previewUrl: String? = null,
    val mimeType: String? = null,
    /** image | video | audio | sticker | file | share. */
    val attachmentType: String = "file",
    val fileName: String? = null,
    val title: String? = null,
    val actionUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

/** One conversation's worth of history-sync payload. */
data class SignalHistoryConversation(
    val conversationId: String,
    val name: String?,
    val isGroup: Boolean,
    val messages: List<SignalHistoryMessage>,
)

/** One message inside a history-sync conversation payload. */
data class SignalHistoryMessage(
    val messageId: String,
    val body: String,
    val timestamp: Long,
    val outgoing: Boolean,
    val senderId: String?,
    val senderName: String?,
    val serviceData: String? = null,
)
