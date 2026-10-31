package com.vayunmathur.everysync.data.sink

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.sync.adapters.TodoKind
import com.vayunmathur.library.log.Log

/** A locally-changed todo row detected via the app provider's timestamp watermark. */
data class LocalTodoChange(
    val syncId: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val todo: RemoteTodo?,
)

/**
 * Reads/writes todo records through the todo app's relay ContentProvider
 * (owned by the `todo/` app). The contract mirrors the provider exactly:
 *
 * - authority: `com.vayunmathur.todo.relay` (signature-protected)
 * - URIs: base table URIs (`.../vault/<id>/todos`, `.../snapshots`) plus the
 *   op-suffixed forms (`.../todos/querySince`, `.../todos/upsertRaw`,
 *   `.../todos/deleteByVault`, same for snapshots). The vault id segment is
 *   accepted but ignored: the app holds a single vault.
 * - query: `?since=<ms>` query parameter first, `selectionArgs[0]` as
 *   fallback, for deltas (tombstones included — todos soft-delete); no bound
 *   lists the current full state.
 * - upsert: `insert`/`update` with `ContentValues(payloadJson)` carrying the
 *   **envelope JSON** ([RemoteTodo] bytes via [TodoKind.toJson]), preserving
 *   the pulled timestamp and relay identity (`uid` -> `syncId`). A payload
 *   with `deleted = true` tombstones the row instead of hard-deleting it, so
 *   the deletion propagates via the next `querySince`.
 * - delete: a non-null selection with `selectionArgs[0]` = syncId tombstones
 *   one row; `deleteByVault` (or selection-less base delete) purges the whole
 *   table *and* its snapshots.
 * - columns: `syncId`, `updatedAt`, `deleted` (0/1), `payloadJson`,
 *   `contentHash` (canonical-envelope SHA-256 hex, merge-compatible with
 *   [TodoKind]).
 *
 * API mirrors [NoteSink] (`localUidToEtag` / `upsert` / `delete` / `purge` /
 * `getLocalChanges`) so the relay driver treats all app vault types uniformly.
 * `RemoteTodo.uid` is the relay sync identity. Distinct from the [TodoSink]
 * merge-driver interface, which is keyed on typed lists rather than IPC.
 */
object TodoIpcSink {
    private const val TAG = "TodoIpcSink"

    /**
     * Health of the last [listLocal] read: false when the query failed or
     * returned a null cursor. Consulted by `TodoRelayStore.listLocalOk`.
     */
    @Volatile var lastListLocalOk: Boolean = true
        private set

    const val AUTHORITY = "com.vayunmathur.todo.relay"
    const val PATH_TODOS = "todos"
    const val PATH_SNAPSHOTS = "snapshots"
    const val OP_QUERY_SINCE = "querySince"
    const val OP_UPSERT_RAW = "upsertRaw"
    const val OP_DELETE_BY_VAULT = "deleteByVault"
    const val COL_SYNC_ID = "syncId"
    const val COL_UPDATED_AT = "updatedAt"
    const val COL_DELETED = "deleted"
    const val COL_PAYLOAD_JSON = "payloadJson"
    const val COL_CONTENT_HASH = "contentHash"
    const val PARAM_SINCE = "since"

    fun todosUri(vaultId: String): Uri =
        Uri.parse("content://$AUTHORITY/vault/$vaultId/$PATH_TODOS")

    fun snapshotsUri(vaultId: String): Uri =
        Uri.parse("content://$AUTHORITY/vault/$vaultId/$PATH_SNAPSHOTS")

    fun queryUri(vaultId: String): Uri =
        todosUri(vaultId).buildUpon().appendPath(OP_QUERY_SINCE).build()

    fun upsertUri(vaultId: String): Uri =
        todosUri(vaultId).buildUpon().appendPath(OP_UPSERT_RAW).build()

    fun purgeUri(vaultId: String, table: String): Uri =
        Uri.parse("content://$AUTHORITY/vault/$vaultId/$table")
            .buildUpon().appendPath(OP_DELETE_BY_VAULT).build()

    /** Relay envelope for [todo] (adapter bytes — what the relay log carries). */
    fun toRelayJson(todo: RemoteTodo): String = TodoKind.toJson(todo)

    /** Parses an envelope payload back; null skips corrupt/foreign rows. */
    fun fromRelayJson(payload: String): RemoteTodo? =
        TodoKind.fromJson(payload)?.takeIf { it.uid.isNotBlank() }

    /** Map of relay syncId → updatedAt currently present locally for this vault. */
    fun localUidToEtag(context: Context, vaultId: String): Map<String, Long?> {
        val result = mutableMapOf<String, Long?>()
        // Full state, live rows only (tombstones are filtered — the merge
        // reads them via listLocal).
        try {
            context.contentResolver.query(
                todosUri(vaultId),
                arrayOf(COL_SYNC_ID, COL_UPDATED_AT),
                "$COL_DELETED = 0",
                null,
                null,
            )?.use { c ->
                val idIdx = c.getColumnIndex(COL_SYNC_ID)
                val tsIdx = c.getColumnIndex(COL_UPDATED_AT)
                while (c.moveToNext()) {
                    val uid = (if (idIdx >= 0) c.getStringOrNull(idIdx) else null) ?: continue
                    result[uid] = if (tsIdx >= 0) c.getLongOrNull(tsIdx) else null
                }
            }
        } catch (expected: Exception) {
            Log.error(TAG, "localUidToEtag failed", expected)
        }
        return result
    }

    /** All local rows in relay form, tombstones included (todos soft-delete). */
    fun listLocal(context: Context, vaultId: String): List<RemoteTodo> {
        val out = mutableListOf<RemoteTodo>()
        var failed = false
        try {
            val cursor = context.contentResolver.query(
                todosUri(vaultId),
                arrayOf(COL_PAYLOAD_JSON),
                null,
                null,
                null,
            )
            if (cursor == null) {
                // Null cursor = provider cannot serve: failure, not empty.
                failed = true
            } else {
                cursor.use { c ->
                    val payloadIdx = c.getColumnIndex(COL_PAYLOAD_JSON)
                    while (c.moveToNext()) {
                        val payload = if (payloadIdx >= 0) c.getStringOrNull(payloadIdx) else null
                        if (!payload.isNullOrBlank()) {
                            fromRelayJson(payload)?.let { out += it }
                        }
                    }
                }
            }
        } catch (expected: Exception) {
            Log.error(TAG, "listLocal failed", expected)
            failed = true
        }
        lastListLocalOk = !failed
        return out
    }

    /** Idempotent upsert keyed on [RemoteTodo.uid]; preserves the relay identity + timestamp. */
    fun upsert(context: Context, vaultId: String, todo: RemoteTodo) {
        try {
            context.contentResolver.insert(
                upsertUri(vaultId),
                ContentValues().apply { put(COL_PAYLOAD_JSON, toRelayJson(todo)) },
            )
        } catch (expected: Exception) {
            Log.error(TAG, "upsert failed", expected)
        }
    }

    /** Bulk upsert for merge results: one IPC call, applied in one provider transaction. */
    fun upsertAll(context: Context, vaultId: String, todos: List<RemoteTodo>) {
        if (todos.isEmpty()) return
        try {
            context.contentResolver.bulkInsert(
                upsertUri(vaultId),
                todos.map { ContentValues().apply { put(COL_PAYLOAD_JSON, toRelayJson(it)) } }.toTypedArray(),
            )
        } catch (expected: Exception) {
            Log.error(TAG, "upsertAll failed", expected)
        }
    }

    /**
     * Applies a pulled relay tombstone (or a local delete ack) by tombstoning
     * the row: non-null selection + syncId deletes (tombstones) exactly one row.
     */
    fun delete(context: Context, vaultId: String, uid: String) {
        try {
            context.contentResolver.delete(
                todosUri(vaultId),
                "$COL_SYNC_ID = ?",
                arrayOf(uid),
            )
        } catch (expected: Exception) {
            Log.error(TAG, "delete failed", expected)
        }
    }

    /** Bulk tombstone for merge garbage collection. */
    fun deleteAll(context: Context, vaultId: String, uids: List<String>) {
        for (uid in uids) delete(context, vaultId, uid)
    }

    /**
     * Removes every todo row (and snapshots) for this vault from the app
     * provider. Used when the user turns todo sync off; the vault itself
     * stays, so a later re-enable repopulates it from a fresh pull.
     */
    fun purge(context: Context, accountName: String, vaultId: String) {
        for (table in listOf(PATH_TODOS, PATH_SNAPSHOTS)) {
            try {
                context.contentResolver.delete(purgeUri(vaultId, table), null, null)
            } catch (expected: Exception) {
                Log.error(TAG, "purge failed for $table", expected)
            }
        }
    }

    /** Back-compat overload for account-scoped callers that track a single vault per account. */
    fun purge(context: Context, accountName: String) {
        purge(context, accountName, accountName)
    }

    /**
     * Rows changed locally since [since] (via `?since=`), tombstones included
     * (todos soft-delete, so deletes propagate as rows with `deleted=1`).
     */
    fun getLocalChanges(context: Context, vaultId: String, since: Long = 0L): List<LocalTodoChange> {
        val changes = mutableListOf<LocalTodoChange>()
        try {
            context.contentResolver.query(
                queryUri(vaultId).buildUpon()
                    .appendQueryParameter(PARAM_SINCE, since.toString()).build(),
                arrayOf(COL_SYNC_ID, COL_UPDATED_AT, COL_DELETED, COL_PAYLOAD_JSON),
                null,
                null,
                null,
            )?.use { c ->
                val idIdx = c.getColumnIndex(COL_SYNC_ID)
                val tsIdx = c.getColumnIndex(COL_UPDATED_AT)
                val delIdx = c.getColumnIndex(COL_DELETED)
                val payloadIdx = c.getColumnIndex(COL_PAYLOAD_JSON)
                while (c.moveToNext()) {
                    val uid = (if (idIdx >= 0) c.getStringOrNull(idIdx) else null) ?: continue
                    val deleted = delIdx >= 0 && c.getInt(delIdx) == 1
                    val payload = if (payloadIdx >= 0) c.getStringOrNull(payloadIdx) else null
                    changes += LocalTodoChange(
                        syncId = uid,
                        updatedAt = if (tsIdx >= 0) c.getLong(tsIdx) else 0L,
                        deleted = deleted,
                        todo = if (deleted || payload.isNullOrBlank()) {
                            null
                        } else {
                            fromRelayJson(payload)
                        },
                    )
                }
            }
        } catch (expected: Exception) {
            Log.error(TAG, "getLocalChanges failed", expected)
        }
        return changes
    }
}
