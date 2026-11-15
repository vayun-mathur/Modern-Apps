package com.vayunmathur.passwords.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryAndEntitiesExtraTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun repositoryGetReturnsSingleton() {
        val first = PasswordRepository.get(ctx)
        val second = PasswordRepository.get(ctx.applicationContext)
        assertSame(first, second)
    }

    @Test fun snapshotStoreDelegatesEveryOperation() = runBlocking {
        val dao = FakeSnapshotDao()
        val store = SnapshotStore(dao)
        store.upsert(SyncSnapshot("a", SyncSnapshot.KIND_PASSWORD, "h1", 1, 2, 3))
        store.upsert(SyncSnapshot("b", SyncSnapshot.KIND_PASSKEY, "h2", 4, 5, 6))
        store.upsert(SyncSnapshot("c", SyncSnapshot.KIND_PASSWORD, "h3", 7, 8, 9))
        assertEquals(listOf("a", "b", "c"), store.getAll().map { it.syncId })
        // Upsert replaces by primary key.
        store.upsert(SyncSnapshot("a", SyncSnapshot.KIND_PASSWORD, "h1b", 10, 2, 3))
        assertEquals("h1b", store.getAll().first { it.syncId == "a" }.contentHash)
        store.deleteByKind(SyncSnapshot.KIND_PASSWORD)
        assertEquals(listOf("b"), store.getAll().map { it.syncId })
        store.deleteBySyncId("b")
        assertTrue(store.getAll().isEmpty())
        store.upsert(SyncSnapshot("z", SyncSnapshot.KIND_PASSKEY, "hz", 0, 0, 0))
        store.deleteAll()
        assertTrue(store.getAll().isEmpty())
        assertEquals("password", SyncSnapshot.KIND_PASSWORD)
        assertEquals("passkey", SyncSnapshot.KIND_PASSKEY)
    }

    @Test fun passkeyEqualityAndHashCoverEveryField() {
        val base = Passkey(
            id = 1, rpId = "r", rpName = "R", credentialId = "c", userId = "u",
            userName = "n", userDisplayName = "d", privateKeyBytes = byteArrayOf(1, 2),
            creationTime = 1, lastUsedTime = 2, signCount = 3,
            linkedPasswordSyncId = "link", syncId = "s", updatedAt = 4,
        )
        val identical = base.copy(privateKeyBytes = byteArrayOf(1, 2))
        assertEquals(base, identical)
        assertEquals(base.hashCode(), identical.hashCode())
        assertFalse(base == null)
        assertFalse(base.equals("not a passkey"))
        assertEquals(base, base)
        // Each single-field change makes the row unequal (StateFlow conflation guard).
        assertNotEquals(base, base.copy(id = 2))
        assertNotEquals(base, base.copy(rpId = "x"))
        assertNotEquals(base, base.copy(rpName = "x"))
        assertNotEquals(base, base.copy(credentialId = "x"))
        assertNotEquals(base, base.copy(userId = "x"))
        assertNotEquals(base, base.copy(userName = "x"))
        assertNotEquals(base, base.copy(userDisplayName = "x"))
        assertNotEquals(base, base.copy(privateKeyBytes = byteArrayOf(9)))
        assertNotEquals(base, base.copy(creationTime = 99))
        assertNotEquals(base, base.copy(lastUsedTime = 99))
        assertNotEquals(base, base.copy(signCount = 99))
        assertNotEquals(base, base.copy(linkedPasswordSyncId = null))
        assertNotEquals(base, base.copy(syncId = "x"))
        assertNotEquals(base, base.copy(updatedAt = 99))
        assertNotEquals(base.hashCode(), base.copy(signCount = 99).hashCode())
    }

    @Test fun passwordEntityDefaultsAndCopy() {
        val blank = Password()
        assertEquals(0L, blank.id)
        assertEquals("", blank.name)
        assertEquals(emptyList(), blank.websites)
        assertEquals(null, blank.totpSecret)
        assertEquals(32, blank.syncId.length)
        assertTrue(blank.syncId.all { it in '0'..'9' || it in 'a'..'f' })
        val named = blank.copy(name = "Named", websites = listOf("a.com"))
        assertEquals("Named", named.name)
        assertEquals(blank.syncId, named.syncId) // copy preserves identity
        assertNotEquals(blank, named)
        assertEquals(named, named.copy())
        assertEquals(named.hashCode(), named.copy().hashCode())
        assertTrue(named.toString().contains("Named"))
    }

    @Test fun daoUpsertBumpsTimestampButRawPreservesIt() = runBlocking {
        // The DAO default methods are the only place the "upsert bumps, raw preserves"
        // contract lives; exercising them against a fake raw store pins both halves.
        val raw = mutableMapOf<Long, Password>()
        val dao = object : PasswordDao() {
            override fun getAllFlow(): Flow<List<Password>> = flowOf(raw.values.toList())
            override suspend fun getAll(): List<Password> = raw.values.toList()
            override fun getByIdFlow(id: Long): Flow<Password?> = flowOf(raw[id])
            override suspend fun getById(id: Long): Password? = raw[id]
            override suspend fun getBySyncId(syncId: String): Password? =
                raw.values.firstOrNull { it.syncId == syncId }
            override suspend fun getUpdatedSince(since: Long): List<Password> =
                raw.values.filter { it.updatedAt > since }
            override suspend fun upsertRaw(value: Password): Long {
                raw[value.id] = value
                return value.id
            }
            override suspend fun delete(value: Password): Int = if (raw.remove(value.id) != null) 1 else 0
            override suspend fun deleteBySyncId(syncId: String): Int {
                val victim = raw.values.firstOrNull { it.syncId == syncId } ?: return 0
                raw.remove(victim.id)
                return 1
            }
            override suspend fun deleteAll() = raw.clear()
        }
        dao.upsertRaw(Password(id = 1, name = "A", syncId = "s", updatedAt = 123))
        assertEquals(123L, dao.getById(1)?.updatedAt)
        dao.upsert(Password(id = 1, name = "A", syncId = "s", updatedAt = 123))
        assertTrue((dao.getById(1)?.updatedAt ?: 0) > 123)
    }

    private class FakeSnapshotDao : SyncSnapshotDao {
        val store = linkedMapOf<String, SyncSnapshot>()
        override suspend fun getAll(): List<SyncSnapshot> = store.values.toList()
        override suspend fun upsert(snapshot: SyncSnapshot) { store[snapshot.syncId] = snapshot }
        override suspend fun deleteBySyncId(syncId: String) { store.remove(syncId) }
        override suspend fun deleteByKind(kind: String) { store.entries.removeAll { it.value.kind == kind } }
        override suspend fun deleteAll() = store.clear()
    }
}
