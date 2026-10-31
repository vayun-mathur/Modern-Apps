package com.vayunmathur.todo.sync

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import androidx.core.net.toUri
import com.vayunmathur.library.log.Log
import com.vayunmathur.todo.data.SyncSnapshot
import com.vayunmathur.todo.data.TodoRepository
import kotlinx.coroutines.runBlocking

/**
 * Relay IPC surface for the todo vault, consumed by the everysync app's `TodoIpcSink` and
 * `TodoListIpcSink` (same signing key, different app).
 *
 * Exposes `content://com.vayunmathur.todo.relay/vault/<id>/{todos,lists,snapshots}`. The
 * vault id segment is accepted but ignored: this app holds a single vault. Every row carries
 * the relay columns `syncId, updatedAt, deleted, payloadJson, contentHash`, where
 * `payloadJson` is [RelayTodoJson] / [RelayTodoListJson].
 *
 * Ops accept both the base table URIs and the op-suffixed URIs the sinks use
 * (`.../querySince?since=<ms>`, `.../upsertRaw`, `.../deleteByVault`):
 *  - `querySince`: rows with `updatedAt` past `since` (falling back to `selectionArgs[0]`),
 *    tombstones included.
 *  - `upsertRaw` (insert/update/bulkInsert): writes the payload verbatim, preserving the
 *    pulled `updatedAt` and relay identity. A `deleted = true` payload tombstones the row.
 *  - single delete (`syncId = ?` selection): tombstones the row.
 *  - `deleteByVault` (or selection-less delete): purge — drops the table and its snapshots.
 *
 * `bulkInsert` and `applyBatch` run in one write transaction.
 */
class RelayTodoProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.vayunmathur.todo.relay"
        const val PERMISSION = "com.vayunmathur.todo.permission.RELAY"

        const val COL_SYNC_ID = "syncId"
        const val COL_UPDATED_AT = "updatedAt"
        const val COL_DELETED = "deleted"
        const val COL_PAYLOAD_JSON = "payloadJson"
        const val COL_CONTENT_HASH = "contentHash"

        val COLUMNS = arrayOf(COL_SYNC_ID, COL_UPDATED_AT, COL_DELETED, COL_PAYLOAD_JSON, COL_CONTENT_HASH)

        private const val PARAM_SINCE = "since"
        private const val EVERYSYNC = "com.vayunmathur.everysync"

        private const val CODE_TODOS = 1
        private const val CODE_SNAPSHOTS = 2
        private const val CODE_LISTS = 3
        private const val CODE_TODOS_QUERY = 11
        private const val CODE_LISTS_QUERY = 13
        private const val CODE_TODOS_UPSERT = 21
        private const val CODE_SNAPSHOTS_UPSERT = 22
        private const val CODE_LISTS_UPSERT = 23
        private const val CODE_TODOS_PURGE = 31
        private const val CODE_SNAPSHOTS_PURGE = 32
        private const val CODE_LISTS_PURGE = 33

        private const val TAG = "RelayTodoProvider"

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "vault/*/todos", CODE_TODOS)
            addURI(AUTHORITY, "vault/*/snapshots", CODE_SNAPSHOTS)
            addURI(AUTHORITY, "vault/*/lists", CODE_LISTS)
            addURI(AUTHORITY, "vault/*/todos/querySince", CODE_TODOS_QUERY)
            addURI(AUTHORITY, "vault/*/lists/querySince", CODE_LISTS_QUERY)
            addURI(AUTHORITY, "vault/*/todos/upsertRaw", CODE_TODOS_UPSERT)
            addURI(AUTHORITY, "vault/*/snapshots/upsertRaw", CODE_SNAPSHOTS_UPSERT)
            addURI(AUTHORITY, "vault/*/lists/upsertRaw", CODE_LISTS_UPSERT)
            addURI(AUTHORITY, "vault/*/todos/deleteByVault", CODE_TODOS_PURGE)
            addURI(AUTHORITY, "vault/*/snapshots/deleteByVault", CODE_SNAPSHOTS_PURGE)
            addURI(AUTHORITY, "vault/*/lists/deleteByVault", CODE_LISTS_PURGE)
        }

        fun todosUri(vaultId: String): Uri = "content://$AUTHORITY/vault/$vaultId/todos".toUri()
        fun listsUri(vaultId: String): Uri = "content://$AUTHORITY/vault/$vaultId/lists".toUri()
        fun snapshotsUri(vaultId: String): Uri = "content://$AUTHORITY/vault/$vaultId/snapshots".toUri()
    }

    private fun repo(): TodoRepository = TodoRepository.get(context ?: error("RelayTodoProvider context null"))

    override fun onCreate(): Boolean = true

    // The signature permission only proves "signed by the Modern Apps key", which
    // every app in the repo is. Narrow that to the everysync package as well.
    private fun enforceRelayCaller() {
        if (Binder.getCallingUid() == Process.myUid()) return
        val caller = callingPackage
        if (caller != EVERYSYNC) throw SecurityException("RelayTodoProvider: caller $caller is not everysync")
    }

    private fun <T> guarded(op: String, uri: Uri, fallback: T, block: suspend (TodoRepository) -> T): T {
        enforceRelayCaller()
        return runBlocking(repo().io) {
            try {
                block(repo())
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "$op failed $uri", e)
                fallback
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "$op failed $uri", e)
                fallback
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = guarded("query", uri, null) { repo ->
        val since = uri.getQueryParameter(PARAM_SINCE)?.toLongOrNull()
            ?: selectionArgs?.firstOrNull()?.toLongOrNull()
            ?: 0L
        when (uriMatcher.match(uri)) {
            CODE_TODOS, CODE_TODOS_QUERY -> cursorOf(repo.getUpdatedSince(since).map { it.toRelayJson() }) {
                arrayOf(it.uid, it.updatedAt, it.deleted.toInt(), relayJson.encodeToString(it), relayContentHash(it))
            }
            CODE_LISTS, CODE_LISTS_QUERY -> cursorOf(repo.getListsUpdatedSince(since).map { it.toRelayJson() }) {
                arrayOf(it.uid, it.updatedAt, it.deleted.toInt(), relayJson.encodeToString(it), relayContentHash(it))
            }
            CODE_SNAPSHOTS -> cursorOf(repo.getTodoSnapshots() + repo.getListSnapshots()) {
                arrayOf(it.syncId, it.localUpdatedAt, 0, "", it.contentHash)
            }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val v = values ?: return null
        return guarded("insert", uri, null) { repo -> handleUpsert(repo, uri, v)?.let { Uri.withAppendedPath(uri, it) } }
    }

    override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int =
        guarded("bulkInsert", uri, 0) { repo ->
            repo.inTransaction { values.count { handleUpsert(repo, uri, it) != null } }
        }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val v = values ?: return 0
        return guarded("update", uri, 0) { repo -> if (handleUpsert(repo, uri, v) != null) 1 else 0 }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        guarded("delete", uri, 0) { repo -> repo.inTransaction { handleDelete(repo, uri, selection, selectionArgs) } }

    override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> =
        guarded("applyBatch", Uri.EMPTY, emptyArray()) { repo ->
            repo.inTransaction {
                val results = ArrayList<ContentProviderResult>(operations.size)
                for ((index, op) in operations.withIndex()) {
                    val prior = results.toTypedArray()
                    results += when {
                        op.isDelete -> {
                            val args = op.resolveSelectionArgsBackReferences(prior, index)
                            val selection = if (args.isNullOrEmpty()) null else "$COL_SYNC_ID = ?"
                            ContentProviderResult(handleDelete(repo, op.uri, selection, args))
                        }
                        else -> {
                            val values: ContentValues = op.resolveValueBackReferences(prior, index) ?: ContentValues()
                            val id = handleUpsert(repo, op.uri, values)
                            ContentProviderResult(if (id != null) 1 else 0)
                        }
                    }
                }
                results.toTypedArray()
            }
        }

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        CODE_TODOS, CODE_TODOS_QUERY -> "vnd.android.cursor.dir/vnd.com.vayunmathur.todo.todo"
        CODE_LISTS, CODE_LISTS_QUERY -> "vnd.android.cursor.dir/vnd.com.vayunmathur.todo.list"
        CODE_SNAPSHOTS -> "vnd.android.cursor.dir/vnd.com.vayunmathur.todo.snapshot"
        else -> null
    }

    private suspend fun handleUpsert(repo: TodoRepository, uri: Uri, v: ContentValues): String? =
        when (uriMatcher.match(uri)) {
            CODE_TODOS, CODE_TODOS_UPSERT -> applyRelayTodo(repo, v)
            CODE_LISTS, CODE_LISTS_UPSERT -> applyRelayList(repo, v)
            CODE_SNAPSHOTS, CODE_SNAPSHOTS_UPSERT -> upsertSnapshot(repo, v)
            else -> null
        }

    private suspend fun handleDelete(
        repo: TodoRepository,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val syncId = selectionArgs?.firstOrNull()?.takeIf { selection != null || uriMatcher.match(uri) == CODE_SNAPSHOTS }
        return when (uriMatcher.match(uri)) {
            CODE_TODOS_PURGE -> purgeTodos(repo)
            CODE_LISTS_PURGE -> purgeLists(repo)
            CODE_SNAPSHOTS_PURGE -> {
                repo.deleteTodoSnapshots()
                repo.deleteListSnapshots()
                0
            }
            CODE_TODOS -> if (syncId != null) repo.tombstoneBySyncId(syncId) else purgeTodos(repo)
            CODE_LISTS -> if (syncId != null) repo.tombstoneListBySyncId(syncId) else purgeLists(repo)
            CODE_SNAPSHOTS -> if (syncId != null) {
                repo.deleteSnapshotBySyncId(syncId)
                1
            } else {
                repo.deleteTodoSnapshots()
                repo.deleteListSnapshots()
                0
            }
            else -> 0
        }
    }

    private suspend fun purgeTodos(repo: TodoRepository): Int {
        repo.purge()
        repo.deleteTodoSnapshots()
        return 0
    }

    private suspend fun purgeLists(repo: TodoRepository): Int {
        repo.purgeLists()
        repo.deleteListSnapshots()
        return 0
    }

    /** Applies one pulled record, rebinding onto the existing row so a pull updates in place. */
    private suspend fun applyRelayTodo(repo: TodoRepository, v: ContentValues): String? {
        val payload = v.getAsString(COL_PAYLOAD_JSON) ?: return null
        val remote = runCatching { relayJson.decodeFromString<RelayTodoJson>(payload) }.getOrNull() ?: return null
        if (remote.uid.isBlank()) return null
        repo.upsertRaw(remote.toTodo(repo.getBySyncId(remote.uid)?.id ?: 0L))
        return remote.uid
    }

    private suspend fun applyRelayList(repo: TodoRepository, v: ContentValues): String? {
        val payload = v.getAsString(COL_PAYLOAD_JSON) ?: return null
        val remote = runCatching { relayJson.decodeFromString<RelayTodoListJson>(payload) }.getOrNull() ?: return null
        if (remote.uid.isBlank()) return null
        repo.upsertListRaw(remote.toTodoList(repo.getListBySyncId(remote.uid)?.id ?: 0L))
        return remote.uid
    }

    private suspend fun upsertSnapshot(repo: TodoRepository, v: ContentValues): String? {
        val syncId = v.getAsString(COL_SYNC_ID) ?: return null
        repo.upsertSnapshot(
            SyncSnapshot(
                syncId = syncId,
                kind = v.getAsString("kind").orEmpty(),
                contentHash = v.getAsString(COL_CONTENT_HASH).orEmpty(),
                localUpdatedAt = v.getAsLong(COL_UPDATED_AT) ?: 0L,
                remoteModified = v.getAsLong("remoteModified") ?: 0L,
                lastSyncedAt = System.currentTimeMillis(),
            ),
        )
        return syncId
    }

    private fun <T> cursorOf(rows: List<T>, toRow: (T) -> Array<Any?>): MatrixCursor =
        MatrixCursor(COLUMNS).apply { rows.forEach { addRow(toRow(it)) } }

    private fun Boolean.toInt(): Int = if (this) 1 else 0
}
