package com.vayunmathur.email.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository

/**
 * Process-wide email database holder. Concern logic lives in the focused
 * stores ([EmailAccountStore], [EmailMessageStore], [EmailQueryStore],
 * [EmailOutboxStore]); this class only owns the singleton, exposes the
 * database/DAO accessors, and forwards the handful of calls made directly
 * against the repository.
 */
class EmailRepository private constructor(context: Context) :
    RoomRepository<EmailDatabase>(context, EmailDatabase::class, "email-db") {

    val accounts: EmailAccountStore = EmailAccountStore(db)
    val messages: EmailMessageStore = EmailMessageStore(db)
    val queries: EmailQueryStore = EmailQueryStore(db)
    val outbox: EmailOutboxStore = EmailOutboxStore(db)

    // Expose the underlying database for DAO-level access
    fun getDatabase(): EmailDatabase = db

    fun accountDao(): EmailAccountDao = db.accountDao()
    fun messageDao(): EmailMessageDao = db.messageDao()
    fun queryDao(): EmailQueryDao = db.queryDao()
    fun outboxDao(): EmailOutboxDao = db.outboxDao()

    // Directly-used forwards (kept for the handful of external call sites)
    suspend fun getAccounts(): List<EmailAccount> = accounts.getAccounts()
    suspend fun insertAccount(account: EmailAccount) = accounts.insertAccount(account)
    suspend fun getOutbox(): List<OutboxEntry> = outbox.getOutbox()
    suspend fun getOutboxCount(): Int = outbox.getOutboxCount()
    suspend fun deleteOutboxEntry(entry: OutboxEntry) = outbox.deleteOutboxEntry(entry)
    suspend fun updateOutboxAttempt(id: Long, error: String?, attempts: Int, at: Long) =
        outbox.updateOutboxAttempt(id, error, attempts, at)
    suspend fun updateOutboxMessageId(id: Long, messageId: String?) =
        outbox.updateOutboxMessageId(id, messageId)

    /** Provides the singleton EmailRepository instance. */
    companion object {
        @Volatile private var instance: EmailRepository? = null
        fun get(context: Context): EmailRepository =
            instance ?: synchronized(this) {
                instance ?: EmailRepository(context).also { instance = it }
            }
    }
}
