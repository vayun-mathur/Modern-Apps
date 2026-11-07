package com.vayunmathur.everysync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.everysync.data.RemoteNote
import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.data.VaultKeyStore
import com.vayunmathur.everysync.data.VaultMeta
import com.vayunmathur.everysync.data.readKey
import com.vayunmathur.everysync.data.sink.NoteSink
import com.vayunmathur.everysync.data.sink.PasswordSink
import com.vayunmathur.everysync.data.sink.TodoIpcSink
import com.vayunmathur.everysync.sync.SnapshotKeyStore
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** IPC sink operations the merge does not exercise directly, and keystore failure branches. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Sinks2Test {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        FakeIpcProvider.install(NoteSink.AUTHORITY, PasswordSink.AUTHORITY, TodoIpcSink.AUTHORITY)
        val softwareKey = SecretKeySpec(ByteArray(32) { (it * 17 + 9).toByte() }, "AES")
        VaultKeyStore.secretKeyForTest = softwareKey
        SnapshotKeyStore.secretKeyForTest = softwareKey
    }

    @Test fun todoSinkOperations() {
        val v = "v-sink-todo"
        TodoIpcSink.upsert(ctx, v, RemoteTodo(uid = "a", title = "A", updatedAt = 100))
        TodoIpcSink.upsert(ctx, v, RemoteTodo(uid = "b", title = "B", updatedAt = 200))
        TodoIpcSink.upsert(ctx, v, RemoteTodo(uid = "c", title = "C", updatedAt = 300))
        assertEquals(3, TodoIpcSink.listLocal(ctx, v).size)
        assertTrue(TodoIpcSink.lastListLocalOk)
        // etag map + since-deltas
        assertEquals(mapOf("a" to 100L, "b" to 200L, "c" to 300L), TodoIpcSink.localUidToEtag(ctx, v))
        val changes = TodoIpcSink.getLocalChanges(ctx, v, since = 150)
        assertEquals(setOf("b", "c"), changes.map { it.syncId }.toSet())
        assertTrue(changes.all { !it.deleted && it.todo != null })
        // upsert replaces by uid
        TodoIpcSink.upsert(ctx, v, RemoteTodo(uid = "a", title = "A2", updatedAt = 400))
        assertEquals("A2", TodoIpcSink.listLocal(ctx, v).first { it.uid == "a" }.title)
        // delete tombstones; the change feed reports it deleted with no body
        TodoIpcSink.delete(ctx, v, "b")
        val afterDelete = TodoIpcSink.getLocalChanges(ctx, v, since = 0)
        assertTrue(afterDelete.first { it.syncId == "b" }.deleted)
        assertNull(afterDelete.first { it.syncId == "b" }.todo)
        assertEquals(3, TodoIpcSink.listLocal(ctx, v).size)
        // purge empties the vault table
        TodoIpcSink.purge(ctx, "acc", v)
        assertTrue(TodoIpcSink.listLocal(ctx, v).isEmpty())
        // JSON conversion paths
        val todo = RemoteTodo(uid = "j1", title = "Json", notes = "n", done = true, priority = 2, tags = listOf("x"))
        assertEquals(todo, TodoIpcSink.fromRelayJson(TodoIpcSink.toRelayJson(todo)))
        assertNull(TodoIpcSink.fromRelayJson("not json"))
        assertTrue(TodoIpcSink.todosUri(v).toString().contains(v))
    }

    @Test fun noteSinkOperations() {
        val v = "v-sink-note"
        NoteSink.upsertAll(ctx, v, listOf(
            RemoteNote(uid = "n1", title = "One", updatedAt = 10),
            RemoteNote(uid = "n2", title = "Two", updatedAt = 20),
        ))
        assertEquals(2, NoteSink.listLocal(ctx, v).size)
        assertEquals(mapOf("n1" to 10L, "n2" to 20L), NoteSink.localUidToEtag(ctx, v))
        val changes = NoteSink.getLocalChanges(ctx, v, since = 15)
        assertEquals(listOf("n2"), changes.map { it.syncId })
        NoteSink.deleteAll(ctx, v, listOf("n1"))
        assertTrue(NoteSink.listLocal(ctx, v).first { it.uid == "n1" }.deleted)
        assertTrue(NoteSink.getLocalChanges(ctx, v).first { it.syncId == "n1" }.deleted)
        NoteSink.purge(ctx, "acc", v)
        assertTrue(NoteSink.listLocal(ctx, v).isEmpty())
        NoteSink.purge(ctx, v) // back-compat overload (vault id as account)
        // Conversion helpers: native fallback, relay first, corrupt skipped
        val note = RemoteNote(uid = "nx", title = "T", content = "C", position = 1.5, updatedAt = 9)
        assertEquals(note, NoteSink.fromPayload(NoteSink.toRelayJson(note)))
        assertEquals(note, NoteSink.fromPayload(NoteSink.toNativeJson(note)))
        assertEquals(note, NoteSink.fromNativeJson(NoteSink.toNativeJson(note)))
        assertNull(NoteSink.fromPayload("{broken"))
        assertTrue(NoteSink.toNativeJson(note).contains("\"syncId\":\"nx\""))
    }

    @Test fun keyStoreCorruptInputsFailClosed() {
        // Unseal rejects non-sealed, non-base64, and truncated sealed values
        assertNull(VaultKeyStore.unseal(ctx, "plain-b64"))
        assertNull(VaultKeyStore.unseal(ctx, "v1:!!!"))
        assertNull(VaultKeyStore.unseal(ctx, "v1:" + Base64.encode(byteArrayOf(1, 2))))
        assertNull(SnapshotKeyStore.unseal(ctx, byteArrayOf(0, 0, 0)))
        assertNull(SnapshotKeyStore.unseal(ctx, ByteArray(0)))
        // readKey on corrupt/absent material is null, never a crash
        val corrupt = VaultMeta("vx", "notes", "T", keyB64 = "v1:" + Base64.encode(byteArrayOf(5, 5, 5)), ownerDeviceId = "d")
        assertNull(corrupt.readKey(ctx))
        val blank = VaultMeta("vy", "notes", "T", keyB64 = "", ownerDeviceId = "d")
        assertNull(blank.readKey(ctx))
        val notB64 = VaultMeta("vz", "notes", "T", keyB64 = "v1:AAAA", ownerDeviceId = "d")
        assertNull(notB64.readKey(ctx))
        // Seal produces a v1-prefixed value that round-trips through readKey
        val keyB64 = Base64.encode(ByteArray(32) { it.toByte() })
        val sealed = VaultKeyStore.seal(ctx, keyB64)
        assertTrue(sealed != null && VaultKeyStore.looksSealed(sealed))
        val meta = VaultMeta("vw", "notes", "T", keyB64 = sealed!!, ownerDeviceId = "d")
        assertTrue(meta.readKey(ctx)!!.contentEquals(ByteArray(32) { it.toByte() }))
    }
}
