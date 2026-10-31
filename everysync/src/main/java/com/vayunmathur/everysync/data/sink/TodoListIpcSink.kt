package com.vayunmathur.everysync.data.sink

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.core.database.getStringOrNull
import androidx.core.net.toUri
import com.vayunmathur.everysync.data.RemoteTodoList
import com.vayunmathur.everysync.sync.adapters.TodoListKind
import com.vayunmathur.library.log.Log

/**
 * Reads/writes todo lists through the todo app's relay ContentProvider — the list half of
 * [TodoIpcSink], same contract on `.../vault/<id>/lists[/querySince|upsertRaw|deleteByVault]`.
 * A todo build without lists answers these URIs with a null cursor, which is treated as a
 * failed read so the merge aborts rather than deleting anything.
 */
object TodoListIpcSink {
    private const val TAG = "TodoListIpcSink"
    private const val PATH_LISTS = "lists"

    @Volatile var lastListLocalOk: Boolean = true
        private set

    fun listsUri(vaultId: String): Uri =
        "content://${TodoIpcSink.AUTHORITY}/vault/$vaultId/$PATH_LISTS".toUri()

    /** False against a todo build that predates lists; the list merge is skipped then. */
    fun isSupported(context: Context, vaultId: String): Boolean =
        runCatching { context.contentResolver.getType(listsUri(vaultId)) != null }.getOrDefault(false)

    private fun upsertUri(vaultId: String): Uri =
        listsUri(vaultId).buildUpon().appendPath(TodoIpcSink.OP_UPSERT_RAW).build()

    fun listLocal(context: Context, vaultId: String): List<RemoteTodoList> {
        val out = mutableListOf<RemoteTodoList>()
        var failed = false
        try {
            val cursor = context.contentResolver.query(listsUri(vaultId), arrayOf(TodoIpcSink.COL_PAYLOAD_JSON), null, null, null)
            if (cursor == null) {
                failed = true
            } else {
                cursor.use { c ->
                    val idx = c.getColumnIndex(TodoIpcSink.COL_PAYLOAD_JSON)
                    while (c.moveToNext()) {
                        val payload = if (idx >= 0) c.getStringOrNull(idx) else null
                        if (!payload.isNullOrBlank()) {
                            TodoListKind.fromJson(payload)?.takeIf { it.uid.isNotBlank() }?.let { out += it }
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

    fun upsertAll(context: Context, vaultId: String, lists: List<RemoteTodoList>) {
        if (lists.isEmpty()) return
        try {
            context.contentResolver.bulkInsert(
                upsertUri(vaultId),
                lists.map { ContentValues().apply { put(TodoIpcSink.COL_PAYLOAD_JSON, TodoListKind.toJson(it)) } }
                    .toTypedArray(),
            )
        } catch (expected: Exception) {
            Log.error(TAG, "upsertAll failed", expected)
        }
    }

    fun deleteAll(context: Context, vaultId: String, uids: List<String>) {
        for (uid in uids) {
            try {
                context.contentResolver.delete(listsUri(vaultId), "${TodoIpcSink.COL_SYNC_ID} = ?", arrayOf(uid))
            } catch (expected: Exception) {
                Log.error(TAG, "delete failed", expected)
            }
        }
    }

    fun purge(context: Context, vaultId: String) {
        try {
            context.contentResolver.delete(TodoIpcSink.purgeUri(vaultId, PATH_LISTS), null, null)
        } catch (expected: Exception) {
            Log.error(TAG, "purge failed", expected)
        }
    }
}
