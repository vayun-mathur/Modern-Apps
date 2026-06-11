package com.vayunmathur.messages.gmessages

import com.vayunmathur.messages.data.MessageSource

/**
 * Events emitted by [GMessagesClient] for the rest of the app to react to.
 * Shape preserved from the previous WebView-puppet design so the
 * repository / Room-write path is unchanged.
 *
 * The [conversationId] / [messageId] strings are the WEB-APP-INTERNAL ids
 * — Google's per-thread numeric ids. The data layer prefixes them with
 * the source name before persisting; see [MessageSource.idPrefix].
 */
sealed interface GMEvent {
    val source: MessageSource

    /** A conversation row's metadata changed. Fired on initial scan and
     *  on any subsequent change the long-poll surfaces. */
    data class ConversationUpdate(
        override val source: MessageSource,
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
        /** Source-specific "my participant" id. See [Conversation.outgoingId]. */
        val outgoingId: String? = null,
    ) : GMEvent

    /** A message row appeared / was updated. Used for backfill + live sync. */
    data class MessageUpdate(
        override val source: MessageSource,
        val conversationId: String,
        val messageId: String,
        val body: String,
        val outgoing: Boolean,
        val timestamp: Long,
        val senderName: String?,
        val reactionsJson: String? = null,
    ) : GMEvent

    /** A NEW inbound message just arrived. Distinct from MessageUpdate
     *  because it should fire a notification; MessageUpdate is just sync. */
    data class IncomingMessage(
        override val source: MessageSource,
        val conversationId: String,
        val messageId: String,
        val body: String,
        val peerName: String?,
        val peerPhone: String?,
        val timestamp: Long,
    ) : GMEvent

    /** A message was deleted on the remote side (or by us). */
    data class MessageDeleted(
        override val source: MessageSource,
        val messageId: String,
        val conversationId: String? = null,
        val timestamp: Long = 0L,
    ) : GMEvent

    /** A message was edited on the remote side. */
    data class MessageEdited(
        override val source: MessageSource,
        val conversationId: String,
        val messageId: String,
        val newBody: String,
        val timestamp: Long,
    ) : GMEvent

    /** A read receipt was received. */
    data class ReadReceipt(
        override val source: MessageSource,
        val conversationId: String,
        val messageId: String,
        val timestamp: Long,
    ) : GMEvent

    /** A conversation was deleted on the remote side. */
    data class ConversationDeleted(
        override val source: MessageSource,
        val conversationId: String,
    ) : GMEvent

    /** A remote user started or stopped typing. */
    data class TypingIndicator(
        override val source: MessageSource,
        val conversationId: String,
        val senderId: String,
        val isTyping: Boolean,
    ) : GMEvent
}
