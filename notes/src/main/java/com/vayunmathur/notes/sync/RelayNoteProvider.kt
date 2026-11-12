package com.vayunmathur.notes.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import com.vayunmathur.library.log.Log
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NotesRepository
import com.vayunmathur.notes.data.SyncSnapshot
import com.vayunmathur.notes.domain.normalizeTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Relay IPC surface for the notes vault, consumed by the everysync app's
 * NoteSink (same process signature, different app).
 *
 * Exposes `content://com.vayunmathur.notes.relay/vault/<id>/notes` and
 * `.../snapshots`. The vault id segment is accepted but ignored: this app
 * Every row carries the relay columns `syncId, updatedAt, deleted,
 * payloadJson, contentHash`.
 *
 * `payloadJson` is [RelayNoteJson] — a field-for-field mirror of the relay
 * envelope (`RemoteNote` in everysync): the exact bytes the relay log carries
 * and the merge driver hashes. This provider must not depend on everysync, so
 * the shape is duplicated here; keep the field names in sync with
 * `RemoteNote` (both sides use `ignoreUnknownKeys`, but a renamed key would
 * silently drop data). `contentHash` is the merge-compatible canonical hash,
 * so LWW tiebreaks agree on both sides.
 *
 * Ops accept both the base table URIs and the op-suffixed URIs
 * (`.../notes/querySince?since=<ms>`, `.../notes/upsertRaw`,
 * `.../notes/deleteByVault`, same for snapshots):
 *  - `querySince`: rows with `updatedAt` past the `since` query parameter
 *    (falling back to `selectionArgs[0]`), including tombstones.
 *  - `upsertRaw`: insert/update with `payloadJson`, preserving the pulled
 *    `updatedAt` and relay identity (`uid` -> `syncId`). A payload with
 *    `deleted = true` tombstones the row instead of hard-deleting it, so the
 *    deletion propagates via the next `querySince`.
 *  - single delete (`syncId = ?` selection): tombstones the row.
 *  - `deleteByVault` (or selection-less delete): purge — drops the whole
 *    table *and* its snapshots.
 */
class RelayNoteProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.vayunmathur.notes.relay"
        const val PERMISSION = "com.vayunmathur.notes.permission.RELAY"

        const val COL_SYNC_ID = "syncId"
        const val COL_UPDATED_AT = "updatedAt"
        const val COL_DELETED = "deleted"
        const val COL_PAYLOAD_JSON = "payloadJson"
        const val COL_CONTENT_HASH = "contentHash"

        val COLUMNS = arrayOf(COL_SYNC_ID, COL_UPDATED_AT, COL_DELETED, COL_PAYLOAD_JSON, COL_CONTENT_HASH)

        private const val PARAM_SINCE = "since"

        private const val CODE_NOTES = 1
        private const val CODE_SNAPSHOTS = 2
        private const val CODE_NOTES_QUERY = 11
        private const val CODE_NOTES_UPSERT = 21
        private const val CODE_SNAPSHOTS_UPSERT = 22
        private const val CODE_NOTES_PURGE = 31
        private const val CODE_SNAPSHOTS_PURGE = 32

        private const val TAG = "RelayNoteProvider"

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "vault/*/notes", CODE_NOTES)
            addURI(AUTHORITY, "vault/*/snapshots", CODE_SNAPSHOTS)
            addURI(AUTHORITY, "vault/*/notes/querySince", CODE_NOTES_QUERY)
            addURI(AUTHORITY, "vault/*/notes/upsertRaw", CODE_NOTES_UPSERT)
            addURI(AUTHORITY, "vault/*/snapshots/upsertRaw", CODE_SNAPSHOTS_UPSERT)
            addURI(AUTHORITY, "vault/*/notes/deleteByVault", CODE_NOTES_PURGE)
            addURI(AUTHORITY, "vault/*/snapshots/deleteByVault", CODE_SNAPSHOTS_PURGE)
        }

        private val json = Json { ignoreUnknownKeys = true }

        fun notesUri(vaultId: String): Uri =
            Uri.parse("content://$AUTHORITY/vault/$vaultId/notes")

        fun snapshotsUri(vaultId: String): Uri =
            Uri.parse("content://$AUTHORITY/vault/$vaultId/snapshots")
    }

    private fun repo(): NotesRepository {
        val ctx = context ?: error("RelayNoteProvider context null")
        return NotesRepository.get(ctx)
    }

    override fun onCreate(): Boolean = true

    // The signature permission only proves "signed by the Modern Apps key", which
    // every app in the repo is. Narrow that to the everysync package as well.
    private fun enforceRelayCaller() {
        if (Binder.getCallingUid() == Process.myUid()) return
        val caller = callingPackage
        if (caller != "com.vayunmathur.everysync") {
            throw SecurityException("RelayNoteProvider: caller $caller is not everysync")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        enforceRelayCaller()
        // querySince: `?since=<ms>` query parameter first, selectionArgs[0] as fallback.
        val since = uri.getQueryParameter(PARAM_SINCE)?.toLongOrNull()
            ?: selectionArgs?.firstOrNull()?.toLongOrNull()
            ?: 0L
        return runBlocking(Dispatchers.IO) {
            try {
                when (uriMatcher.match(uri)) {
                    CODE_NOTES, CODE_NOTES_QUERY -> cursorFromNotes(repo().getUpdatedSince(since))
                    CODE_SNAPSHOTS -> cursorFromSnapshots(repo().getAllSnapshots())
                    else -> null
                }
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "query failed $uri", e)
                null
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "query failed $uri", e)
                null
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceRelayCaller()
        val v = values ?: return null
        return runBlocking(Dispatchers.IO) {
            try {
                when (uriMatcher.match(uri)) {
                    CODE_NOTES, CODE_NOTES_UPSERT -> applyRelayJson(v)
                    CODE_SNAPSHOTS, CODE_SNAPSHOTS_UPSERT -> upsertSnapshot(v)
                    else -> null
                }?.let { Uri.withAppendedPath(uri, it) }
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "insert failed $uri", e)
                null
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "insert failed $uri", e)
                null
            }
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        enforceRelayCaller()
        val v = values ?: return 0
        return runBlocking(Dispatchers.IO) {
            try {
                when (uriMatcher.match(uri)) {
                    CODE_NOTES, CODE_NOTES_UPSERT -> if (applyRelayJson(v) != null) 1 else 0
                    CODE_SNAPSHOTS, CODE_SNAPSHOTS_UPSERT -> if (upsertSnapshot(v) != null) 1 else 0
                    else -> 0
                }
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "update failed $uri", e)
                0
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "update failed $uri", e)
                0
            }
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceRelayCaller()
        return runBlocking(Dispatchers.IO) {
            try {
                val repo = repo()
                when (uriMatcher.match(uri)) {
                    CODE_NOTES_PURGE -> {
                        repo.purge()
                        repo.deleteAllSnapshots()
                        0
                    }
                    CODE_SNAPSHOTS_PURGE -> {
                        repo.deleteAllSnapshots()
                        0
                    }
                    // Single delete (`syncId = ?`): tombstone the row so the
                    // deletion propagates via querySince. Selection-less base
                    // delete is the legacy purge spelling.
                    CODE_NOTES -> {
                        val syncId = if (selection != null) selectionArgs?.firstOrNull() else null
                        if (syncId != null) {
                            repo.tombstoneBySyncId(syncId)
                        } else {
                            repo.purge()
                            repo.deleteAllSnapshots()
                            0
                        }
                    }
                    CODE_SNAPSHOTS -> {
                        val syncId = selectionArgs?.firstOrNull()
                        if (syncId != null) {
                            repo.deleteSnapshotBySyncId(syncId)
                            1
                        } else {
                            repo.deleteAllSnapshots()
                            0
                        }
                    }
                    else -> 0
                }
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "delete failed $uri", e)
                0
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "delete failed $uri", e)
                0
            }
        }
    }

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        CODE_NOTES, CODE_NOTES_QUERY -> "vnd.android.cursor.dir/vnd.com.vayunmathur.notes.note"
        CODE_SNAPSHOTS -> "vnd.android.cursor.dir/vnd.com.vayunmathur.notes.snapshot"
        else -> null
    }

    /**
     * Applies one pulled relay record. `uid` is the sync identity. A `deleted`
     * payload tombstones the row (never hard-deletes) so the deletion
     * propagates. Rebinds onto the existing row id so a pull updates in place
     * instead of duplicating.
     */
    private suspend fun applyRelayJson(v: ContentValues): String? {
        val payload = v.getAsString(COL_PAYLOAD_JSON) ?: return null
        val repo = repo()
        // Serializer overload (not the inline reified one) dodges the
        // decodeFromString overload ambiguity on this Kotlin version.
        val remote: RelayNoteJson =
            runCatching { json.decodeFromString(RelayNoteJson.serializer(), payload) }.getOrNull()
                ?: return null
        if (remote.uid.isBlank()) return null
        val existing = repo.getBySyncId(remote.uid)
        repo.upsertRaw(remote.toNote(existing?.id ?: 0L))
        return remote.uid
    }

    private suspend fun upsertSnapshot(v: ContentValues): String? {
        val syncId = v.getAsString(COL_SYNC_ID) ?: return null
        repo().upsertSnapshot(
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

    private fun cursorFromNotes(notes: List<Note>): MatrixCursor {
        val c = MatrixCursor(COLUMNS)
        for (n in notes) {
            val relay = n.toRelayJson()
            val payload = json.encodeToString(relay)
            c.addRow(
                arrayOf<Any?>(
                    n.syncId,
                    n.updatedAt,
                    if (n.deleted) 1 else 0,
                    payload,
                    relayContentHash(relay),
                ),
            )
        }
        return c
    }

    private fun cursorFromSnapshots(snapshots: List<SyncSnapshot>): MatrixCursor {
        val c = MatrixCursor(COLUMNS)
        for (s in snapshots) {
            c.addRow(arrayOf<Any?>(s.syncId, s.localUpdatedAt, 0, "", s.contentHash))
        }
        return c
    }

    private fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * Merge-compatible content hash: canonical envelope (`uid`, `position`,
     * `updatedAt`, `deleted` stripped) plus the deleted flag, SHA-256 hexed —
     * the same bytes the relay merge driver hashes, so LWW tiebreaks agree on
     * both sides.
     */
    private fun relayContentHash(e: RelayNoteJson): String {
        val canonical = json.encodeToString(
            e.copy(uid = "", position = 0.0, updatedAt = 0L, deleted = false),
        ) + "\u0000deleted=${e.deleted}"
        return sha256Hex(canonical)
    }
}

/**
 * Relay envelope for one note record. Field-for-field mirror of everysync's
 * `RemoteNote` (the bytes the relay log carries): title + content + blocks
 * JSON; image bytes ride the blob channel and are referenced by file name.
 *
 * notes/ must not depend on everysync, so the shape is duplicated here.
 * Both sides decode with `ignoreUnknownKeys`, but keep the names in sync —
 * a renamed key here would silently drop data on the relay side.
 */
@Serializable
internal data class RelayNoteJson(
    /** Relay sync identity (opaque 32-char id); maps to [Note.syncId]. */
    val uid: String = "",
    val title: String = "",
    val content: String = "",
    /** Serialized NoteBody, or null for legacy text-only notes. */
    val blocksJson: String? = null,
    val position: Double = 0.0,
    val deleted: Boolean = false,
    /** Last local-or-remote modification (epoch ms); drives relay LWW. */
    val updatedAt: Long = 0L,
    /**
     * User tags (#913). Last on purpose and empty by default: `json` does not
     * encode defaults, so an untagged note's envelope — and so its content
     * hash — is byte-identical to the pre-tags shape and nothing re-syncs.
     * Must stay in the same position as `RemoteNote.tags` (the hash is over
     * the encoded bytes, which follow declaration order).
     */
    val tags: List<String> = emptyList(),
)

internal fun RelayNoteJson.toNote(id: Long): Note = Note(
    id = id,
    title = title,
    content = content,
    position = position,
    blocks = blocksJson,
    syncId = uid,
    updatedAt = updatedAt,
    deleted = deleted,
    // Normalise on receive: another device's build is not trusted to have
    // applied the same rules (length, count, case-insensitive dedupe).
    tags = normalizeTags(tags),
)

// Envelope mapper: native <-> relay-log bytes.
internal fun Note.toRelayJson(): RelayNoteJson = RelayNoteJson(
    uid = syncId,
    title = title,
    content = content,
    blocksJson = blocks,
    position = position,
    deleted = deleted,
    updatedAt = updatedAt,
    tags = tags,
)
