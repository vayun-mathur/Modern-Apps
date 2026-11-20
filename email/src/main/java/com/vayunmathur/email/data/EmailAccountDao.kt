package com.vayunmathur.email.data

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Accounts, folders and blocked senders. */
@Dao
interface EmailAccountDao {
    @Query("SELECT * FROM EmailAccount")
    fun getAccountsFlow(): Flow<List<EmailAccount>>

    @Query("SELECT * FROM EmailAccount")
    suspend fun getAccounts(): List<EmailAccount>

    @Query("SELECT * FROM EmailAccount WHERE email = :email")
    suspend fun getAccountByEmail(email: String): EmailAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: EmailAccount)

    @Query("UPDATE EmailAccount SET signature = :signature WHERE email = :email")
    suspend fun setSignature(email: String, signature: String)

    @Query("UPDATE EmailAccount SET allowInsecureCertificates = :allow WHERE email = :email")
    suspend fun setAllowInsecureCertificates(email: String, allow: Boolean)

    // ---- Blocked senders ----

    @Query("SELECT * FROM BlockedSender ORDER BY address ASC")
    fun getBlockedSendersFlow(): Flow<List<BlockedSender>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlockedSender(sender: BlockedSender)

    @Query("DELETE FROM BlockedSender WHERE address = :address")
    suspend fun deleteBlockedSender(address: String)

    @Delete
    suspend fun deleteAccount(account: EmailAccount)

    @Query("SELECT * FROM EmailFolder WHERE accountEmail = :accountEmail")
    fun getFoldersFlow(accountEmail: String): Flow<List<EmailFolder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<EmailFolder>)

    @Query("DELETE FROM EmailFolder WHERE accountEmail = :accountEmail")
    suspend fun clearFolders(accountEmail: String)
}
