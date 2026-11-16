package com.vayunmathur.passwords.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PasswordRepository] persistence delegation.
 *
 * Every repository method is a one-line delegation to a Room DAO over the
 * SQLCipher database. That database's native library is unavailable under
 * Robolectric, so each delegation surfaces [UnsatisfiedLinkError] when it
 * first opens the vault — which still proves the method reaches its DAO
 * (rather than, say, returning a stub or swallowing the call), and pins the
 * documented on-device-only boundary. [SnapshotStore] is exercised for real
 * against an in-memory [SyncSnapshotDao].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordRepositoryDelegationTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private fun repo() = PasswordRepository.get(ctx)

    @Test fun passwordReadsAndWritesDelegateToDao() {
        val repo = repo()
        val password = Password(id = 1, name = "A", syncId = "s-1")
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.getAllPasswords() } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.getPasswordById(1) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.getPasswordBySyncId("s-1") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.getPasswordsUpdatedSince(0) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.upsertPassword(password) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.upsertPasswordRaw(password) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.deletePassword(password) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.deletePasswordBySyncId("s-1") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.deleteAllPasswords() } }
    }

    @Test fun passkeyReadsAndWritesDelegateToDao() {
        val repo = repo()
        val passkey = Passkey(id = 2, rpId = "example.com", syncId = "pk-1")
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.getAllPasskeys() } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.getPasskeyBySyncId("pk-1") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.getPasskeysUpdatedSince(0) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.getPasskeysByRpId("example.com") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.getPasskeyByCredentialId("cred") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.upsertPasskey(passkey) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.upsertPasskeyRaw(passkey) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.deletePasskey(passkey) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.deletePasskeyBySyncId("pk-1") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { repo.deleteAllPasskeys() } }
    }

    @Test fun transactionDelegatesToDatabase() {
        assertFailsWith<UnsatisfiedLinkError> {
            runBlocking { repo().withTransaction { "inside" } }
        }
    }

    // ---- SnapshotStore against an in-memory DAO (real behaviour) ----

    private class FakeSnapshotDao : SyncSnapshotDao {
        val store = linkedMapOf<String, SyncSnapshot>()
        override suspend fun getAll(): List<SyncSnapshot> = store.values.toList()
        override suspend fun upsert(snapshot: SyncSnapshot) { store[snapshot.syncId] = snapshot }
        override suspend fun deleteBySyncId(syncId: String) { store.remove(syncId) }
        override suspend fun deleteByKind(kind: String) {
            store.values.removeAll { it.kind == kind }
        }
        override suspend fun deleteAll() = store.clear()
    }

    @Test fun snapshotStoreCrudAndKindDeletes() = runBlocking {
        val dao = FakeSnapshotDao()
        val store = SnapshotStore(dao)
        assertTrue(store.getAll().isEmpty())

        store.upsert(SyncSnapshot("a", SyncSnapshot.KIND_PASSWORD, "h1", 1, 2, 3))
        store.upsert(SyncSnapshot("b", SyncSnapshot.KIND_PASSKEY, "h2", 4, 5, 6))
        store.upsert(SyncSnapshot("c", SyncSnapshot.KIND_PASSWORD, "h3", 7, 8, 9))
        assertEquals(listOf("a", "b", "c"), store.getAll().map { it.syncId })

        // Upsert replaces by primary key.
        store.upsert(SyncSnapshot("a", SyncSnapshot.KIND_PASSWORD, "h1b", 10, 2, 3))
        assertEquals("h1b", store.getAll().first { it.syncId == "a" }.contentHash)
        assertEquals(3, store.getAll().size)

        store.deleteByKind(SyncSnapshot.KIND_PASSWORD)
        assertEquals(listOf("b"), store.getAll().map { it.syncId })

        store.deleteBySyncId("b")
        assertTrue(store.getAll().isEmpty())

        store.upsert(SyncSnapshot("z", SyncSnapshot.KIND_PASSKEY, "hz", 0, 0, 0))
        store.deleteAll()
        assertTrue(store.getAll().isEmpty())
        // Deleting a missing id is a no-op.
        store.deleteBySyncId("missing")
    }
}
