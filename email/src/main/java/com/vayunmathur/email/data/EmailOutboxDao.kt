package com.vayunmathur.email.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Outbox queue and drafts. */
@Dao
interface EmailOutboxDao {
    // ---- Outbox ----

    @Query("SELECT * FROM OutboxEntry ORDER BY createdAt ASC")
    fun getOutboxFlow(): Flow<List<OutboxEntry>>

    @Query("SELECT * FROM OutboxEntry ORDER BY createdAt ASC")
    suspend fun getOutbox(): List<OutboxEntry>

    @Query("SELECT COUNT(*) FROM OutboxEntry")
    suspend fun getOutboxCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutboxEntry(entry: OutboxEntry): Long

    @Delete
    suspend fun deleteOutboxEntry(entry: OutboxEntry)

    @Query(
        "UPDATE OutboxEntry SET lastError = :error, attemptCount = :attempts, " +
            "lastAttemptAt = :at WHERE id = :id",
    )
    suspend fun updateOutboxAttempt(id: Long, error: String?, attempts: Int, at: Long)

    @Query("UPDATE OutboxEntry SET messageId = :messageId WHERE id = :id")
    suspend fun updateOutboxMessageId(id: Long, messageId: String?)

    // ---- Drafts ----

    @Query("SELECT * FROM DraftEntry ORDER BY updatedAt DESC")
    fun getDraftsFlow(): Flow<List<DraftEntry>>

    @Query("SELECT * FROM DraftEntry WHERE id = :id")
    suspend fun getDraft(id: Long): DraftEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDraft(draft: DraftEntry): Long

    @Query("DELETE FROM DraftEntry WHERE id = :id")
    suspend fun deleteDraftById(id: Long)
}
