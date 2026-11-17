package com.vayunmathur.everysync.data.sink

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.vayunmathur.everysync.data.RemoteNote
import com.vayunmathur.everysync.sync.adapters.NoteKind
import com.vayunmathur.library.log.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A locally-changed note row detected via the app provider's timestamp watermark. */
data class LocalNoteChange(
    val syncId: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val note: RemoteNote?,
)

/**
 * Reads/writes note records through the notes app's relay ContentProvider
 * (owned by task 3 — the `notes/` app). The contract mirrors the provider
 * exactly:
 *
 * - authority: `com.vayunmathur.notes.relay` (signature-protected)
 * - URIs: base table URIs (`.../vault/<id>/notes`, `.../snapshots`) plus the
 *   op-suffixed forms (`.../notes/querySince`, `.../notes/upsertRaw`,
 *   `.../notes/deleteByVault`, same for snapshots). The vault id segment is
 *   accepted but ignored: the app holds a single vault.
 * - query: `?since=<ms>` query parameter first, `selectionArgs[0]` as
 *   fallback, for deltas (tombstones included — notes soft-delete); no bound
 *   lists the current full state.
 * - `payloadJson` is the **envelope JSON** ([RemoteNote] bytes via
 *   [NoteKind.toJson] — the exact relay-log bytes, with `uid` identity).
 *   The provider emits envelopes and accepts envelopes primarily; the native
 *   row shape is accepted as fallback. Reads accept both via [fromPayload]
 *   (envelope first, native fallback) so no row is ever silently dropped.
 * - upsert: `insert`/`update` with `ContentValues(payloadJson)` carrying the
 *   **envelope JSON**, preserving the pulled timestamp and relay identity
 *   (`uid` -> `syncId`). A `deleted` envelope tombstones the row instead of
 *   hard-deleting it, so the deletion propagates via the next `querySince`.
 * - delete: a non-null selection with `selectionArgs[0]` = syncId tombstones
 *   one row; `deleteByVault` (or selection-less base delete) purges the whole
 *   table *and* its snapshots.
 * - columns: `syncId`, `updatedAt`, `deleted` (0/1), `payloadJson`,
 *   `contentHash` (payload SHA-256 hex; the merge driver recomputes
 *   canonical hashes itself via [NoteKind] and ignores this column on read).
 */
object NoteSink {
    private const val TAG = "NoteSink"

    /**
     * Health of the last [listLocal] read: false when the query failed or
     * returned a null cursor. Consulted by `NoteRelayStore.listLocalOk`.
     */
    @Volatile var lastListLocalOk: Boolean = true
        private set

    const val AUTHORITY = "com.vayunmathur.notes.relay"
    const val PATH_NOTES = "notes"
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

    private val json = Json { ignoreUnknownKeys = true }

    fun notesUri(vaultId: String): Uri =
        "content://$AUTHORITY/vault/$vaultId/$PATH_NOTES".toUri()

    fun snapshotsUri(vaultId: String): Uri =
        "content://$AUTHORITY/vault/$vaultId/$PATH_SNAPSHOTS".toUri()

    fun queryUri(vaultId: String): Uri =
        notesUri(vaultId).buildUpon().appendPath(OP_QUERY_SINCE).build()

    fun upsertUri(vaultId: String): Uri =
        notesUri(vaultId).buildUpon().appendPath(OP_UPSERT_RAW).build()

    fun purgeUri(vaultId: String, table: String): Uri =
        "content://$AUTHORITY/vault/$vaultId/$table".toUri()
            .buildUpon().appendPath(OP_DELETE_BY_VAULT).build()

    /** Relay envelope for [note] (adapter bytes — what the relay log carries). */
    fun toRelayJson(note: RemoteNote): String = NoteKind.toJson(note)

    /** Native row JSON for [note] (what the provider persists verbatim). */
    fun toNativeJson(note: RemoteNote): String =
        json.encodeToString(
            NativeNote(
                title = note.title,
                content = note.content,
                position = note.position,
                blocks = note.blocksJson,
                syncId = note.uid,
                updatedAt = note.updatedAt,
                deleted = note.deleted,
            ),
        )

    /**
     * Parses a `payloadJson` value back into relay form. Accepts the envelope
     * shape first (what the provider emits) and the native row shape as
     * fallback; null skips corrupt/foreign rows.
     */
    fun fromPayload(payload: String): RemoteNote? {
        fromRelayJson(payload)?.let { return it }
        return fromNativeJson(payload)
    }

    /** Parses a native row payload; null skips non-native rows. */
    fun fromNativeJson(payload: String): RemoteNote? = runCatching {
        val n = json.decodeFromString<NativeNote>(payload)
        RemoteNote(
            uid = n.syncId,
            title = n.title,
            content = n.content,
            blocksJson = n.blocks,
            position = n.position,
            deleted = n.deleted,
            updatedAt = n.updatedAt,
        )
    }.getOrNull()?.takeIf { it.uid.isNotBlank() }

    /** Parses an envelope payload back; null skips corrupt/foreign rows. */
    fun fromRelayJson(payload: String): RemoteNote? =
        NoteKind.fromJson(payload)?.takeIf { it.uid.isNotBlank() }

    /** Map of relay syncId → updatedAt currently present locally for this vault. */
    fun localUidToEtag(context: Context, vaultId: String): Map<String, Long?> {
        val result = mutableMapOf<String, Long?>()
        // Full state, live rows only (tombstones are filtered — the merge
        // reads them via listLocal).
        try {
            context.contentResolver.query(
                notesUri(vaultId),
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

    /** All local rows in relay form, tombstones included (notes soft-delete). */
    fun listLocal(context: Context, vaultId: String): List<RemoteNote> {
        val out = mutableListOf<RemoteNote>()
        var failed = false
        try {
            val cursor = context.contentResolver.query(
                notesUri(vaultId),
                arrayOf(COL_PAYLOAD_JSON),
                null,
                null,
                null,
            )
            if (cursor == null) {
                // Null cursor = provider cannot serve: failure, not empty.
                failed = true
            } else {
                cursor.use { c -> out += readNoteRows(c) }
            }
        } catch (expected: Exception) {
            Log.error(TAG, "listLocal failed", expected)
            failed = true
        }
        lastListLocalOk = !failed
        return out
    }

    private fun readNoteRows(cursor: android.database.Cursor): List<RemoteNote> {
        val out = mutableListOf<RemoteNote>()
        val payloadIdx = cursor.getColumnIndex(COL_PAYLOAD_JSON)
        while (cursor.moveToNext()) {
            val payload = if (payloadIdx >= 0) cursor.getStringOrNull(payloadIdx) else null
            if (!payload.isNullOrBlank()) {
                fromPayload(payload)?.let { out += it }
            }
        }
        return out
    }

    /** Idempotent upsert keyed on [RemoteNote.uid]; preserves the relay identity + timestamp. */
    fun upsert(context: Context, vaultId: String, note: RemoteNote) {
        try {
            context.contentResolver.insert(
                upsertUri(vaultId),
                ContentValues().apply { put(COL_PAYLOAD_JSON, toRelayJson(note)) },
            )
        } catch (expected: Exception) {
            Log.error(TAG, "upsert failed", expected)
        }
    }

    /** Bulk upsert for merge results; one IPC call per row like the single form. */
    fun upsertAll(context: Context, vaultId: String, notes: List<RemoteNote>) {
        for (note in notes) upsert(context, vaultId, note)
    }

    /**
     * Applies a pulled relay tombstone (or a local delete ack) by tombstoning
     * the row: non-null selection + syncId deletes (tombstones) exactly one row.
     */
    fun delete(context: Context, vaultId: String, uid: String) {
        try {
            context.contentResolver.delete(
                notesUri(vaultId),
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
     * Removes every note row (and snapshots) for this vault from the app
     * provider. Used when the user turns note sync off; the vault itself
     * stays, so a later re-enable repopulates it from a fresh pull.
     */
    // Intentional no-op / account-parity parameter: kept so all sinks share one call shape.
    @Suppress("UnusedParameter")
    fun purge(context: Context, accountName: String, vaultId: String) {
        for (table in listOf(PATH_NOTES, PATH_SNAPSHOTS)) {
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
     * (notes soft-delete, so deletes propagate as rows with `deleted=1`).
     */
    fun getLocalChanges(context: Context, vaultId: String, since: Long = 0L): List<LocalNoteChange> {
        val changes = mutableListOf<LocalNoteChange>()
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
                    changes += LocalNoteChange(
                        syncId = uid,
                        updatedAt = if (tsIdx >= 0) c.getLong(tsIdx) else 0L,
                        deleted = deleted,
                        note = if (deleted || payload.isNullOrBlank()) {
                            null
                        } else {
                            fromPayload(payload)
                        },
                    )
                }
            }
        } catch (expected: Exception) {
            Log.error(TAG, "getLocalChanges failed", expected)
        }
        return changes
    }

    /**
     * Marks locally-pushed rows clean after a successful relay append. No-op:
     * rows applied via `upsertRaw` keep their remote `updatedAt`, which sits
     * at or below the push watermark, so fresh pulls never look dirty.
     */
    // Intentional no-op / account-parity parameter: kept so all sinks share one call shape.
    @Suppress("UnusedParameter")
    fun clearDirty(context: Context, vaultId: String, syncId: String) {
        // Timestamp-watermark providers need no explicit clean bit; kept for
        // API parity with ContactsSink/CalendarSink.
    }

    /**
     * Native `Note` JSON shape (mirrors the notes app's model without
     * depending on its module: `id` is per-device so it is never synced).
     */
    @Serializable
    internal data class NativeNote(
        val title: String = "",
        val content: String = "",
        val position: Double = 0.0,
        val blocks: String? = null,
        val syncId: String = "",
        val updatedAt: Long = 0L,
        val deleted: Boolean = false,
    )
}
