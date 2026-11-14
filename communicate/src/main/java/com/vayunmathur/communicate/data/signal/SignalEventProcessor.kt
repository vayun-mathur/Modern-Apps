package com.vayunmathur.communicate.data.signal

import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Drains [SignalClient.events] into Room ([SignalDatabase]) so the inbox survives restarts.
 * Mirrors [com.vayunmathur.communicate.data.whatsapp.WhatsAppEventProcessor].
 *
 * Grounded in share/SIGNAL_VERIFICATION.md P1 Receipts/typing/edits/reactions:
 * - SignalService.proto Content oneof: dataMessage / receiptMessage / typingMessage / editMessage / callMessage /
 * syncMessage
 * - ReceiptMessage{Type DELIVERY/READ/VIEWED; repeated uint64 timestamp} -> markDelivered/markReadStatus per timestamp
 * (not messageId)
 * - TypingMessage{STARTED/STOPPED, timestamp, groupId 32B} -> typing ephemeral (processor drains to no-op; UI
 * subscribes to events)
 * - EditMessage{targetSentTimestamp, dataMessage} -> lookup original by targetSentTimestamp timestamp -> markEdited +
 * serviceData isEdited
 * - DataMessage.delete{targetSentTimestamp} / reaction{emoji,remove,targetAuthorAciBinary,targetSentTimestamp} /
 * pollCreate/pollVote/pollTerminate
 * - CallMessage, SyncMessage.read[] / viewed[] (multi-device read sync)
 *
 * Handles: IncomingMessage, MessageUpdate, MessageDeleted/Edited, reactions, polls/poll votes,
 * ConversationUpdate/Deleted, ReadReceipt (delivery/read batches), HistorySync, CallEnded, typing.
 */
class SignalEventProcessor(private val db: SignalDatabase) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val messages = db.cachedMessageDao()
    private val reactions = db.cachedReactionDao()
    private val conversations = db.conversationDao()
    private val callLog = db.callLogDao()

    fun start(events: SharedFlow<SignalEvent>) {
        scope.launch {
            events.collect { event ->
                try {
                    handle(event)
                } catch (expected: Throwable) {
                    Log.error(TAG, "failed to process ${event::class.simpleName}", expected)
                }
            }
        }
    }

    fun stop() { scope.cancel() }

    private suspend fun handle(event: SignalEvent) {
        when (event) {
            is SignalEvent.IncomingMessage -> handleIncomingMessage(event)
            is SignalEvent.MessageUpdate -> handleMessageUpdate(event)
            is SignalEvent.MessageEdited -> handleMessageEdited(event)
            is SignalEvent.MessageDeleted -> handleMessageDeleted(event)
            is SignalEvent.ReactionReceived -> handleReactionReceived(event)
            is SignalEvent.ReactionRemoved -> handleReactionRemoved(event)
            is SignalEvent.PollVote -> handlePollVote(event)
            is SignalEvent.ConversationUpdate -> handleConversationUpdate(event)
            is SignalEvent.ConversationDeleted -> handleConversationDeleted(event)
            is SignalEvent.ReadReceipt -> handleReadReceipt(event)
            is SignalEvent.HistorySync -> handleHistorySync(event)
            else -> handleCallEvent(event)
        }
    }

    /** Call + typing + state events. */
    private suspend fun handleCallEvent(event: SignalEvent) {
        when (event) {
            is SignalEvent.CallEnded -> handleCallEnded(event)
            is SignalEvent.TypingIndicator -> handleTypingIndicator()
            else -> {/* StateChanged, PresenceUpdate, CallOffer/State, PollVote already handled, etc. */ }
        }
    }
    private suspend fun handleIncomingMessage(event: SignalEvent.IncomingMessage) {
        val cid = conversationId(event.conversationId)
        val isNew = messages.get(event.messageId) == null
        val sd = SignalServiceData(
            senderName = event.senderName,
            senderId = event.senderId,
            pollQuestion = event.pollQuestion,
            pollOptions = event.pollOptions.map { SignalPollOptionData(it) },
            mediaUrl = event.attachments.firstOrNull()?.url,
            mediaMime = event.attachments.firstOrNull()?.mimeType,
            mediaName = event.attachments.firstOrNull()?.fileName,
        )
        messages.upsert(
            SignalCachedMessage(
                messageId = event.messageId,
                conversationId = cid,
                body = event.body,
                timestamp = event.timestamp,
                outgoing = false,
                senderId = event.senderId ?: "",
                serviceData = event.serviceData ?: sd.serialize(),
            ),
        )
        touchConversation(cid, event.timestamp, incrementUnread = isNew)
    }

    private suspend fun handleMessageUpdate(event: SignalEvent.MessageUpdate) {
        messages.upsert(
            SignalCachedMessage(
                messageId = event.messageId,
                conversationId = conversationId(event.conversationId),
                body = event.body,
                timestamp = event.timestamp,
                outgoing = event.outgoing,
                senderId = event.senderId ?: "",
                serviceData = event.serviceData,
            ),
        )
        touchConversation(conversationId(event.conversationId), event.timestamp, incrementUnread = false)
    }

    private suspend fun handleMessageEdited(event: SignalEvent.MessageEdited) {
        // Real edit key is targetSentTimestamp (uint64), not messageId string; SignalClient resolves
        // targetSentTimestamp -> messageId and emits MessageEdited with that messageId. Processor persists.
        messages.markEdited(event.messageId, event.newBody)
        mergeServiceData(event.messageId) { it.copy(isEdited = true) }
    }

    private suspend fun handleMessageDeleted(event: SignalEvent.MessageDeleted) {
        messages.markRevoked(event.messageId)
        mergeServiceData(event.messageId) { it.copy(isRevoked = true) }
    }

    private suspend fun handleReactionReceived(event: SignalEvent.ReactionReceived) {
        // Reaction wire: DataMessage.reaction{emoji,remove,targetAuthorAciBinary,targetSentTimestamp} inside
        // Content.dataMessage
        reactions.upsert(
            SignalCachedReaction(event.messageId, event.emoji, event.senderId, System.currentTimeMillis()),
        )
        refreshReactions(event.messageId)
    }

    private suspend fun handleReactionRemoved(event: SignalEvent.ReactionRemoved) {
        reactions.remove(event.messageId, event.senderId)
        refreshReactions(event.messageId)
    }

    private suspend fun handlePollVote(event: SignalEvent.PollVote) {
        // Poll wire: DataMessage.pollVote{targetSentTimestamp, optionIndexes[], voteCount} inside
        // Content.dataMessage
        mergeServiceData(event.pollMessageId) { sd ->
            val updated = sd.pollOptions.map { opt ->
                if (opt.name in event.optionNames && event.voterId !in opt.voters) {
                    opt.copy(voteCount = opt.voteCount + 1, voters = opt.voters + event.voterId)
                } else opt
            }
            sd.copy(pollOptions = updated)
        }
    }

    private suspend fun handleConversationUpdate(event: SignalEvent.ConversationUpdate) {
        val cid = conversationId(event.conversationId)
        touchConversation(cid, event.lastTimestamp, incrementUnread = false)
        val isGroup = event.isGroup || cid.startsWith("group:")
        if (isGroup || !event.peerName.isNullOrBlank()) {
            val existing = conversations.getConversation(cid) ?: SignalConversation(chatId = cid)
            conversations.upsert(
                existing.copy(
                    isGroup = isGroup || existing.isGroup,
                    name = event.peerName?.takeIf { it.isNotBlank() } ?: existing.name,
                ),
            )
        }
    }

    private suspend fun handleConversationDeleted(event: SignalEvent.ConversationDeleted) {
        messages.deleteConversation(conversationId(event.conversationId))
        conversations.delete(conversationId(event.conversationId))
    }

    private suspend fun handleReadReceipt(event: SignalEvent.ReadReceipt) {
        // ReceiptMessage handling: ReceiptMessage.timestamp is repeated uint64 of DataMessage timestamps being
        // acked.
        // SignalClient emits one ReadReceipt per timestamp (delivery/read); processor advances outgoing cached
        // message status to 2/3.
        event.messageId?.let { id ->
            if (event.isDelivery) messages.markDelivered(id) else messages.markReadStatus(id)
        }
        // If messageId is numeric timestamp fallback (stringified timestamp), also try timestamp match
        if (event.messageId == null || event.messageId.matches(Regex("\\d+"))) {
            // No-op: timestamp-based lookup already emitted per-resolved-id above.
        }
    }

    private suspend fun handleHistorySync(event: SignalEvent.HistorySync) {
        val rows = ArrayList<SignalCachedMessage>()
        for (conv in event.conversations) {
            for (m in conv.messages) {
                rows.add(
                    SignalCachedMessage(
                        messageId = m.messageId,
                        conversationId = conversationId(conv.conversationId),
                        body = m.body,
                        timestamp = m.timestamp,
                        outgoing = m.outgoing,
                        senderId = m.senderId ?: "",
                        serviceData = m.serviceData,
                    ),
                )
            }
            val newest = conv.messages.maxOfOrNull { it.timestamp } ?: 0L
            touchConversation(conversationId(conv.conversationId), newest, incrementUnread = false)
        }
        if (rows.isNotEmpty()) messages.upsertAll(rows)
    }

    private suspend fun handleCallEnded(event: SignalEvent.CallEnded) {
        runCatching {
            callLog.upsert(
                SignalCallLog(
                    callId = event.callId,
                    durationSeconds = event.durationSeconds,
                    outcome = event.reason,
                ),
            )
        }
    }

    private fun handleTypingIndicator() {
        // TypingMessage: ephemeral STARTED/STOPPED with optional groupId 32B; not persisted, but touch
        // conversation so thread list refreshes if needed.
        // Processor keeps this as no-op for DB; SignalClient already emitted TypingIndicator for UI.
    }

    private fun conversationId(raw: String): String = raw.removePrefix("signal:")

    private suspend fun touchConversation(cid: String, timestamp: Long, incrementUnread: Boolean) {
        val existing = conversations.getConversation(cid)
        val unread = (existing?.unreadCount ?: 0) + if (incrementUnread) 1 else 0
        conversations.upsert(
            (existing ?: SignalConversation(chatId = cid)).copy(
                lastMessageTimestamp = maxOf(existing?.lastMessageTimestamp ?: 0L, timestamp),
                unreadCount = unread,
            ),
        )
    }

    private suspend fun refreshReactions(messageId: String) {
        val rows = reactions.getForMessage(messageId)
        val summary = rows.groupingBy { it.emoji }.eachCount().map { SignalReaction(it.key, it.value) }
        mergeServiceData(messageId) { it.copy(reactions = summary) }
    }

    private suspend fun mergeServiceData(messageId: String, transform: (SignalServiceData) -> SignalServiceData) {
        val msg = messages.get(messageId) ?: return
        val current = SignalServiceData.parse(msg.serviceData) ?: SignalServiceData()
        messages.updateServiceData(messageId, transform(current).serialize())
    }

    /** Logging tag for [SignalEventProcessor]. */
    companion object { private const val TAG = "SignalEventProcessor" }
}
