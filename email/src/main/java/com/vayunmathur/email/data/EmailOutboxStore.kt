package com.vayunmathur.email.data

import kotlinx.coroutines.flow.Flow

/** Outbox queue and drafts — one concern of [EmailRepository]. */
class EmailOutboxStore internal constructor(private val db: EmailDatabase) {
    private val dao: EmailOutboxDao get() = db.outboxDao()

    fun getOutboxFlow(): Flow<List<OutboxEntry>> = dao.getOutboxFlow()
    suspend fun getOutbox(): List<OutboxEntry> = dao.getOutbox()
    suspend fun getOutboxCount(): Int = dao.getOutboxCount()
    suspend fun insertOutboxEntry(entry: OutboxEntry): Long = dao.insertOutboxEntry(entry)
    suspend fun deleteOutboxEntry(entry: OutboxEntry) = dao.deleteOutboxEntry(entry)
    suspend fun updateOutboxAttempt(id: Long, error: String?, attempts: Int, at: Long) =
        dao.updateOutboxAttempt(id, error, attempts, at)
    suspend fun updateOutboxMessageId(id: Long, messageId: String?) =
        dao.updateOutboxMessageId(id, messageId)

    fun getDraftsFlow(): Flow<List<DraftEntry>> = dao.getDraftsFlow()
    suspend fun getDraft(id: Long): DraftEntry? = dao.getDraft(id)
    suspend fun insertDraft(draft: DraftEntry): Long = dao.insertDraft(draft)
    suspend fun deleteDraftById(id: Long) = dao.deleteDraftById(id)
}
