package com.vayunmathur.securemail.platform

import com.vayunmathur.securemail.data.CachedKey
import com.vayunmathur.securemail.data.IdentityDao
import com.vayunmathur.securemail.data.KeyDao
import com.vayunmathur.securemail.data.MailFolder
import com.vayunmathur.securemail.data.MailIdentity
import com.vayunmathur.securemail.data.MailStore
import com.vayunmathur.securemail.data.MessageDao
import com.vayunmathur.securemail.data.OutboxDao
import com.vayunmathur.securemail.data.OutboxMessage
import com.vayunmathur.securemail.data.StoredMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [MailStore] for ViewModel/controller tests (no SQLCipher). */
class FakeMailStore(
    messages: List<StoredMessage> = emptyList(),
    identities: List<MailIdentity> = emptyList(),
) : MailStore {

    val messageList = MutableStateFlow(messages)
    val keyList = MutableStateFlow<List<CachedKey>>(emptyList())
    val outboxList = MutableStateFlow<List<OutboxMessage>>(emptyList())
    val identityList = MutableStateFlow(identities)
    private var nextRowId = 1L

    private val messageDao = object : MessageDao {
        override fun observeAll(): Flow<List<StoredMessage>> =
            messageList.map { list -> list.sortedByDescending { it.dateMillis } }
        override fun observeFolder(folder: MailFolder): Flow<List<StoredMessage>> =
            messageList.map { list -> list.filter { it.folder == folder }.sortedByDescending { it.dateMillis } }
        override suspend fun get(id: String): StoredMessage? = messageList.value.firstOrNull { it.id == id }
        override suspend fun upsert(msg: StoredMessage) {
            messageList.value = messageList.value.filterNot { it.id == msg.id } + msg
        }
        override suspend fun markRead(id: String) {
            messageList.value = messageList.value.map { if (it.id == id) it.copy(isRead = true) else it }
        }
        override suspend fun storeDecrypted(id: String, body: String, subject: String, verified: String?, html: String?) {
            messageList.value = messageList.value.map {
                if (it.id == id) it.copy(decryptedBody = body, decryptedSubject = subject, verifiedSigner = verified, decryptedHtml = html) else it
            }
        }
        override suspend fun emptyTrash() {
            messageList.value = messageList.value.filterNot { it.folder == MailFolder.TRASH }
        }
        override suspend fun delete(id: String) {
            messageList.value = messageList.value.filterNot { it.id == id }
        }
        override suspend fun setRead(ids: List<String>, read: Boolean) {
            messageList.value = messageList.value.map { if (it.id in ids) it.copy(isRead = read) else it }
        }
        override suspend fun moveTo(ids: List<String>, folder: MailFolder) {
            messageList.value = messageList.value.map { if (it.id in ids) it.copy(folder = folder) else it }
        }
        override suspend fun deleteAll(ids: List<String>) {
            messageList.value = messageList.value.filterNot { it.id in ids }
        }
    }

    private val keyDao = object : KeyDao {
        override suspend fun get(address: String): CachedKey? = keyList.value.firstOrNull { it.address == address }
        override suspend fun upsert(key: CachedKey) {
            keyList.value = keyList.value.filterNot { it.address == key.address } + key
        }
    }

    private val outboxDao = object : OutboxDao {
        override suspend fun all(): List<OutboxMessage> = outboxList.value.sortedBy { it.createdMillis }
        override fun observeAll(): Flow<List<OutboxMessage>> = outboxList
        override fun observeCount(): Flow<Int> = outboxList.map { it.size }
        override suspend fun insert(entry: OutboxMessage): Long {
            val row = entry.copy(rowId = nextRowId++)
            outboxList.value = outboxList.value + row
            return row.rowId
        }
        override suspend fun delete(rowId: Long) {
            outboxList.value = outboxList.value.filterNot { it.rowId == rowId }
        }
        override suspend fun noteAttempt(rowId: Long, error: String) {
            outboxList.value = outboxList.value.map {
                if (it.rowId == rowId) it.copy(lastError = error, attemptCount = it.attemptCount + 1) else it
            }
        }
    }

    private val identityDao = object : IdentityDao {
        override fun observeAll(): Flow<List<MailIdentity>> = identityList
        override suspend fun active(): List<MailIdentity> = identityList.value.filter { !it.disabled }
        override suspend fun get(address: String): MailIdentity? = identityList.value.firstOrNull { it.address == address }
        override suspend fun primary(): MailIdentity? = identityList.value.firstOrNull { it.isPrimary }
        override suspend fun count(): Int = identityList.value.size
        override suspend fun upsert(identity: MailIdentity) {
            identityList.value = identityList.value.filterNot { it.address == identity.address } + identity
        }
        override suspend fun setDisabled(address: String, disabled: Boolean) {
            identityList.value = identityList.value.map {
                if (it.address == address && !it.isPrimary) it.copy(disabled = disabled) else it
            }
        }
        override suspend fun deleteAll() { identityList.value = emptyList() }
    }

    override fun messageDao(): MessageDao = messageDao
    override fun keyDao(): KeyDao = keyDao
    override fun outboxDao(): OutboxDao = outboxDao
    override fun identityDao(): IdentityDao = identityDao
}
