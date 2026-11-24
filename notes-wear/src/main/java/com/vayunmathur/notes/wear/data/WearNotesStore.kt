package com.vayunmathur.notes.wear.data

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.notes.wear.domain.WearNote
import com.vayunmathur.notes.wear.domain.WearNoteOp
import com.vayunmathur.notes.wear.domain.WearNotesProtocol
import com.vayunmathur.notes.wear.domain.WearNotesSnapshot
import com.vayunmathur.notes.wear.domain.WearOpKind
import com.vayunmathur.notes.wear.domain.mergeWearSnapshot
import com.vayunmathur.notes.wear.domain.reflectedOps
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/**
 * The watch's local truth: the last phone snapshot merged with this watch's
 * pending ops, persisted as JSON in app-private storage so notes read,
 * create and edit fully offline (phone absent, process cold-started).
 * Pending ops are persisted separately and survive until a phone snapshot
 * reflects them (see [WearNotesProtocol] for the LWW policy).
 */
class WearNotesStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WearNotesStore"

        @Volatile
        private var instance: WearNotesStore? = null

        /** The process-wide store (bridge and UI must agree on one). */
        fun get(context: Context): WearNotesStore = instance ?: synchronized(this) {
            instance ?: WearNotesStore(context.applicationContext).also { instance = it }
        }

        /** New watch-originated notes use the phone's syncId shape (32 hex chars). */
        fun newSyncId(): String = UUID.randomUUID().toString().replace("-", "")
    }

    private val snapshotFile = File(context.filesDir, "wear-notes-snapshot.json")
    private val pendingFile = File(context.filesDir, "wear-notes-pending.json")

    private val _notes = MutableStateFlow(loadNotes())
    private val _pendingOps = MutableStateFlow(loadPending())

    /** Local view: phone snapshot merged with pending watch ops, recent first. */
    val notes: StateFlow<List<WearNote>> = _notes.asStateFlow()

    /** Watch-originated ops not yet reflected by a phone snapshot. */
    val pendingOps: StateFlow<List<WearNoteOp>> = _pendingOps.asStateFlow()

    private var lastSnapshot: WearNotesSnapshot = loadSnapshot()

    /** Snapshot generation the local view is based on (for watch-state reporting). */
    val appliedGeneratedAtMillis: Long get() = lastSnapshot.generatedAtMillis

    /** Applies a phone snapshot: drops reflected ops, keeps winning pending ones. */
    fun setSnapshot(snapshot: WearNotesSnapshot) {
        lastSnapshot = snapshot
        val reflected = reflectedOps(snapshot, _pendingOps.value)
        if (reflected.isNotEmpty()) {
            _pendingOps.value = _pendingOps.value - reflected.toSet()
            persistPending()
        }
        _notes.value = mergeWearSnapshot(snapshot, _pendingOps.value)
        persistSnapshot()
        com.vayunmathur.notes.wear.sync.WearLinkBridge.publishWatchState(context)
    }

    /** Creates a note locally and queues its op; visible immediately, pending until reflected. */
    fun createNote(title: String, body: String, nowMillis: Long = System.currentTimeMillis()): WearNote {
        val note = WearNote(
            syncId = newSyncId(),
            title = title.take(WearNotesProtocol.MAX_TITLE_CHARS),
            body = body.take(WearNotesProtocol.MAX_BODY_CHARS),
            updatedAt = nowMillis,
        )
        queue(WearNoteOp(WearOpKind.UPSERT, note))
        return note
    }

    /**
     * Edits a text-only note locally and queues its op. The stamp is
     * `max(now, edited.updatedAt + 1)` so it beats the version it derives
     * from under LWW even with a skewed watch clock. Null when the note is
     * missing or not text-only (the UI offers no edit there either).
     */
    fun editNote(syncId: String, title: String, body: String, nowMillis: Long = System.currentTimeMillis()): WearNote? {
        val current = _notes.value.firstOrNull { it.syncId == syncId } ?: return null
        if (!current.textOnly) return null
        val edited = current.copy(
            title = title.take(WearNotesProtocol.MAX_TITLE_CHARS),
            body = body.take(WearNotesProtocol.MAX_BODY_CHARS),
            bodyTruncated = false,
            updatedAt = maxOf(nowMillis, current.updatedAt + 1),
        )
        queue(WearNoteOp(WearOpKind.UPSERT, edited))
        return edited
    }

    /** Deletes any note locally (tombstone op) and queues it; the note hides immediately. */
    fun deleteNote(syncId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val current = _notes.value.firstOrNull { it.syncId == syncId } ?: return false
        queue(
            WearNoteOp(
                WearOpKind.DELETE,
                current.copy(deleted = true, updatedAt = maxOf(nowMillis, current.updatedAt + 1)),
            ),
        )
        return true
    }

    private fun queue(op: WearNoteOp) {
        // One pending op per note: a newer local change supersedes the older one.
        _pendingOps.value = _pendingOps.value.filter { it.note.syncId != op.note.syncId } + op
        persistPending()
        _notes.value = mergeWearSnapshot(lastSnapshot, _pendingOps.value)
        com.vayunmathur.notes.wear.sync.WearLinkBridge.flushPending(context)
    }

    private fun loadSnapshot(): WearNotesSnapshot = runCatching {
        WearNotesProtocol.json.decodeFromString<WearNotesSnapshot>(snapshotFile.readText())
    }.getOrDefault(WearNotesSnapshot())

    private fun loadNotes(): List<WearNote> = mergeWearSnapshot(loadSnapshot(), loadPending())

    private fun loadPending(): List<WearNoteOp> = runCatching {
        WearNotesProtocol.json.decodeFromString<List<WearNoteOp>>(pendingFile.readText())
    }.getOrDefault(emptyList())

    private fun persistSnapshot() {
        runCatching { snapshotFile.writeText(WearNotesProtocol.json.encodeToString(lastSnapshot)) }
            .onFailure { Log.status(TAG, "persist snapshot failed: ${it.message}") }
    }

    private fun persistPending() {
        runCatching { pendingFile.writeText(WearNotesProtocol.json.encodeToString(_pendingOps.value)) }
            .onFailure { Log.status(TAG, "persist pending ops failed: ${it.message}") }
    }
}
