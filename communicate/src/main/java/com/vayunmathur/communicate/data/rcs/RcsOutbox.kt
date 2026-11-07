package com.vayunmathur.communicate.data.rcs

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.RcsSendResult
import com.vayunmathur.communicate.data.sendRcsMessage
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deferred-delivery outbox (§4.3, UP deferred messaging): failed sends park
 * here with backoff and retry until expiry (default 7 days), then surface
 * as failed. The sync service pumps [pumpDue] periodically; sends also
 * enqueue on transport failure instead of silently dropping.
 *
 * Kinds: `text` (body re-sent as-is), `file` (attachments CSV re-uploaded),
 * `group` (fan-out re-attempted). Pager-mode sends carry an `Expiry` header
 * (RCC.07 deferred delivery) so the network holds them for offline peers.
 */
object RcsOutbox {
    private const val TAG = "RcsOutbox"

    /** Default deferred-delivery window (UP: up to 7 days store-and-forward). */
    const val DEFAULT_EXPIRY_MS = 7L * 24 * 3600 * 1000

    private const val MAX_ATTEMPTS = 12
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3600_000L
    private const val BACKOFF_5_MIN = 5 * MINUTE_MS
    private const val BACKOFF_15_MIN = 15 * MINUTE_MS
    private const val BACKOFF_4_HOURS = 4 * HOUR_MS
    private const val BACKOFF_12_HOURS = 12 * HOUR_MS
    private const val BACKOFF_HOUR_ATTEMPT = 3
    private const val BACKOFF_4H_ATTEMPT = 4

    /** Backoff schedule: 1m, 5m, 15m, 1h, 4h, then 12h. */
    private fun backoffMs(attempts: Int): Long = when {
        attempts <= 0 -> MINUTE_MS
        attempts == 1 -> BACKOFF_5_MIN
        attempts == 2 -> BACKOFF_15_MIN
        attempts == BACKOFF_HOUR_ATTEMPT -> HOUR_MS
        attempts == BACKOFF_4H_ATTEMPT -> BACKOFF_4_HOURS
        else -> BACKOFF_12_HOURS
    }

    /**
     * Park a failed send for retry. Returns the outbox id, or null when the
     * gate is off / the payload is empty. Never throws.
     */
    suspend fun enqueue(
        context: Context,
        conversationId: String,
        body: String,
        attachments: List<CommunicateAttachment> = emptyList(),
        isGroup: Boolean = false,
        expiryMs: Long = System.currentTimeMillis() + DEFAULT_EXPIRY_MS,
    ): String? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext null
        if (body.isBlank() && attachments.isEmpty()) return@withContext null
        runCatching {
            val id = "outbox-${UUID.randomUUID()}"
            val kind = when {
                isGroup -> "group"
                attachments.isNotEmpty() -> "file"
                else -> "text"
            }
            RcsDatabase.getDatabase(context).outboxDao().upsert(
                RcsOutboxMessage(
                    messageId = id,
                    conversationId = conversationId,
                    kind = kind,
                    body = body,
                    attachments = attachments.joinToString(",") { it.contentUri },
                    attempts = 0,
                    nextRetryMs = System.currentTimeMillis() + backoffMs(0),
                    expiryMs = expiryMs,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            id
        }.getOrElse {
            Log.status(TAG, "Outbox enqueue failed", it)
            null
        }
    }

    /**
     * Retry due messages (called periodically by the sync service). Each due
     * row re-sends via the normal path; success deletes it, failure bumps
     * attempts + backoff, expiry deletes + marks failed. Returns (retried,
     * sent) counts. Never throws.
     */
    suspend fun pumpDue(
        context: Context,
        repository: CommunicateRepository,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext 0 to 0
        var retried = 0
        var sent = 0
        runCatching {
            val db = RcsDatabase.getDatabase(context)
            val now = System.currentTimeMillis()
            for (row in db.outboxDao().dueForRetry(now)) {
                retried++
                if (now >= row.expiryMs || row.attempts >= MAX_ATTEMPTS) {
                    db.outboxDao().delete(row.messageId)
                    markOutboxFailed(context, row)
                    continue
                }
                val result = runCatching {
                    val attachments = row.attachments.split(",")
                        .filter { it.isNotBlank() }
                        .map { uri ->
                            CommunicateAttachment(
                                contentUri = uri,
                                mimeType = "application/octet-stream",
                            )
                        }
                    repository.sendRcsMessage(
                        context = context,
                        address = row.conversationId,
                        body = row.body,
                        threadRemoteId = row.conversationId,
                        attachments = attachments,
                        participants = emptyList(),
                        fromOutbox = true,
                    )
                }.getOrDefault(RcsSendResult.Failed)
                if (result == RcsSendResult.Sent) {
                    db.outboxDao().delete(row.messageId)
                    sent++
                } else {
                    val attempts = row.attempts + 1
                    db.outboxDao().upsert(
                        row.copy(
                            attempts = attempts,
                            nextRetryMs = now + backoffMs(attempts),
                        ),
                    )
                }
            }
        }.onFailure { Log.status(TAG, "Outbox pump failed", it) }
        retried to sent
    }

    /** Pending outbox count (badge for the status screen). */
    suspend fun pendingCount(context: Context): Int = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext 0
        runCatching { RcsDatabase.getDatabase(context).outboxDao().count() }.getOrDefault(0)
    }

    private suspend fun markOutboxFailed(context: Context, row: RcsOutboxMessage) {
        runCatching {
            val db = RcsDatabase.getDatabase(context)
            db.cachedMessageDao().upsert(
                RcsCachedMessage(
                    messageId = row.messageId,
                    conversationId = row.conversationId,
                    body = row.body.ifBlank { "[expired]" },
                    timestamp = System.currentTimeMillis(),
                    outgoing = true,
                    status = 4,
                ),
            )
        }
    }
}
