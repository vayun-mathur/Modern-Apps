package com.vayunmathur.notes.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.notes.domain.withTagRenamed
import com.vayunmathur.notes.domain.withoutTag
import kotlinx.coroutines.flow.Flow

class NotesRepository private constructor(context: Context) :
    RoomRepository<NoteDatabase>(context, NoteDatabase::class, DB_NAME) {

    private val dao get() = db.noteDao()
    private val snapshotDao get() = db.syncSnapshotDao()

    val notes: Flow<List<Note>> get() = dao.getAllFlow()
    fun noteByIdFlow(id: Long): Flow<Note?> = dao.getByIdFlow(id)

    suspend fun getAll(): List<Note> = dao.getAll()
    suspend fun getBySyncId(syncId: String): Note? = dao.getBySyncId(syncId)
    suspend fun getUpdatedSince(since: Long): List<Note> = dao.getUpdatedSince(since)

    /** Local write: bumps updatedAt so the change is picked up by sync. */
    suspend fun upsert(value: Note): Long = dao.upsert(value)

    /** Writes [value] verbatim. Only the relay sync uses this, to preserve a pulled timestamp. */
    suspend fun upsertRaw(value: Note): Long = dao.upsertRaw(value)
    suspend fun upsertAllRaw(values: List<Note>) = dao.upsertAllRaw(values)

    /** Local delete: tombstones instead of hard-deleting so the relay sync can propagate it. */
    suspend fun delete(value: Note): Int {
        dao.upsert(value.copy(deleted = true))
        return 1
    }
    suspend fun tombstoneBySyncId(syncId: String): Int {
        val existing = dao.getBySyncId(syncId) ?: return 0
        if (!existing.deleted) dao.upsert(existing.copy(deleted = true))
        return 1
    }
    suspend fun deleteBySyncId(syncId: String): Int = dao.deleteBySyncId(syncId)
    suspend fun upsertAll(values: List<Note>) = dao.upsertAll(values)

    /**
     * Renames tag [from] to [to] on every live note (case-insensitive match). A
     * note that already carries [to] merges the two. Returns how many notes
     * changed. Local writes, so updatedAt is bumped and the change syncs.
     */
    suspend fun renameTag(from: String, to: String): Int = rewriteTags { withTagRenamed(it, from, to) }

    /** Removes [tag] from every live note. Notes themselves are never deleted. */
    suspend fun deleteTag(tag: String): Int = rewriteTags { withoutTag(it, tag) }

    private suspend fun rewriteTags(transform: (List<String>) -> List<String>): Int {
        val changed = dao.getAll().mapNotNull { note ->
            val tags = transform(note.tags)
            if (tags != note.tags) note.copy(tags = tags) else null
        }
        // One batch upsert (a single Room transaction) for every changed note.
        if (changed.isNotEmpty()) dao.upsertAll(changed)
        return changed.size
    }

    /** Removes every note (including tombstones). Used when a relay vault is purged. */
    suspend fun purge() = dao.deleteAll()

    suspend fun getAllSnapshots(): List<SyncSnapshot> = snapshotDao.getAll()
    suspend fun upsertSnapshot(snapshot: SyncSnapshot) = snapshotDao.upsert(snapshot)
    suspend fun deleteSnapshotBySyncId(syncId: String) = snapshotDao.deleteBySyncId(syncId)
    suspend fun deleteAllSnapshots() = snapshotDao.deleteAll()

    /**
     * Rewrites image block references after [com.vayunmathur.notes.platform.NoteImageStore.migrateToContentAddressed].
     * Uses raw writes so the renames don't bump updatedAt (a file rename is not a content edit).
     */
    suspend fun migrateImageReferences(renames: Map<String, String>) {
        if (renames.isEmpty()) return
        val updated = dao.getAllIncludingDeleted().mapNotNull { note ->
            val body = note.body()
            var changed = false
            val blocks = body.blocks.map { block ->
                val replacement = (block as? NoteBlock.Image)?.let { renames[it.fileName] }
                if (replacement != null) {
                    changed = true
                    block.copy(fileName = replacement)
                } else {
                    block
                }
            }
            if (changed) note.withBody(NoteBody(blocks)) else null
        }
        if (updated.isNotEmpty()) dao.upsertAllRaw(updated)
    }

    /**
     * One-time migration: reads notes from the legacy "passwords-db" file (the
     * app's former database name), then deletes it. Uses an uncached auxiliary
     * open so it is independent of the primary DB's lifecycle/order.
     */
    suspend fun readAndClearLegacyNotes(): List<Note> {
        if (!appContext.getDatabasePath(LEGACY_DB_NAME).exists()) return emptyList()
        val legacy = openAuxiliary(LEGACY_DB_NAME)
        // Delete only after a successful read: deleting in a finally would
        // destroy the only copy if getAll() throws (corrupt/encrypted legacy
        // file), and the caller upserts after this returns, so a failed read
        // must leave the file in place for a later retry.
        val notes = try {
            legacy.noteDao().getAll()
        } finally {
            legacy.close()
        }
        appContext.deleteDatabase(LEGACY_DB_NAME)
        return notes
    }

    companion object {
        private const val LEGACY_DB_NAME = "passwords-db"

        @Volatile private var instance: NotesRepository? = null
        fun get(context: Context): NotesRepository =
            instance ?: synchronized(this) {
                instance ?: NotesRepository(context).also { instance = it }
            }
    }
}
