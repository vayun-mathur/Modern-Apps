package com.vayunmathur.email.data

import kotlinx.coroutines.flow.Flow

/** Accounts, blocked senders and folders — one concern of [EmailRepository]. */
class EmailAccountStore internal constructor(private val db: EmailDatabase) {
    private val dao: EmailAccountDao get() = db.accountDao()

    fun getAccountsFlow(): Flow<List<EmailAccount>> = dao.getAccountsFlow()
    suspend fun getAccounts(): List<EmailAccount> = dao.getAccounts()
    suspend fun getAccountByEmail(email: String): EmailAccount? = dao.getAccountByEmail(email)
    suspend fun insertAccount(account: EmailAccount) = dao.insertAccount(account)
    suspend fun setSignature(email: String, signature: String) = dao.setSignature(email, signature)
    suspend fun setAllowInsecureCertificates(email: String, allow: Boolean) =
        dao.setAllowInsecureCertificates(email, allow)
    suspend fun deleteAccount(account: EmailAccount) = dao.deleteAccount(account)

    fun getBlockedSendersFlow(): Flow<List<BlockedSender>> = dao.getBlockedSendersFlow()
    suspend fun insertBlockedSender(sender: BlockedSender) = dao.insertBlockedSender(sender)
    suspend fun deleteBlockedSender(address: String) = dao.deleteBlockedSender(address)

    fun getFoldersFlow(accountEmail: String): Flow<List<EmailFolder>> = dao.getFoldersFlow(accountEmail)
    suspend fun insertFolders(folders: List<EmailFolder>) = dao.insertFolders(folders)
    suspend fun clearFolders(accountEmail: String) = dao.clearFolders(accountEmail)
}
