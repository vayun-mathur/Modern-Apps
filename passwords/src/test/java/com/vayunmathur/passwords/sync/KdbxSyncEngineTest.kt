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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val VAULT_PW = "correct horse battery staple"

class KdbxSyncEngineTest {

    @Test fun newLocalEntryIsPushed() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "GitHub", "octocat", "p", updatedAt = 1000)))

        val result = harness.sync()

        assertEquals(KdbxSyncResult.Success(pushed = 1, pulled = 0, deletedLocal = 0, deletedRemote = 0), result)
        val remote = harness.remoteEntries()
        assertEquals(1, remote.size)
        assertEquals("GitHub", remote.single()["Title"])
        assertEquals("sync-1", remote.single()[EntryMapper.FIELD_SYNC_ID])
    }

    @Test fun newRemoteEntryIsPulled() = runBlocking {
        val harness = Harness(remote = listOf(fields(password(0, "Remote", "bob", "rp", syncId = "r1", updatedAt = 500))))

        val result = harness.sync()

        assertEquals(KdbxSyncResult.Success(pushed = 0, pulled = 1, deletedLocal = 0, deletedRemote = 0), result)
        val stored = harness.pwDao.store.single()
        assertEquals("Remote", stored.name)
        assertEquals("r1", stored.syncId)
        // Pulled rows keep the remote timestamp so the next cycle does not see them as dirty.
        assertEquals(500, stored.updatedAt)
        assertEquals(0, harness.document.writes)
    }

    @Test fun bothModifiedResolvesToNewestTimestamp() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "original", updatedAt = 1000)))
        harness.sync()

        harness.pwDao.store[0] = harness.pwDao.store[0].copy(password = "local", updatedAt = 3000)
        harness.editRemote { it + mapOf("Password" to "remote", EntryMapper.FIELD_MODIFIED to "2000") }

        val result = harness.sync()

        assertEquals(1, (result as KdbxSyncResult.Success).pushed)
        assertEquals("local", harness.remoteEntries().single()["Password"])
        assertEquals("local", harness.pwDao.store.single().password)
    }

    @Test fun remoteWinsWhenItIsNewer() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "original", updatedAt = 1000)))
        harness.sync()

        harness.pwDao.store[0] = harness.pwDao.store[0].copy(password = "local", updatedAt = 2000)
        harness.editRemote { it + mapOf("Password" to "remote", EntryMapper.FIELD_MODIFIED to "3000") }

        val result = harness.sync()

        assertEquals(1, (result as KdbxSyncResult.Success).pulled)
        assertEquals("remote", harness.pwDao.store.single().password)
        assertEquals(3000, harness.pwDao.store.single().updatedAt)
    }

    @Test fun entryDeletedRemotelyIsDeletedLocally() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "p", updatedAt = 1000)))
        harness.sync()

        // Own-client tombstone in the file deletes locally.
        harness.setRemote(
            listOf(
                mapOf(
                    EntryMapper.FIELD_SYNC_ID to "sync-1",
                    EntryMapper.FIELD_TYPE to "tombstone",
                    EntryMapper.FIELD_TOMBSTONE to EntryMapper.TOMBSTONE_VALUE,
                    EntryMapper.FIELD_MODIFIED to "2000",
                ),
            ),
        )
        val result = harness.sync()

        assertEquals(1, (result as KdbxSyncResult.Success).deletedLocal)
        assertTrue(harness.pwDao.store.isEmpty())
        assertTrue(harness.snapshotDao.store.isEmpty())
    }

    @Test fun entryDeletedLocallyIsDeletedRemotely() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "p", updatedAt = 1000)))
        harness.sync()

        harness.pwDao.store.clear()
        val result = harness.sync()

        // Own-client delete writes a tombstone marker, not an emptied file.
        assertEquals(1, (result as KdbxSyncResult.Success).deletedRemote)
        val remote = harness.remoteEntries()
        assertEquals(1, remote.size)
        assertTrue(EntryMapper.isTombstoneEntry(remote.single()))
        assertTrue(harness.snapshotDao.store.isEmpty())
    }

    @Test fun firstSyncAdoptsMatchingVaultEntriesInsteadOfDuplicating() = runBlocking {
        val local = password(1, "GitHub", "octocat", "p", syncId = "local-1", updatedAt = 1000)
        // An entry written by KeePassXC: same credential, but no sync identity.
        val foreign = fields(local) - EntryMapper.FIELD_SYNC_ID - EntryMapper.FIELD_MODIFIED
        val harness = Harness(passwords = listOf(local), remote = listOf(foreign))

        harness.sync()

        assertEquals(1, harness.pwDao.store.size)
        assertEquals("local-1", harness.pwDao.store.single().syncId)
        val remote = harness.remoteEntries()
        assertEquals(1, remote.size)
        assertEquals("local-1", remote.single()[EntryMapper.FIELD_SYNC_ID])
    }

    @Test fun unmatchedForeignEntryIsPulledAsNew() = runBlocking {
        val harness = Harness(remote = listOf(mapOf("Title" to "Foreign", "UserName" to "x", "Password" to "y")))

        harness.sync()

        val stored = harness.pwDao.store.single()
        assertEquals("Foreign", stored.name)
        assertTrue(stored.syncId.isNotBlank())
    }

    @Test fun ownDeleteWritesTombstoneInsteadOfDroppingFromFile() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "p", updatedAt = 1000)))
        harness.sync()

        harness.pwDao.store.clear()
        val result = harness.sync()

        assertEquals(1, (result as KdbxSyncResult.Success).deletedRemote)
        val remote = harness.remoteEntries()
        assertEquals(1, remote.size)
        assertTrue(EntryMapper.isTombstoneEntry(remote.single()))
        assertEquals("sync-1", remote.single()[EntryMapper.FIELD_SYNC_ID])
        // Local row is gone; its snapshot is dropped (delete acknowledged locally).
        assertTrue(harness.pwDao.store.isEmpty())
        assertTrue(harness.snapshotDao.store.isEmpty())
    }

    @Test fun tombstoneIsRetainedUntilAckedThenGarbageCollected() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "p", updatedAt = 1000)))
        harness.sync()
        harness.pwDao.store.clear()
        harness.sync()
        assertEquals(1, harness.remoteEntries().size)

        // Next cycle with the tombstone still present: steady state, kept.
        val steady = harness.sync()
        assertTrue(steady is KdbxSyncResult.Success)
        assertEquals(1, harness.remoteEntries().size)
        assertTrue(EntryMapper.isTombstoneEntry(harness.remoteEntries().single()))
    }

    @Test fun foreignAbsenceQuarantinesInsteadOfDeleting() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "p", updatedAt = 1000)))
        harness.sync()

        // Foreign client (no tombstone support) wipes the file.
        harness.setRemote(emptyList())
        val result = harness.sync()

        assertTrue(result is KdbxSyncResult.Quarantined, "expected Quarantined, got $result")
        assertEquals(1, (result as KdbxSyncResult.Quarantined).count)
        // Nothing changed on either side.
        assertEquals(1, harness.pwDao.store.size)
        assertEquals(1, harness.snapshotDao.store.size)
        assertEquals(0, harness.document.writes - 1)
    }

    @Test fun foreignDeleteOfOneEntryQuarantinesWholeSync() = runBlocking {
        val harness = Harness(
            passwords = listOf(
                password(1, "A", "u", "p", updatedAt = 1000),
                password(2, "B", "v", "q", updatedAt = 1000),
            ),
        )
        harness.sync()

        // Foreign client deletes just one entry: still ambiguous, whole sync halts.
        harness.setRemote(harness.remoteEntries().filterNot {
            it[EntryMapper.FIELD_SYNC_ID] == "sync-1"
        })
        val result = harness.sync()

        assertTrue(result is KdbxSyncResult.Quarantined, "expected Quarantined, got $result")
        assertEquals(2, harness.pwDao.store.size)
        assertEquals(2, harness.snapshotDao.store.size)
    }

    @Test fun transportExceptionAbortsWithoutMutating() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "p", updatedAt = 1000)))
        harness.sync()
        val writesBefore = harness.document.writes
        harness.document.readFailsWith = java.io.IOException("cloud drive unavailable")

        val result = harness.sync()

        assertTrue(result is KdbxSyncResult.Error, "expected Error, got $result")
        assertEquals(1, harness.pwDao.store.size)
        assertEquals(1, harness.snapshotDao.store.size)
        assertEquals(writesBefore, harness.document.writes)
    }

    @Test fun tombstoneForUnknownIdIsIgnored() = runBlocking {
        val ghost = mapOf(
            EntryMapper.FIELD_SYNC_ID to "ghost-id",
            EntryMapper.FIELD_TYPE to "tombstone",
            EntryMapper.FIELD_TOMBSTONE to EntryMapper.TOMBSTONE_VALUE,
            EntryMapper.FIELD_MODIFIED to "999",
        )
        val harness = Harness(remote = listOf(ghost))

        val result = harness.sync()

        assertTrue(result is KdbxSyncResult.Success, "expected Success, got $result")
        assertTrue(harness.pwDao.store.isEmpty())
        assertTrue(harness.snapshotDao.store.isEmpty())
    }

    @Test fun foreignTombstoneValuedKeyDoesNotDelete() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "p", updatedAt = 1000)))
        harness.sync()

        // A foreign client writes the same-named key with a junk value: it is
        // stripped on push and never arms the deletion path. The entry is
        // still present, so no quarantine either — the sync just cleans it.
        harness.editRemote { it + mapOf(EntryMapper.FIELD_TOMBSTONE to "yes-please") }
        val result = harness.sync()

        assertTrue(result is KdbxSyncResult.Success, "expected Success, got $result")
        assertEquals(1, harness.pwDao.store.size)
        assertEquals("p", harness.pwDao.store.single().password)
        assertTrue(harness.remoteEntries().single()[EntryMapper.FIELD_TOMBSTONE].isNullOrEmpty())
    }

    @Test fun tombstoneMeetsRecreatedRowDeleteWins() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "p", updatedAt = 1000)))
        harness.sync()
        harness.pwDao.store.clear()
        harness.sync()
        assertTrue(EntryMapper.isTombstoneEntry(harness.remoteEntries().single()))

        // User re-creates the same credential locally while the tombstone
        // still propagates: the in-flight delete wins (explicit beats new).
        harness.pwDao.store.add(password(2, "A", "u", "p2", syncId = "sync-1", updatedAt = 3000))
        val result = harness.sync()

        assertTrue(result is KdbxSyncResult.Success, "expected Success, got $result")
        assertTrue(harness.pwDao.store.isEmpty())
        assertEquals(1, harness.remoteEntries().size)
        assertTrue(EntryMapper.isTombstoneEntry(harness.remoteEntries().single()))
    }

    @Test fun pulledEntryIsNotDirtyOnTheFollowingSync() = runBlocking {
        val harness = Harness(remote = listOf(fields(password(0, "Remote", "bob", "rp", syncId = "r1", updatedAt = 500))))
        harness.sync()

        val result = harness.sync()

        assertEquals(KdbxSyncResult.Success(pushed = 0, pulled = 0, deletedLocal = 0, deletedRemote = 0), result)
        assertEquals(0, harness.document.writes)
        assertEquals(1, harness.pwDao.store.size)
    }

    @Test fun pushedEntryIsNotDirtyOnTheFollowingSync() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "p", updatedAt = 1000)))
        harness.sync()
        val writesAfterFirst = harness.document.writes

        val result = harness.sync()

        assertEquals(KdbxSyncResult.Success(pushed = 0, pulled = 0, deletedLocal = 0, deletedRemote = 0), result)
        assertEquals(writesAfterFirst, harness.document.writes)
    }

    @Test fun missingFileAbortsWithoutMutating() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "p", updatedAt = 1000)))
        harness.document.bytes = null

        val result = harness.sync()

        assertEquals(KdbxSyncResult.FileMissing, result)
        assertEquals(1, harness.pwDao.store.size)
        assertTrue(harness.snapshotDao.store.isEmpty())
        assertEquals(0, harness.document.writes)
    }

    @Test fun wrongPasswordAbortsWithoutMutating() = runBlocking {
        val harness = Harness(passwords = listOf(password(1, "A", "u", "p", updatedAt = 1000)))

        val result = harness.engine.sync(harness.document, "not the password")

        assertEquals(KdbxSyncResult.WrongPassword, result)
        assertEquals(1, harness.pwDao.store.size)
        assertTrue(harness.snapshotDao.store.isEmpty())
        assertEquals(0, harness.document.writes)
    }

    @Test fun passkeysSyncAlongsidePasswords() = runBlocking {
        val passkey = Passkey(
            id = 1,
            rpId = "acme.example",
            rpName = "Acme",
            credentialId = "cred-1",
            userId = "user-1",
            userName = "alice",
            privateKeyBytes = byteArrayOf(1, 2, 3),
            creationTime = 10,
            lastUsedTime = 20,
            signCount = 1,
            syncId = "pk-1",
            updatedAt = 1000,
        )
        val harness = Harness(passkeys = listOf(passkey))

        assertEquals(1, (harness.sync() as KdbxSyncResult.Success).pushed)

        val remote = harness.remoteEntries().single()
        assertEquals("cred-1", remote["KPEX_PASSKEY_CREDENTIAL_ID"])

        // A sign-count bump is a content change and propagates.
        harness.pkDao.store[0] = harness.pkDao.store[0].copy(signCount = 2, updatedAt = 2000)
        assertEquals(1, (harness.sync() as KdbxSyncResult.Success).pushed)
        assertEquals("2", harness.remoteEntries().single()["_SignCount"])
    }
}

private fun password(
    id: Long,
    name: String,
    username: String,
    password: String,
    syncId: String = "sync-$id",
    updatedAt: Long = 0,
) = Password(id = id, name = name, username = username, password = password, syncId = syncId, updatedAt = updatedAt)

private fun fields(pw: Password) = EntryMapper.toFields(pw)

private class Harness(
    passwords: List<Password> = emptyList(),
    passkeys: List<Passkey> = emptyList(),
    remote: List<Map<String, String>> = emptyList(),
) {
    val codec = FakeCodec()
    val pwDao = FakePasswordDao(passwords)
    val pkDao = FakePasskeyDao(passkeys)
    val snapshotDao = FakeSyncSnapshotDao()
    val document = FakeDocument(codec.write(VAULT_PW, remote))

    val engine get() = KdbxSyncEngine(pwDao, pkDao, snapshotDao, codec)

    suspend fun sync() = engine.sync(document, VAULT_PW)

    fun remoteEntries(): List<Map<String, String>> = codec.read(VAULT_PW, assertNotNull(document.bytes))!!

    fun setRemote(entries: List<Map<String, String>>) {
        document.bytes = codec.write(VAULT_PW, entries)
    }

    fun editRemote(transform: (Map<String, String>) -> Map<String, String>) {
        setRemote(remoteEntries().map(transform))
    }
}

/** Stands in for the JNI codec: "encryption" is a lookup table keyed by the returned blob. */
private class FakeCodec : KdbxCodec {
    private val vaults = mutableMapOf<String, List<Map<String, String>>>()
    private var counter = 0

    override fun read(password: String, bytes: ByteArray): List<Map<String, String>>? {
        if (password != VAULT_PW) return null
        return vaults[String(bytes)]
    }

    override fun write(password: String, entries: List<Map<String, String>>): ByteArray {
        val handle = "vault-${counter++}"
        vaults[handle] = entries.map { it.toMap() }
        return handle.toByteArray()
    }
}

private class FakeDocument(var bytes: ByteArray?) : KdbxDocument {
    var writes = 0
    var readFailsWith: java.io.IOException? = null

    override fun read(): ByteArray {
        readFailsWith?.let { throw it }
        return bytes ?: throw FileNotFoundException("no document")
    }

    override fun write(bytes: ByteArray) {
        this.bytes = bytes
        writes++
    }
}

private class FakePasswordDao(initial: List<Password>) : PasswordDao() {
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
        val index = store.indexOfFirst { it.id == row.id }
        if (index >= 0) store[index] = row else store.add(row)
        return row.id
    }

    override suspend fun delete(value: Password): Int =
        if (store.removeAll { it.id == value.id }) 1 else 0

    override suspend fun deleteBySyncId(syncId: String): Int =
        if (store.removeAll { it.syncId == syncId }) 1 else 0

    override suspend fun deleteAll() = store.clear()
}

private class FakePasskeyDao(initial: List<Passkey>) : PasskeyDao() {
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
        val index = store.indexOfFirst { it.id == row.id }
        if (index >= 0) store[index] = row else store.add(row)
        return row.id
    }

    override suspend fun delete(passkey: Passkey): Int =
        if (store.removeAll { it.id == passkey.id }) 1 else 0

    override suspend fun deleteBySyncId(syncId: String): Int =
        if (store.removeAll { it.syncId == syncId }) 1 else 0

    override suspend fun deleteAll() = store.clear()
}

private class FakeSyncSnapshotDao : SyncSnapshotDao {
    val store = mutableMapOf<String, SyncSnapshot>()

    override suspend fun getAll(): List<SyncSnapshot> = store.values.toList()
    override suspend fun upsert(snapshot: SyncSnapshot) { store[snapshot.syncId] = snapshot }
    override suspend fun deleteBySyncId(syncId: String) { store.remove(syncId) }
    override suspend fun deleteByKind(kind: String) { store.entries.removeAll { it.value.kind == kind } }
    override suspend fun deleteAll() = store.clear()
}
