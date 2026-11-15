package com.vayunmathur.todo.sync

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.todo.data.SyncSnapshot
import com.vayunmathur.todo.data.TestTodoRepository
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.data.getListBySyncId
import com.vayunmathur.todo.data.upsertRaw
import com.vayunmathur.todo.data.upsertListRaw
import com.vayunmathur.todo.data.upsertSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Genuine [RelayTodoProvider] IPC behaviour. The provider obtains its
 * repository through [com.vayunmathur.todo.data.TodoRepository.get], so the
 * in-memory repository is installed as that singleton (see [TestTodoRepository])
 * before the provider is built by Robolectric with a real context.
 */
@RunWith(RobolectricTestRunner::class)
class RelayTodoProviderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var harness: TestTodoRepository? = null
    private val repo get() = harness!!.repository
    private lateinit var provider: RelayTodoProvider

    @BeforeTest fun setUp() {
        harness = TestTodoRepository.create(context)
        provider = Robolectric.buildContentProvider(RelayTodoProvider::class.java).create().get()
    }

    @AfterTest fun tearDown() {
        harness?.close()
        harness = null
    }

    private fun todoValues(todo: Todo): ContentValues {
        val json = relayJson.encodeToString(todo.toRelayJson())
        return ContentValues().apply {
            put(RelayTodoProvider.COL_SYNC_ID, todo.syncId)
            put(RelayTodoProvider.COL_UPDATED_AT, todo.updatedAt)
            put(RelayTodoProvider.COL_DELETED, if (todo.deleted) 1 else 0)
            put(RelayTodoProvider.COL_PAYLOAD_JSON, json)
            put(RelayTodoProvider.COL_CONTENT_HASH, relayContentHash(todo.toRelayJson()))
        }
    }

    private fun listValues(list: TodoList): ContentValues {
        val json = relayJson.encodeToString(list.toRelayJson())
        return ContentValues().apply {
            put(RelayTodoProvider.COL_SYNC_ID, list.syncId)
            put(RelayTodoProvider.COL_PAYLOAD_JSON, json)
        }
    }

    private fun rows(cursor: Cursor?): List<List<Any?>> {
        assertNotNull(cursor)
        val out = mutableListOf<List<Any?>>()
        cursor.use {
            while (it.moveToNext()) {
                out += RelayTodoProvider.COLUMNS.map { col ->
                    val idx = it.getColumnIndex(col)
                    when (col) {
                        RelayTodoProvider.COL_UPDATED_AT -> it.getLong(idx)
                        RelayTodoProvider.COL_DELETED -> it.getInt(idx)
                        else -> it.getString(idx)
                    }
                }
            }
        }
        return out
    }

    private fun seedTodos() = runBlocking {
        repo.upsertRaw(Todo(syncId = "old", title = "Old", updatedAt = 100L))
        repo.upsertRaw(Todo(syncId = "new", title = "New", updatedAt = 5_000L, deleted = true))
    }

    @Test fun queryTodosReturnsAllRowsWithRelayColumns() {
        seedTodos()
        val result = rows(provider.query(RelayTodoProvider.todosUri("v1"), null, null, null, null))
        assertEquals(2, result.size)
        val byId = result.associateBy { it[0] as String }
        assertEquals(100L, byId.getValue("old")[1])
        assertEquals(0, byId.getValue("old")[2])
        assertEquals(1, byId.getValue("new")[2])
        assertTrue((byId.getValue("new")[3] as String).contains("\"uid\":\"new\""))
        assertEquals(64, (byId.getValue("new")[4] as String).length)
    }

    @Test fun queryTodosSinceFiltersByUriParamThenSelectionArg() {
        seedTodos()
        val byParam = rows(
            provider.query(
                RelayTodoProvider.todosUri("v1").buildUpon().appendPath("querySince")
                    .appendQueryParameter("since", "1000").build(),
                null, null, null, null,
            ),
        )
        assertEquals(listOf("new"), byParam.map { it[0] })
        val byArg = rows(
            provider.query(RelayTodoProvider.todosUri("v1"), null, null, arrayOf("1000"), null),
        )
        assertEquals(listOf("new"), byArg.map { it[0] })
    }

    @Test fun queryListsAndSnapshots() = runBlocking {
        repo.upsertRaw(Todo(syncId = "t", title = "T", updatedAt = 10L))
        repo.upsertListRaw(TodoList(syncId = "l", name = "List", updatedAt = 20L))
        repo.upsertSnapshot(
            SyncSnapshot(
                syncId = "t", kind = SyncSnapshot.KIND_TODO, contentHash = "ht",
                localUpdatedAt = 3L, remoteModified = 4L, lastSyncedAt = 5L,
            ),
        )
        repo.upsertSnapshot(
            SyncSnapshot(
                syncId = "l", kind = SyncSnapshot.KIND_LIST, contentHash = "hl",
                localUpdatedAt = 6L, remoteModified = 7L, lastSyncedAt = 8L,
            ),
        )
        val lists = rows(provider.query(RelayTodoProvider.listsUri("v1"), null, null, null, null))
        assertEquals(1, lists.size)
        assertEquals("l", lists.single()[0])
        val snapshots = rows(provider.query(RelayTodoProvider.snapshotsUri("v1"), null, null, null, null))
        assertEquals(setOf("t", "l"), snapshots.map { it[0] }.toSet())
        assertEquals(setOf("ht", "hl"), snapshots.map { it[4] }.toSet())
    }

    @Test fun queryUnknownUriReturnsNull() {
        assertNull(
            provider.query("content://com.vayunmathur.todo.relay/vault/v1/nope".toUri(), null, null, null, null),
        )
    }

    @Test fun insertTodoPreservesPulledTimestampAndReturnsUri() = runBlocking {
        val todo = Todo(syncId = "pulled", title = "From remote", updatedAt = 4242L)
        val uri = provider.insert(RelayTodoProvider.todosUri("v1"), todoValues(todo))
        assertNotNull(uri)
        assertTrue(uri.toString().endsWith("pulled"))
        val stored = repo.getBySyncId("pulled")
        assertEquals("From remote", stored?.title)
        assertEquals(4242L, stored?.updatedAt)
    }

    @Test fun insertTodoUpdatesExistingRowInPlace() = runBlocking {
        provider.insert(RelayTodoProvider.todosUri("v1"), todoValues(Todo(syncId = "x", title = "v1", updatedAt = 1L)))
        val firstId = repo.getBySyncId("x")!!.id
        provider.insert(
            RelayTodoProvider.todosUri("v1").buildUpon().appendPath("upsertRaw").build(),
            todoValues(Todo(syncId = "x", title = "v2", updatedAt = 2L)),
        )
        assertEquals(firstId, repo.getBySyncId("x")!!.id)
        assertEquals("v2", repo.getBySyncId("x")!!.title)
    }

    @Test fun insertRejectsMissingInvalidAndBlankPayloads() {
        assertNull(provider.insert(RelayTodoProvider.todosUri("v1"), ContentValues()))
        val invalid = ContentValues().apply { put(RelayTodoProvider.COL_PAYLOAD_JSON, "{not json") }
        assertNull(provider.insert(RelayTodoProvider.todosUri("v1"), invalid))
        val blank = ContentValues().apply {
            put(RelayTodoProvider.COL_PAYLOAD_JSON, relayJson.encodeToString(RelayTodoJson(uid = "")))
        }
        assertNull(provider.insert(RelayTodoProvider.todosUri("v1"), blank))
        assertNull(provider.insert("content://com.vayunmathur.todo.relay/vault/v1/nope".toUri(), todoValues(Todo(syncId = "z"))))
    }

    @Test fun insertListAndSnapshot() = runBlocking {
        val listUri = provider.insert(
            RelayTodoProvider.listsUri("v1"),
            listValues(TodoList(syncId = "l1", name = "Remote list", updatedAt = 99L)),
        )
        assertNotNull(listUri)
        assertEquals("Remote list", repo.getListBySyncId("l1")?.name)
        assertEquals(99L, repo.getListBySyncId("l1")?.updatedAt)

        val snapValues = ContentValues().apply {
            put(RelayTodoProvider.COL_SYNC_ID, "snap1")
            put("kind", SyncSnapshot.KIND_TODO)
            put(RelayTodoProvider.COL_CONTENT_HASH, "hash1")
            put(RelayTodoProvider.COL_UPDATED_AT, 11L)
            put("remoteModified", 12L)
        }
        assertNotNull(provider.insert(RelayTodoProvider.snapshotsUri("v1"), snapValues))
        val snapshots = rows(provider.query(RelayTodoProvider.snapshotsUri("v1"), null, null, null, null))
        assertEquals(listOf("snap1"), snapshots.map { it[0] })
    }

    @Test fun updateTodoCountsOneAndUnknownCountsZero() = runBlocking {
        assertEquals(
            1,
            provider.update(RelayTodoProvider.todosUri("v1"), todoValues(Todo(syncId = "u", title = "U")), null, null),
        )
        assertEquals("U", repo.getBySyncId("u")?.title)
        assertEquals(
            0,
            provider.update("content://com.vayunmathur.todo.relay/vault/v1/nope".toUri(), todoValues(Todo(syncId = "u")), null, null),
        )
    }

    @Test fun bulkInsertCountsSuccessfulRows() = runBlocking {
        val bad = ContentValues().apply { put(RelayTodoProvider.COL_PAYLOAD_JSON, "nope") }
        val count = provider.bulkInsert(
            RelayTodoProvider.todosUri("v1"),
            arrayOf(todoValues(Todo(syncId = "b1", title = "B1")), bad, todoValues(Todo(syncId = "b2", title = "B2"))),
        )
        assertEquals(2, count)
        assertEquals(setOf("b1", "b2"), repo.getAll().map { it.syncId }.toSet())
    }

    @Test fun deleteBySyncIdTombstonesTodoAndList() = runBlocking {
        repo.upsertRaw(Todo(syncId = "del", title = "Del"))
        repo.upsertListRaw(TodoList(syncId = "dell", name = "Del list"))
        assertEquals(
            1,
            provider.delete(RelayTodoProvider.todosUri("v1"), "syncId = ?", arrayOf("del")),
        )
        assertTrue(repo.getBySyncId("del")!!.deleted)
        assertEquals(
            1,
            provider.delete(RelayTodoProvider.listsUri("v1"), "syncId = ?", arrayOf("dell")),
        )
        assertTrue(harness!!.database.todoListDao().getBySyncId("dell")!!.deleted)
        // Missing id tombstones nothing.
        assertEquals(
            0,
            provider.delete(RelayTodoProvider.todosUri("v1"), "syncId = ?", arrayOf("missing")),
        )
    }

    @Test fun deleteWithoutSelectionPurgesTodosAndSnapshots() = runBlocking {
        repo.upsertRaw(Todo(syncId = "p", title = "P"))
        repo.upsertSnapshot(
            SyncSnapshot(
                syncId = "p", kind = SyncSnapshot.KIND_TODO, contentHash = "h",
                localUpdatedAt = 1L, remoteModified = 1L, lastSyncedAt = 1L,
            ),
        )
        provider.delete(RelayTodoProvider.todosUri("v1"), null, null)
        assertNull(repo.getBySyncId("p"))
        assertTrue(rows(provider.query(RelayTodoProvider.snapshotsUri("v1"), null, null, null, null)).isEmpty())
    }

    @Test fun deleteByVaultUriPurgesLists() = runBlocking {
        repo.upsertListRaw(TodoList(syncId = "pl", name = "Purge me"))
        val purgeUri = RelayTodoProvider.listsUri("v1").buildUpon().appendPath("deleteByVault").build()
        provider.delete(purgeUri, null, null)
        assertNull(repo.getListBySyncId("pl"))
    }

    @Test fun deleteSnapshotByIdAndAllSnapshots() = runBlocking {
        val snapUri = RelayTodoProvider.snapshotsUri("v1")
        fun snap(id: String) = ContentValues().apply {
            put(RelayTodoProvider.COL_SYNC_ID, id)
            put("kind", SyncSnapshot.KIND_TODO)
            put(RelayTodoProvider.COL_CONTENT_HASH, "h-$id")
            put(RelayTodoProvider.COL_UPDATED_AT, 1L)
        }
        provider.insert(snapUri, snap("s1"))
        provider.insert(snapUri, snap("s2"))
        assertEquals(1, provider.delete(snapUri, "syncId = ?", arrayOf("s1")))
        assertEquals(listOf("s2"), rows(provider.query(snapUri, null, null, null, null)).map { it[0] })
        provider.delete(snapUri.buildUpon().appendPath("deleteByVault").build(), null, null)
        assertTrue(rows(provider.query(snapUri, null, null, null, null)).isEmpty())
    }

    @Test fun applyBatchUpsertsAndDeletesInOneCall() { runBlocking {
        repo.upsertRaw(Todo(syncId = "victim", title = "Victim"))
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(RelayTodoProvider.todosUri("v1"))
                .withValues(todoValues(Todo(syncId = "added", title = "Added"))).build(),
            ContentProviderOperation.newDelete(RelayTodoProvider.todosUri("v1"))
                .withSelection("syncId = ?", arrayOf("victim")).build(),
            ContentProviderOperation.newInsert(RelayTodoProvider.listsUri("v1"))
                .withValues(listValues(TodoList(syncId = "bl", name = "Batch list"))).build(),
        )
        val results = provider.applyBatch(ops)
        assertEquals(3, results.size)
        assertEquals(1, results[0].count)
        assertEquals(1, results[1].count)
        assertNotNull(repo.getBySyncId("added"))
        assertTrue(repo.getBySyncId("victim")!!.deleted)
        assertNotNull(repo.getListBySyncId("bl"))
    } }

    @Test fun getTypeForEveryUriFamily() {
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.todo.todo",
            provider.getType(RelayTodoProvider.todosUri("v").buildUpon().appendPath("querySince").build()),
        )
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.todo.list",
            provider.getType(RelayTodoProvider.listsUri("v").buildUpon().appendPath("querySince").build()),
        )
        // upsertRaw URIs are insert-only and have no MIME type.
        assertNull(
            provider.getType(RelayTodoProvider.listsUri("v").buildUpon().appendPath("upsertRaw").build()),
        )
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.todo.snapshot",
            provider.getType(RelayTodoProvider.snapshotsUri("v")),
        )
    }
}

