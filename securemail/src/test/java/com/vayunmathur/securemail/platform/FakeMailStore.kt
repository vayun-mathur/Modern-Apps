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
import com.vayunmathur.securemail.data.StoredMessagePreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [MessageDao] recording every mutation for assertions. */
class FakeMessageDao : MessageDao {
    private val byId = linkedMapOf<String, StoredMessage>()
    private val allFlow = MutableStateFlow<List<StoredMessage>>(emptyList())
    val storeDecryptedCalls = mutableListOf<List<Any?>>()

    private fun publish() {
        allFlow.value = byId.values.sortedByDescending { it.dateMillis }
    }

    fun snapshot(): List<StoredMessage> = allFlow.value

    private fun StoredMessage.toPreview() = StoredMessagePreview(
        id = id, from = from, toJson = toJson, dateMillis = dateMillis, messageId = messageId,
        decryptedSubject = decryptedSubject, verifiedSigner = verifiedSigner,
        unencrypted = unencrypted, isRead = isRead, folder = folder, deliveredTo = deliveredTo,
        preview = decryptedBody?.take(200), hasDecryptedBody = decryptedBody != null,
    )

    override fun observeAll(): Flow<List<StoredMessagePreview>> =
        allFlow.map { list -> list.map { it.toPreview() } }

    override fun observeFolder(folder: MailFolder): Flow<List<StoredMessagePreview>> =
        allFlow.map { list -> list.filter { it.folder == folder }.map { it.toPreview() } }

    override fun observeFolderFull(folder: MailFolder): Flow<List<StoredMessage>> =
        allFlow.map { list -> list.filter { it.folder == folder } }

    override fun observeById(id: String): Flow<StoredMessage?> =
        allFlow.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun get(id: String): StoredMessage? = byId[id]

    override suspend fun upsert(msg: StoredMessage) {
        byId[msg.id] = msg
        publish()
    }

    override suspend fun markRead(id: String) {
        byId[id]?.let { byId[id] = it.copy(isRead = true) }
        publish()
    }

    override suspend fun storeDecrypted(id: String, body: String, subject: String, verified: String?, html: String?) {
        storeDecryptedCalls += listOf(id, body, subject, verified, html)
        byId[id]?.let {
            byId[id] = it.copy(decryptedBody = body, decryptedSubject = subject, verifiedSigner = verified, decryptedHtml = html)
        }
        publish()
    }

    override suspend fun emptyTrash() {
        byId.values.removeAll { it.folder == MailFolder.TRASH }
        publish()
    }

    override suspend fun delete(id: String) {
        byId.remove(id)
        publish()
    }

    override suspend fun setRead(ids: List<String>, read: Boolean) {
        ids.forEach { id -> byId[id]?.let { byId[id] = it.copy(isRead = read) } }
        publish()
    }

    override suspend fun moveTo(ids: List<String>, folder: MailFolder) {
        ids.forEach { id -> byId[id]?.let { byId[id] = it.copy(folder = folder) } }
        publish()
    }

    override suspend fun deleteAll(ids: List<String>) {
        ids.forEach { byId.remove(it) }
        publish()
    }
}

/** In-memory [KeyDao]. */
class FakeKeyDao : KeyDao {
    val byAddress = linkedMapOf<String, CachedKey>()
    override suspend fun get(address: String): CachedKey? = byAddress[address]
    override suspend fun upsert(key: CachedKey) {
        byAddress[key.address] = key
    }
}

/** In-memory [OutboxDao] with generated row ids and recorded attempts. */
class FakeOutboxDao : OutboxDao {
    private val entries = linkedMapOf<Long, OutboxMessage>()
    private var nextId = 1L
    private val countFlow = MutableStateFlow(0)
    private val allFlow = MutableStateFlow<List<OutboxMessage>>(emptyList())
    val noteAttemptCalls = mutableListOf<Pair<Long, String>>()

    private fun publish() {
        val sorted = entries.values.sortedBy { it.createdMillis }
        allFlow.value = sorted
        countFlow.value = sorted.size
    }

    override suspend fun all(): List<OutboxMessage> = entries.values.sortedBy { it.createdMillis }

    override fun observeAll(): Flow<List<OutboxMessage>> = allFlow

    override fun observeCount(): Flow<Int> = countFlow

    override suspend fun insert(entry: OutboxMessage): Long {
        val id = if (entry.rowId != 0L) entry.rowId else nextId++
        if (id >= nextId) nextId = id + 1
        entries[id] = entry.copy(rowId = id)
        publish()
        return id
    }

    override suspend fun delete(rowId: Long) {
        entries.remove(rowId)
        publish()
    }

    override suspend fun noteAttempt(rowId: Long, error: String) {
        noteAttemptCalls += rowId to error
        entries[rowId]?.let {
            entries[rowId] = it.copy(lastError = error, attemptCount = it.attemptCount + 1)
        }
        publish()
    }
}

/** In-memory [IdentityDao]; `setDisabled` never touches the primary, like the real query. */
class FakeIdentityDao : IdentityDao {
    private val byAddress = linkedMapOf<String, MailIdentity>()
    private val allFlow = MutableStateFlow<List<MailIdentity>>(emptyList())

    private fun publish() {
        allFlow.value = byAddress.values.sortedWith(
            compareByDescending<MailIdentity> { it.isPrimary }.thenBy { it.createdMillis },
        )
    }

    override fun observeAll(): Flow<List<MailIdentity>> = allFlow

    override suspend fun active(): List<MailIdentity> =
        byAddress.values.filter { !it.disabled }
            .sortedWith(compareByDescending<MailIdentity> { it.isPrimary }.thenBy { it.createdMillis })

    override suspend fun get(address: String): MailIdentity? = byAddress[address]

    override suspend fun primary(): MailIdentity? = byAddress.values.firstOrNull { it.isPrimary }

    override suspend fun count(): Int = byAddress.size

    override suspend fun upsert(identity: MailIdentity) {
        byAddress[identity.address] = identity
        publish()
    }

    override suspend fun setDisabled(address: String, disabled: Boolean) {
        byAddress[address]?.takeIf { !it.isPrimary }?.let {
            byAddress[address] = it.copy(disabled = disabled)
        }
        publish()
    }

    override suspend fun deleteAll() {
        byAddress.clear()
        publish()
    }
}

/** [MailStore] backed entirely by the fakes above; no Room/SQLCipher involved. */
class FakeMailStore : MailStore {
    val messages = FakeMessageDao()
    val keys = FakeKeyDao()
    val outbox = FakeOutboxDao()
    val identities = FakeIdentityDao()

    override fun messageDao(): MessageDao = messages
    override fun keyDao(): KeyDao = keys
    override fun outboxDao(): OutboxDao = outbox
    override fun identityDao(): IdentityDao = identities
}
