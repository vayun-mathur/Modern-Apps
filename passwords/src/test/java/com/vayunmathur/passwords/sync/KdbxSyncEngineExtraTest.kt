package com.vayunmathur.passwords.sync

import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordDao
import com.vayunmathur.passwords.data.SyncSnapshot
import com.vayunmathur.passwords.data.SyncSnapshotDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PW = "vault-pw"

class KdbxSyncEngineExtraTest {

    @Test fun emptyRemoteFileIsTreatedAsEmptyVaultAndPushed() = runBlocking {
        val h = Harness(passwords = listOf(pw(1, "A", updatedAt = 100)))
        h.document.bytes = ByteArray(0)
        val result = h.engine.sync(h.document, PW)
        assertEquals(KdbxSyncResult.Success(1, 0, 0, 0), result)
        assertEquals(1, h.document.writes)
        assertEquals("A", h.remoteEntries().single()["Title"])
    }

    @Test fun encodeFailureReturnsErrorWithoutWritingOrMutating() = runBlocking {
        val h = Harness(passwords = listOf(pw(1, "A", updatedAt = 100)))
        h.codec.failWrite = true
        val result = h.engine.sync(h.document, PW)
        assertEquals(KdbxSyncResult.Error("Failed to encode KDBX"), result)
        assertEquals(0, h.document.writes)
        assertEquals(1, h.pwDao.store.size)
        assertTrue(h.snapshotDao.store.isEmpty())
    }

    @Test fun verifyMismatchReturnsVerifyFailedWithoutWriting() = runBlocking {
        val h = Harness(passwords = listOf(pw(1, "A", updatedAt = 100)))
        h.codec.corruptVerify = true
        val result = h.engine.sync(h.document, PW)
        assertEquals(KdbxSyncResult.VerifyFailed, result)
        assertEquals(0, h.document.writes)
        assertTrue(h.snapshotDao.store.isEmpty())
    }

    @Test fun documentWriteIOExceptionReturnsError() = runBlocking {
        val h = Harness(passwords = listOf(pw(1, "A", updatedAt = 100)))
        h.document.writeFailsWith = IOException("disk full")
        val result = h.engine.sync(h.document, PW)
        assertEquals(KdbxSyncResult.Error("disk full"), result)
        assertTrue(h.snapshotDao.store.isEmpty())
    }

    @Test fun documentReadIOExceptionReturnsErrorWithMessage() = runBlocking {
        val h = Harness(passwords = listOf(pw(1, "A", updatedAt = 100)))
        h.document.readFailsWith = IOException("cloud unavailable")
        val result = h.engine.sync(h.document, PW)
        assertEquals(KdbxSyncResult.Error("cloud unavailable"), result)
        assertEquals(1, h.pwDao.store.size)
    }

    @Test fun backupCallbackReceivesPreviousBytesOnlyWhenRewriting() = runBlocking {
        val h = Harness(passwords = listOf(pw(1, "A", updatedAt = 100)))
        h.sync()
        val previous = assertNotNull(h.document.bytes)
        var backedUp: ByteArray? = null
        h.pwDao.store[0] = h.pwDao.store[0].copy(password = "changed", updatedAt = 5000)
        val result = h.engine.sync(h.document, PW, onBackup = { backedUp = it })
        assertTrue(result is KdbxSyncResult.Success)
        assertEquals(previous.toList(), backedUp!!.toList())
    }

    @Test fun blankSyncIdLocalRowGetsFreshIdentityAndIsPersisted() = runBlocking {
        val blank = Password(id = 5, name = "Legacy", username = "u", password = "p", syncId = "", updatedAt = 42)
        val h = Harness(passwords = listOf(blank))
        val result = h.engine.sync(h.document, PW)
        assertEquals(KdbxSyncResult.Success(1, 0, 0, 0), result)
        val stored = h.pwDao.store.single()
        assertTrue(stored.syncId.isNotBlank())
        assertEquals(32, stored.syncId.length)
        assertEquals(stored.syncId, h.remoteEntries().single()[EntryMapper.FIELD_SYNC_ID])
        assertEquals(stored.syncId, h.snapshotDao.store.keys.single())
    }

    @Test fun duplicateRemoteSyncIdsAreNotDropped() = runBlocking {
        val entry = EntryMapper.toFields(pw(0, "First", syncId = "dup", updatedAt = 100))
        val dupe = entry + ("Password" to "second-copy")
        val h = Harness(remote = listOf(entry, dupe))
        val result = h.engine.sync(h.document, PW)
        assertTrue(result is KdbxSyncResult.Success, "got $result")
        // Both rows survive locally: the duplicate is adopted under a fresh identity.
        assertEquals(2, h.pwDao.store.size)
        assertEquals(setOf("First"), h.pwDao.store.map { it.name }.toSet())
        assertEquals(2, h.pwDao.store.map { it.syncId }.distinct().size)
    }

    @Test fun foreignExtraFieldsSurviveALocalWin() = runBlocking {
        val h = Harness(passwords = listOf(pw(1, "A", updatedAt = 100)))
        h.sync()
        h.editRemote { it + mapOf("Tags" to "work,important", "Custom" to "keep-me") }
        h.pwDao.store[0] = h.pwDao.store[0].copy(password = "newer", updatedAt = 9000)
        val result = h.engine.sync(h.document, PW)
        assertEquals(1, (result as KdbxSyncResult.Success).pushed)
        val remote = h.remoteEntries().single()
        assertEquals("newer", remote["Password"])
        assertEquals("work,important", remote["Tags"])
        assertEquals("keep-me", remote["Custom"])
    }

    @Test fun passkeyPulledFromRemoteAndDeletedByTombstone() = runBlocking {
        val pk = Passkey(
            id = 0, rpId = "example.com", rpName = "Example", credentialId = "cred-x",
            userId = "uid", userName = "alice", privateKeyBytes = byteArrayOf(9, 8),
            creationTime = 11, lastUsedTime = 22, signCount = 3, syncId = "pk-r", updatedAt = 700,
        )
        val h = Harness(remote = listOf(EntryMapper.toFields(pk)))
        assertEquals(KdbxSyncResult.Success(0, 1, 0, 0), h.engine.sync(h.document, PW))
        val stored = h.pkDao.store.single()
        assertEquals("cred-x", stored.credentialId)
        assertEquals(listOf(9.toByte(), 8.toByte()), stored.privateKeyBytes.toList())
        assertEquals(700L, stored.updatedAt)

        h.setRemote(
            listOf(
                mapOf(
                    EntryMapper.FIELD_SYNC_ID to "pk-r",
                    EntryMapper.FIELD_TYPE to "tombstone",
                    EntryMapper.FIELD_TOMBSTONE to EntryMapper.TOMBSTONE_VALUE,
                    EntryMapper.FIELD_MODIFIED to "800",
                ),
            ),
        )
        val deleted = h.engine.sync(h.document, PW)
        assertEquals(1, (deleted as KdbxSyncResult.Success).deletedLocal)
        assertTrue(h.pkDao.store.isEmpty())
    }

    @Test fun localOnlyPasskeyIsPushedWithAllKpexFields() = runBlocking {
        val pk = Passkey(
            id = 3, rpId = "acme.example", rpName = "Acme", credentialId = "c1",
            userId = "u1", userName = "bob", userDisplayName = "Bob B",
            privateKeyBytes = byteArrayOf(1), creationTime = 5, lastUsedTime = 6,
            signCount = 7, linkedPasswordSyncId = "sync-1", syncId = "pk-l", updatedAt = 123,
        )
        val h = Harness(passkeys = listOf(pk))
        assertEquals(KdbxSyncResult.Success(1, 0, 0, 0), h.engine.sync(h.document, PW))
        val remote = h.remoteEntries().single()
        assertEquals("c1", remote["KPEX_PASSKEY_CREDENTIAL_ID"])
        assertEquals("acme.example", remote["KPEX_PASSKEY_RELYING_PARTY"])
        assertEquals("7", remote["_SignCount"])
        assertEquals("sync-1", remote["_LinkedPassword"])
    }

    // -- harness (mirrors KdbxSyncEngineTest's fakes, plus failure knobs) --

    private fun pw(id: Long, name: String, syncId: String = "sync-$id", updatedAt: Long) =
        Password(id = id, name = name, username = "u-$name", password = "p", syncId = syncId, updatedAt = updatedAt)

    private class Harness(
        passwords: List<Password> = emptyList(),
        passkeys: List<Passkey> = emptyList(),
        remote: List<Map<String, String>> = emptyList(),
    ) {
        val codec = FakeCodec()
        val pwDao = FakePwDao(passwords)
        val pkDao = FakePkDao(passkeys)
        val snapshotDao = FakeSnapshotDao()
        val document = FakeDocument(codec.write(PW, remote))
        val engine get() = KdbxSyncEngine(pwDao, pkDao, snapshotDao, codec)
        suspend fun sync() = engine.sync(document, PW)
        fun remoteEntries(): List<Map<String, String>> = codec.read(PW, assertNotNull(document.bytes))!!
        fun setRemote(entries: List<Map<String, String>>) { document.bytes = codec.write(PW, entries) }
        fun editRemote(transform: (Map<String, String>) -> Map<String, String>) =
            setRemote(remoteEntries().map(transform))
    }

    private class FakeCodec : KdbxCodec {
        private val vaults = mutableMapOf<String, List<Map<String, String>>>()
        private var counter = 0
        var failWrite = false
        var corruptVerify = false
        override fun read(password: String, bytes: ByteArray): List<Map<String, String>>? {
            if (password != PW) return null
            val found = vaults[String(bytes)] ?: return null
            return if (corruptVerify) found + mapOf("Title" to "phantom") else found
        }
        override fun write(password: String, entries: List<Map<String, String>>): ByteArray? {
            if (failWrite) return null
            val handle = "vault-${counter++}"
            vaults[handle] = entries.map { it.toMap() }
            return handle.toByteArray()
        }
    }

    private class FakeDocument(var bytes: ByteArray?) : KdbxDocument {
        var writes = 0
        var readFailsWith: IOException? = null
        var writeFailsWith: IOException? = null
        override fun read(): ByteArray {
            readFailsWith?.let { throw it }
            return bytes ?: throw FileNotFoundException("no document")
        }
        override fun write(bytes: ByteArray) {
            writeFailsWith?.let { throw it }
            this.bytes = bytes
            writes++
        }
    }

    private class FakePwDao(initial: List<Password>) : PasswordDao() {
        val store = initial.toMutableList()
        private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
        override fun getAllFlow(): Flow<List<Password>> = flowOf(store.toList())
        override suspend fun getAll(): List<Password> = store.toList()
        override fun getByIdFlow(id: Long): Flow<Password?> = flowOf(store.firstOrNull { it.id == id })
        override suspend fun getById(id: Long): Password? = store.firstOrNull { it.id == id }
        override suspend fun getBySyncId(syncId: String): Password? = store.firstOrNull { it.syncId == syncId }
        override suspend fun getUpdatedSince(since: Long): List<Password> = store.filter { it.updatedAt > since }
        override suspend fun upsertRaw(value: Password): Long {
            val row = if (value.id == 0L) value.copy(id = nextId++) else value
            val i = store.indexOfFirst { it.id == row.id }
            if (i >= 0) store[i] = row else store.add(row)
            return row.id
        }
        override suspend fun delete(value: Password): Int = if (store.removeAll { it.id == value.id }) 1 else 0
        override suspend fun deleteBySyncId(syncId: String): Int =
            if (store.removeAll { it.syncId == syncId }) 1 else 0
        override suspend fun deleteAll() = store.clear()
    }

    private class FakePkDao(initial: List<Passkey>) : PasskeyDao() {
        val store = initial.toMutableList()
        private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
        override fun getAllFlow(): Flow<List<Passkey>> = flowOf(store.toList())
        override suspend fun getAll(): List<Passkey> = store.toList()
        override suspend fun getByRpId(rpId: String): List<Passkey> = store.filter { it.rpId == rpId }
        override suspend fun getByCredentialId(credentialId: String): Passkey? =
            store.firstOrNull { it.credentialId == credentialId }
        override suspend fun getBySyncId(syncId: String): Passkey? = store.firstOrNull { it.syncId == syncId }
        override suspend fun getUpdatedSince(since: Long): List<Passkey> = store.filter { it.updatedAt > since }
        override suspend fun upsertRaw(passkey: Passkey): Long {
            val row = if (passkey.id == 0L) passkey.copy(id = nextId++) else passkey
            val i = store.indexOfFirst { it.id == row.id }
            if (i >= 0) store[i] = row else store.add(row)
            return row.id
        }
        override suspend fun delete(passkey: Passkey): Int =
            if (store.removeAll { it.id == passkey.id }) 1 else 0
        override suspend fun deleteBySyncId(syncId: String): Int =
            if (store.removeAll { it.syncId == syncId }) 1 else 0
        override suspend fun deleteAll() = store.clear()
    }

    private class FakeSnapshotDao : SyncSnapshotDao {
        val store = mutableMapOf<String, SyncSnapshot>()
        override suspend fun getAll(): List<SyncSnapshot> = store.values.toList()
        override suspend fun upsert(snapshot: SyncSnapshot) { store[snapshot.syncId] = snapshot }
        override suspend fun deleteBySyncId(syncId: String) { store.remove(syncId) }
        override suspend fun deleteByKind(kind: String) { store.entries.removeAll { it.value.kind == kind } }
        override suspend fun deleteAll() = store.clear()
    }
}
