package com.vayunmathur.email.data

import androidx.room.*
import com.vayunmathur.email.Attachment
import com.vayunmathur.email.EmailAccount
import com.vayunmathur.email.EmailFolder
import com.vayunmathur.email.EmailMessage
import com.vayunmathur.email.OutboxEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailDao {
    @Query("SELECT * FROM EmailAccount")
    fun getAccountsFlow(): Flow<List<EmailAccount>>

    @Query("SELECT * FROM EmailAccount")
    suspend fun getAccounts(): List<EmailAccount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: EmailAccount)

    @Delete
    suspend fun deleteAccount(account: EmailAccount)

    @Query("SELECT * FROM EmailFolder WHERE accountEmail = :accountEmail")
    fun getFoldersFlow(accountEmail: String): Flow<List<EmailFolder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<EmailFolder>)

    @Query("SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName ORDER BY dateMillis DESC, id DESC")
    fun getMessagesFlow(accountEmail: String, folderName: String): Flow<List<EmailMessage>>

    @Query("SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND threadId = :threadId ORDER BY dateMillis ASC, id ASC")
    fun getThreadFlow(accountEmail: String, threadId: String): Flow<List<EmailMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<EmailMessage>)

    @Query("SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND id = :uid AND folderName = :folderName")
    suspend fun getMessage(accountEmail: String, folderName: String, uid: Long): EmailMessage?

    /** UIDs already stored for a given folder — used to skip body re-fetch in sync. */
    @Query("SELECT id FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName")
    suspend fun getKnownUids(accountEmail: String, folderName: String): List<Long>

    /**
     * Messages we have headers for but no body yet. Used by the sync worker to
     * back-fill bodies in the background once the per-folder header sync is done.
     * Newest first because the user is most likely to open recent mail.
     */
    @Query("SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND body IS NULL ORDER BY id DESC LIMIT :limit")
    suspend fun getMessagesWithoutBody(accountEmail: String, limit: Int): List<EmailMessage>

    @Query("DELETE FROM EmailFolder WHERE accountEmail = :accountEmail")
    suspend fun clearFolders(accountEmail: String)

    @Query("DELETE FROM EmailMessage WHERE accountEmail = :accountEmail")
    suspend fun clearMessages(accountEmail: String)

    @Query("SELECT * FROM EmailMessage WHERE accountEmail = :accountEmail AND folderName = :folderName AND (subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%') ORDER BY dateMillis DESC, id DESC")
    fun searchMessagesFlow(accountEmail: String, folderName: String, query: String): Flow<List<EmailMessage>>

    // Unified Inbox
    @Query("SELECT * FROM EmailMessage WHERE folderName = :folderName ORDER BY dateMillis DESC, id DESC")
    fun getUnifiedMessagesFlow(folderName: String): Flow<List<EmailMessage>>

    @Query("SELECT * FROM EmailMessage WHERE folderName = :folderName AND (subject LIKE '%' || :query || '%' OR `from` LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%') ORDER BY dateMillis DESC, id DESC")
    fun searchUnifiedMessagesFlow(folderName: String, query: String): Flow<List<EmailMessage>>

    @Query("SELECT * FROM EmailMessage WHERE folderName = 'INBOX' ORDER BY dateMillis DESC, id DESC LIMIT 10")
    suspend fun getRecentUnifiedMessages(): List<EmailMessage>

    // ---- One-time backfill for the dateMillis column ----

    /** Rows persisted before `dateMillis` existed; their `date` string needs parsing. */
    @Query("SELECT * FROM EmailMessage WHERE dateMillis = 0 LIMIT 1000")
    suspend fun getRowsWithZeroDateMillis(): List<EmailMessage>

    @Query("UPDATE EmailMessage SET dateMillis = :millis WHERE accountEmail = :accountEmail AND folderName = :folderName AND id = :uid")
    suspend fun updateDateMillis(accountEmail: String, folderName: String, uid: Long, millis: Long)

    // Attachments
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<Attachment>)

    @Query("SELECT * FROM Attachment WHERE accountEmail = :accountEmail AND messageId = :uid")
    suspend fun getAttachments(accountEmail: String, uid: Long): List<Attachment>

    @Query("UPDATE Attachment SET localUri = :uri WHERE accountEmail = :accountEmail AND messageId = :uid AND partId = :partId")
    suspend fun updateAttachmentLocalUri(accountEmail: String, uid: Long, partId: String, uri: String)

    @Query("UPDATE EmailMessage SET isRead = :isRead WHERE accountEmail = :accountEmail AND id = :uid AND folderName = :folderName")
    suspend fun updateReadStatus(accountEmail: String, folderName: String, uid: Long, isRead: Boolean)

    @Query("UPDATE EmailMessage SET isRead = :isRead WHERE accountEmail = :accountEmail AND id IN (:uids)")
    suspend fun updateBulkReadStatus(accountEmail: String, uids: List<Long>, isRead: Boolean)

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

    @Query("UPDATE OutboxEntry SET lastError = :error, attemptCount = :attempts, lastAttemptAt = :at WHERE id = :id")
    suspend fun updateOutboxAttempt(id: Long, error: String?, attempts: Int, at: Long)
}
