package com.vayunmathur.everysync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.TokenStore
import com.vayunmathur.everysync.data.RemoteNote
import com.vayunmathur.everysync.data.RemotePassword
import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.data.RemoteTodoList
import com.vayunmathur.everysync.data.VaultMeta
import com.vayunmathur.everysync.data.VaultStore
import com.vayunmathur.everysync.data.readKey
import com.vayunmathur.everysync.data.sink.NoteSink
import com.vayunmathur.everysync.data.sink.PasswordSink
import com.vayunmathur.everysync.data.sink.TodoIpcSink
import com.vayunmathur.everysync.data.sink.TodoListIpcSink
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.SyncDirection
import com.vayunmathur.everysync.provider.impl.E2eeProvider
import com.vayunmathur.everysync.sync.NoteRelayStore
import com.vayunmathur.everysync.sync.PasswordRelayStore
import com.vayunmathur.everysync.sync.RelaySnapshot
import com.vayunmathur.everysync.sync.SnapshotKeyStore
import com.vayunmathur.everysync.sync.SnapshotStore
import com.vayunmathur.everysync.sync.TodoListRelayStore
import com.vayunmathur.everysync.sync.TodoRelayStore
import com.vayunmathur.everysync.sync.adapters.NoteKind
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Full merge cycles (E2eeProvider.driveMerge) and the store/snapshot layers
 * against the fake relay and fake IPC providers. No network, no AndroidKeyStore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class E2eeDriveTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val relay get() = RelaySuccessTest.sharedRelay

    @BeforeTest fun setUp() {
        relay.install()
        FakeIpcProvider.install(NoteSink.AUTHORITY, PasswordSink.AUTHORITY, TodoIpcSink.AUTHORITY)
        val softwareKey = SecretKeySpec(ByteArray(32) { (it * 7 + 1).toByte() }, "AES")
        com.vayunmathur.everysync.data.VaultKeyStore.secretKeyForTest = softwareKey
        SnapshotKeyStore.secretKeyForTest = softwareKey
        TokenStore.getInstance(ctx).secretKeyForTest = softwareKey
    }

    private suspend fun makeVault(type: String, vaultId: String): Pair<VaultMeta, ByteArray> {
        val key = RelaySync.newVaultKey()
        val meta = VaultMeta(
            vaultId = vaultId, dataType = type, title = "T-$type",
            keyB64 = Base64.encode(key), ownerDeviceId = RelaySync.deviceId,
            ownerKeyB64 = Base64.encode(RelaySync.publicBundle), isOwner = true,
        )
        VaultStore.upsert(ctx, meta)
        com.vayunmathur.everysync.provider.SyncState.set(ctx, ACCOUNT, "e2ee_vault_$type", vaultId)
        return VaultStore.get(ctx, vaultId)!! to key
    }

    private fun config(vararg types: DataType) = AccountConfig(ACCOUNT, "e2ee", types.toSet())

    @Test fun keyWrappingRoundTrips() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val (meta, key) = makeVault("notes", "v-keywrap")
        // Stored value is sealed, the read key is the original vault key
        assertTrue(com.vayunmathur.everysync.data.VaultKeyStore.looksSealed(meta.keyB64), meta.keyB64.take(8))
        assertTrue(meta.readKey(ctx)!!.contentEquals(key))
        // TokenStore sync keys round-trip and clear
        val tokens = TokenStore.getInstance(ctx)
        tokens.putSyncKey(ACCOUNT, "notes", "sekrit")
        assertEquals("sekrit", tokens.getSyncKey(ACCOUNT, "notes"))
        assertNull(tokens.getSyncKey(ACCOUNT, "todos"))
        tokens.clear(ACCOUNT)
        // SnapshotKeyStore seal/unseal
        val sealed = SnapshotKeyStore.seal(ctx, "payload".encodeToByteArray())
        assertNotNull(sealed)
        assertEquals("payload", SnapshotKeyStore.unseal(ctx, sealed)?.decodeToString())
        assertNull(SnapshotKeyStore.unseal(ctx, byteArrayOf(1, 2, 3)))
    } }

    @Test fun notesVaultFullCycle() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val (meta, key) = makeVault("notes", "v-notes1")
        val provider = E2eeProvider()
        val channel = RelaySync.recordChannel("notes", meta.vaultId)
        // Empty vault syncs cleanly in every direction
        provider.sync(ctx, config(DataType.NOTES), SyncDirection.BOTH)
        assertTrue(relay.channel(channel).isEmpty())
        // Local note is pushed encrypted
        NoteSink.upsert(ctx, meta.vaultId, RemoteNote(uid = "n1", title = "First", content = "body", updatedAt = 1_000))
        provider.sync(ctx, config(DataType.NOTES), SyncDirection.BOTH)
        assertEquals(1, relay.channel(channel).size)
        val pulled = RelaySync.pullRecords(channel, key, 0)
        assertEquals("n1", NoteKind.fromJson(pulled!!.items.single())?.uid)
        // Second cycle is a no-op (baseline clean)
        provider.sync(ctx, config(DataType.NOTES), SyncDirection.BOTH)
        assertEquals(1, relay.channel(channel).size)
        // Remote edit wins locally
        RelaySync.appendRecords(channel, key, listOf(NoteKind.toJson(RemoteNote(uid = "n1", title = "Edited remotely", content = "body", updatedAt = 2_000))))
        provider.sync(ctx, config(DataType.NOTES), SyncDirection.BOTH)
        assertEquals("Edited remotely", NoteSink.listLocal(ctx, meta.vaultId).single { it.uid == "n1" }.title)
        // Remote tombstone soft-deletes locally
        RelaySync.appendRecords(channel, key, listOf(NoteKind.toJson(RemoteNote(uid = "n1", title = "Edited remotely", deleted = true, updatedAt = 3_000))))
        provider.sync(ctx, config(DataType.NOTES), SyncDirection.BOTH)
        assertTrue(NoteSink.listLocal(ctx, meta.vaultId).single { it.uid == "n1" }.deleted)
        // PULL never appends: a fresh local note stays off the relay
        NoteSink.upsert(ctx, meta.vaultId, RemoteNote(uid = "n2", title = "Local only", updatedAt = 4_000))
        val before = relay.channel(channel).size
        provider.sync(ctx, config(DataType.NOTES), SyncDirection.PULL)
        assertEquals(before, relay.channel(channel).size)
        // PUSH appends local edits but does not apply remote state
        RelaySync.appendRecords(channel, key, listOf(NoteKind.toJson(RemoteNote(uid = "n3", title = "Remote only", updatedAt = 5_000))))
        provider.sync(ctx, config(DataType.NOTES), SyncDirection.PUSH)
        assertTrue(relay.channel(channel).size > before)
        assertTrue(NoteSink.listLocal(ctx, meta.vaultId).none { it.uid == "n3" })
        // Relay down: sync surfaces the relay-unavailable abort, state untouched
        relay.transportDown = true
        // init is already done, so the abort comes from the pull instead
        val ex = runCatching { provider.sync(ctx, config(DataType.NOTES), SyncDirection.BOTH) }.exceptionOrNull()
        assertTrue(ex is com.vayunmathur.everysync.provider.impl.VaultSyncAbort, "$ex")
        relay.transportDown = false
        assertTrue(NoteSink.listLocal(ctx, meta.vaultId).any { it.uid == "n2" })
    } }

    @Test fun pullOnlyDoesNotOrphanLocalOnlyNotes() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val (meta, key) = makeVault("notes", "v-pull-orphan")
        val provider = E2eeProvider()
        val channel = RelaySync.recordChannel("notes", meta.vaultId)
        NoteSink.upsert(ctx, meta.vaultId, RemoteNote(uid = "np1", title = "Local only", updatedAt = 1_000))
        // A PULL cycle must not append, and must not mark np1 as synced:
        // the following BOTH cycle still has to push it.
        provider.sync(ctx, config(DataType.NOTES), SyncDirection.PULL)
        assertTrue(relay.channel(channel).isEmpty())
        provider.sync(ctx, config(DataType.NOTES), SyncDirection.BOTH)
        val pulled = RelaySync.pullRecords(channel, key, 0)
        assertEquals(listOf("np1"), pulled!!.items.mapNotNull { NoteKind.fromJson(it)?.uid })
        // A diverged local edit made before a PULL must survive the next
        // BOTH cycle (push it), not be overwritten by the stale remote copy.
        NoteSink.upsert(ctx, meta.vaultId, RemoteNote(uid = "np1", title = "Edited offline", updatedAt = 2_000))
        provider.sync(ctx, config(DataType.NOTES), SyncDirection.PULL)
        provider.sync(ctx, config(DataType.NOTES), SyncDirection.BOTH)
        val after = RelaySync.pullRecords(channel, key, 0)!!.items
            .mapNotNull { NoteKind.fromJson(it) }
        assertEquals("Edited offline", after.last { it.uid == "np1" }.title)
        assertEquals("Edited offline", NoteSink.listLocal(ctx, meta.vaultId).single { it.uid == "np1" }.title)
    } }

    @Test fun passwordsVaultHardDeleteCycle() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val (meta, key) = makeVault("passwords", "v-pass1")
        val provider = E2eeProvider()
        val channel = RelaySync.recordChannel("passwords", meta.vaultId)
        PasswordSink.upsert(ctx, meta.vaultId, RemotePassword(uid = "p1", name = "Mail", username = "alice", password = "s3cret", updatedAt = 1_000))
        PasswordSink.upsert(ctx, meta.vaultId, RemotePassword(uid = "k1", isPasskey = true, rpId = "example.com", credentialId = "cred1", updatedAt = 1_000))
        provider.sync(ctx, config(DataType.PASSWORDS), SyncDirection.BOTH)
        assertEquals(2, relay.channel(channel).size)
        assertEquals(2, PasswordSink.listLocalOrThrow(ctx, meta.vaultId).size)
        // Hard delete locally propagates a tombstone to the relay
        PasswordSink.delete(ctx, meta.vaultId, "p1")
        provider.sync(ctx, config(DataType.PASSWORDS), SyncDirection.BOTH)
        val items = RelaySync.pullRecords(channel, key, 0)!!.items
            .mapNotNull { com.vayunmathur.everysync.sync.adapters.PasswordKind.fromJson(it) }
        assertTrue(items.any { it.uid == "p1" && it.deleted }, items.toString())
        assertTrue(PasswordSink.listLocalOrThrow(ctx, meta.vaultId).none { it.uid == "p1" })
        assertTrue(PasswordSink.listLocalOrThrow(ctx, meta.vaultId).any { it.uid == "k1" })
    } }

    @Test fun todosVaultAndListsCycle() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val (meta, key) = makeVault("todos", "v-todo1")
        val provider = E2eeProvider()
        TodoIpcSink.upsert(ctx, meta.vaultId, RemoteTodo(uid = "t1", title = "Buy milk", dueAt = 123L, updatedAt = 1_000))
        TodoListIpcSink.upsertAll(ctx, meta.vaultId, listOf(RemoteTodoList(uid = "l1", name = "Home", updatedAt = 1_000)))
        provider.sync(ctx, config(DataType.TODOS), SyncDirection.BOTH)
        assertEquals(1, relay.channel(RelaySync.recordChannel("todos", meta.vaultId)).size)
        assertEquals(1, relay.channel(RelaySync.recordChannel("todolists", meta.vaultId)).size)
        assertTrue(TodoListIpcSink.isSupported(ctx, meta.vaultId))
        // Remote todo arrives locally
        RelaySync.appendRecords(
            RelaySync.recordChannel("todos", meta.vaultId), key,
            listOf(com.vayunmathur.everysync.sync.adapters.TodoKind.toJson(RemoteTodo(uid = "t2", title = "From peer", updatedAt = 2_000))),
        )
        provider.sync(ctx, config(DataType.TODOS), SyncDirection.BOTH)
        assertTrue(TodoIpcSink.listLocal(ctx, meta.vaultId).any { it.uid == "t2" && it.title == "From peer" })
    } }

    @Test fun relayStoresDirectly() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val (meta, _) = makeVault("notes", "v-store1")
        val store = NoteRelayStore(ctx, meta.vaultId)
        assertNull(store.parseRemote("not json"))
        assertNull(store.parseRemote(NoteKind.toJson(RemoteNote(uid = "", title = "blank"))))
        val note = RemoteNote(uid = "s1", title = "Stored", updatedAt = 7)
        assertEquals(note, store.parseRemote(store.serializeForRemote(note)))
        store.upsertLocal(listOf(note))
        assertEquals(listOf(note), store.listLocal())
        assertTrue(store.listLocalOk)
        store.saveSnapshots(listOf(RelaySnapshot("s1", "note", "hash", 7, 7, 1)))
        assertEquals("hash", store.loadBaseline()["s1"]?.contentHash)
        store.removeSnapshots(listOf("s1"))
        // DataStoreUtils sync reads lag the suspend edit briefly under load
        val deadline = System.currentTimeMillis() + 10_000
        while (store.loadBaseline().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue(store.loadBaseline().isEmpty())
        store.deleteLocal(listOf(note))
        assertTrue(store.listLocal().single().deleted)

        val passStore = PasswordRelayStore(ctx, meta.vaultId)
        val pw = RemotePassword(uid = "pw1", name = "Bank", updatedAt = 3)
        passStore.upsertLocal(listOf(pw))
        assertEquals(listOf(pw), passStore.listLocal())
        passStore.deleteLocal(listOf(pw))
        assertTrue(passStore.listLocal().isEmpty())

        val todoStore = TodoRelayStore(ctx, meta.vaultId)
        val todo = RemoteTodo(uid = "td1", title = "Task", updatedAt = 3)
        todoStore.upsertLocal(listOf(todo))
        assertEquals(listOf(todo), todoStore.listLocal())
        todoStore.deleteLocal(listOf(todo))
        assertTrue(todoStore.listLocal().single().deleted)

        val listStore = TodoListRelayStore(ctx, meta.vaultId)
        val list = RemoteTodoList(uid = "tl1", name = "Work", updatedAt = 3)
        listStore.upsertLocal(listOf(list))
        assertEquals(listOf(list), listStore.listLocal())
        assertEquals(list, listStore.parseRemote(listStore.serializeForRemote(list)))
    } }

    @Test fun snapshotTakeRestoreAndPrune() { runBlocking {
        assertTrue(RelaySync.init(ctx))
        val (meta, _) = makeVault("notes", "v-snap1")
        NoteSink.upsert(ctx, meta.vaultId, RemoteNote(uid = "sn1", title = "Snap me", updatedAt = 10))
        assertFalse(SnapshotStore.takenToday(ctx, meta.vaultId))
        assertTrue(SnapshotStore.snapshotVault(ctx, meta.vaultId, "notes"))
        assertTrue(SnapshotStore.takenToday(ctx, meta.vaultId))
        val all = SnapshotStore.listAll(ctx)
        val mine = all.first { it.vaultId == meta.vaultId }
        assertEquals(1, mine.recordCount)
        assertEquals(listOf(mine), SnapshotStore.listForVault(ctx, meta.vaultId))
        // Same-day snapshotAll is a no-op for this vault
        SnapshotStore.snapshotAll(ctx)
        assertEquals(1, SnapshotStore.listForVault(ctx, meta.vaultId).size)
        // Purge local state, then restore brings the record back
        NoteSink.purge(ctx, ACCOUNT, meta.vaultId)
        assertTrue(NoteSink.listLocal(ctx, meta.vaultId).isEmpty())
        assertEquals(1, SnapshotStore.restore(ctx, mine))
        assertEquals("Snap me", NoteSink.listLocal(ctx, meta.vaultId).single().title)
        // Restoring a snapshot whose file is gone fails closed
        assertNull(SnapshotStore.restore(ctx, SnapshotStore.SnapshotMeta("ghost", "notes", 86_400_000L, 1)))
        // Unknown type cannot snapshot
        assertFalse(SnapshotStore.snapshotVault(ctx, meta.vaultId, "bogus-type"))
        // An old snapshot is pruned by age
        val oldDay = System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000
        assertTrue(SnapshotStore.snapshotVault(ctx, meta.vaultId, "notes", nowMs = oldDay))
        assertEquals(2, SnapshotStore.listForVault(ctx, meta.vaultId).size)
        SnapshotStore.pruneExpired(ctx)
        assertEquals(1, SnapshotStore.listForVault(ctx, meta.vaultId).size)
    } }

    private companion object { const val ACCOUNT = "alice" }
}
