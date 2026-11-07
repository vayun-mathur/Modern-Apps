package com.vayunmathur.communicate.data.rcs

import android.content.Context
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.cacheInboundRcs
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Outbound is-composing (RFC 3994, §4.1) + inbound display-report tracking
 * (§4.2).
 *
 * Typing: throttled active/idle sends over pager-mode MESSAGE (in-session
 * MSRP when a confirmed dialog exists). The sync service owns the throttle
 * clock; this object builds + sends.
 *
 * Display receipts: [markDisplayed] fires `display` IMDN for every inbound
 * row in [conversationId] carrying an [RcsCachedMessage.imdnId] that hasn't
 * been reported yet (tracked in-memory per process; the imdnId itself is
 * persisted on the row).
 */
object RcsTyping {
    /** How long after the last keystroke before an idle report goes out. */
    const val IDLE_AFTER_MS = 5_000L

    /** Minimum gap between active reports (avoid a MESSAGE per keystroke). */
    const val ACTIVE_THROTTLE_MS = 10_000L
    internal const val IDLE_SLACK_MS = 500L

    /**
     * Send an is-composing notification to [recipient]. Prefers the
     * confirmed dialog (§1.4), falls back to pager-mode. Returns true when
     * the leg accepted. Never throws.
     */
    suspend fun sendComposing(
        recipient: String,
        active: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || !RcsSipTransport.canSend() || recipient.isBlank()) {
            return@withContext false
        }
        runCatching {
            val body = buildIsComposingBody(active)
            // In-dialog first (sequenced, cheaper for the peer to correlate).
            val cpim = RcsSipTransport.buildCpimBody(body).toByteArray(Charsets.UTF_8)
            if (RcsSessionManager.sendInDialogMessage(recipient, cpim, "message/cpim")) {
                return@runCatching true
            }
            val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
                fromUri = "sip:me@rcs",
                toUri = "sip:$recipient@rcs",
                callId = "${UUID.randomUUID()}@rcs-typing",
                body = body,
            )
            val typingHeaders = headers.replace(
                "Content-Type: message/cpim",
                "Content-Type: application/im-iscomposing+xml",
            )
            RcsSipTransport.sendSipMessage(startLine, typingHeaders, content)
        }.getOrDefault(false)
    }
}

/**
 * Outbound is-composing throttle (§4.1): coalesces per-keystroke draft
 * changes into active (first keystroke + every 10s while typing) and idle
 * (5s after the last change) reports. Per-conversation clock; all state
 * in-memory. Never throws.
 */
object RcsTypingThrottle {
    private data class Clock(var lastActiveSentMs: Long = 0L, var idleDueMs: Long = 0L)

    private val clocks = java.util.concurrent.ConcurrentHashMap<String, Clock>()

    suspend fun onDraftChanged(recipient: String, draft: String) {
        if (!RcsFeature.enabled || recipient.isBlank()) return
        val now = System.currentTimeMillis()
        val clock = clocks.getOrPut(recipient) { Clock() }
        if (draft.isBlank()) {
            // Cleared: idle immediately (once).
            if (clock.idleDueMs != 0L) {
                clock.idleDueMs = 0L
                RcsTyping.sendComposing(recipient, false)
            }
            return
        }
        if (now - clock.lastActiveSentMs >= RcsTyping.ACTIVE_THROTTLE_MS) {
            clock.lastActiveSentMs = now
            RcsTyping.sendComposing(recipient, true)
        }
        clock.idleDueMs = now + RcsTyping.IDLE_AFTER_MS
        // Schedule the idle report (idempotent: only the latest due fires).
        val due = clock.idleDueMs
        CoroutineScope(Dispatchers.IO).launch {
            delay(RcsTyping.IDLE_AFTER_MS + RcsTyping.IDLE_SLACK_MS)
            val current = clocks[recipient] ?: return@launch
            if (current.idleDueMs == due && System.currentTimeMillis() >= due) {
                current.idleDueMs = 0L
                RcsTyping.sendComposing(recipient, false)
            }
        }
    }

    /** Drop the clock (conversation closed). */
    fun clear(recipient: String) {
        clocks.remove(recipient)
    }
}

/**
 * Fire `display` IMDN reports for unread inbound rows in [conversationId]
 * (§4.2). Called from `markRcsRead` — opening the thread means the messages
 * were displayed. Each row reports once per process (in-memory guard; the
 * row's [RcsCachedMessage.imdnId] is the referenced id). Never throws.
 */
suspend fun CommunicateRepository.sendRcsDisplayReports(
    context: Context,
    conversationId: String,
): Unit = withContext(Dispatchers.IO) {
    if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return@withContext
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        val rows = db.cachedMessageDao().getForConversation(conversationId)
            .filter { !it.outgoing && it.imdnId.isNotBlank() && it.messageId !in reportedDisplay }
        for (row in rows) {
            val report = buildImdnBody(row.imdnId, ImdnDisposition.Displayed)
            val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
                fromUri = "sip:me@rcs",
                toUri = "sip:$conversationId@rcs",
                callId = "${UUID.randomUUID()}@rcs-display",
                body = report,
            )
            if (RcsSipTransport.sendSipMessage(startLine, headers, content)) {
                reportedDisplay.add(row.messageId)
            }
        }
    }
}

private val reportedDisplay =
    java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

/**
 * Cache an inbound row carrying its sender `imdn.Message-ID` (§4.2), so
 * display reports can reference it later.
 */
internal suspend fun CommunicateRepository.cacheInboundRcsWithImdn(
    context: Context,
    conversationId: String,
    body: String,
    senderId: String = "",
    messageId: String = "in-${UUID.randomUUID()}",
    imdnId: String = "",
    ftUrl: String? = null,
    ftMime: String? = null,
) {
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        // Same redelivery guard as cacheInboundRcs: only a genuinely new
        // messageId may bump the unread count.
        val isNew = db.cachedMessageDao().get(messageId) == null
        db.cachedMessageDao().upsert(
            RcsCachedMessage(
                messageId = messageId,
                conversationId = conversationId,
                body = body,
                timestamp = now,
                outgoing = false,
                senderId = senderId,
                ftUrl = ftUrl,
                ftMime = ftMime,
                imdnId = imdnId,
            ),
        )
        val existing = db.conversationDao().getConversation(conversationId)
        db.conversationDao().upsert(
            (existing ?: RcsConversation(remoteId = conversationId)).copy(
                lastMessageTimestamp = now,
                unreadCount = (existing?.unreadCount ?: 0) + if (isNew) 1 else 0,
            ),
        )
    }
}
