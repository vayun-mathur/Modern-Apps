package com.vayunmathur.notes.sync

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.data.NoteBody
import com.vayunmathur.notes.data.NotesRepository
import com.vayunmathur.notes.data.body
import com.vayunmathur.notes.data.withBody
import com.vayunmathur.notes.domain.WearNote
import com.vayunmathur.notes.domain.WearNoteOp
import com.vayunmathur.notes.domain.WearNotesProtocol
import com.vayunmathur.notes.domain.WearNotesSnapshot
import com.vayunmathur.notes.domain.WearOpKind
import com.vayunmathur.notes.domain.WearWatchState
import com.vayunmathur.notes.domain.buildWearSnapshot
import com.vayunmathur.notes.domain.wearNoteWins
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/** Phone-side wear status; absence of a watch is a normal state, never an error. */
data class WearNotesSyncState(
    val companionPresent: Boolean = false,
    val lastPublishedAtMillis: Long? = null,
    val lastPublishedNoteCount: Int = 0,
    val lastWatchPendingOps: Int = 0,
)

/**
 * Phone side of the wear sync over WearLink (RFCOMM, :library:wearlink):
 * presence is a completed handshake, the recent-notes snapshot is a data
 * item, and watch-originated ops are messages handled here while this
 * process lives. There is no Play-services listener: an op sent while the
 * phone app is dead is not delivered (WearLink has no store-and-forward);
 * the watch keeps it pending and resends on the next live session.
 *
 * Watch ops apply under the protocol's last-writer-wins policy
 * ([wearNoteWins]) against the phone row with the same syncId, preserving
 * the op's `updatedAt` via [NotesRepository.upsertRaw] so both sides
 * compare the same stamps. An UPSERT for a note whose phone body has
 * image/ink blocks is refused (the watch never offers it; this is the
 * belt-and-braces check) — a text replacement would destroy those blocks.
 * DELETE tombstones any note, matching [NotesRepository.delete] semantics
 * so the deletion also propagates over the relay sync.
 */
class WearNotesSync private constructor(context: Context) {

    companion object {
        private const val TAG = "WearNotesSync"

        @Volatile
        private var instance: WearNotesSync? = null

        /** The process-wide sync (activity/view-model must agree on one). */
        fun get(context: Context): WearNotesSync = instance ?: synchronized(this) {
            instance ?: WearNotesSync(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository get() = NotesRepository.get(appContext)

    private val _state = MutableStateFlow(WearNotesSyncState())
    val state: StateFlow<WearNotesSyncState> = _state.asStateFlow()

    private val listener = object : WearLinkListener {
        override fun onData(path: String, body: ByteArray) {
            if (path != WearNotesProtocol.WATCH_STATE_PATH) return
            val watchState = runCatching {
                WearNotesProtocol.json.decodeFromString<WearWatchState>(body.decodeToString())
            }.getOrNull() ?: return
            _state.update { it.copy(lastWatchPendingOps = watchState.pendingOps) }
        }

        override fun onMessage(path: String, body: ByteArray) {
            when (path) {
                WearNotesProtocol.REQUEST_SYNC_PATH -> scope.launch { publishSnapshot() }
                WearNotesProtocol.OP_PATH -> {
                    val op = runCatching {
                        WearNotesProtocol.json.decodeFromString<WearNoteOp>(body.decodeToString())
                    }.getOrNull()
                    if (op == null) {
                        Log.status(TAG, "ignoring malformed wear op")
                        return
                    }
                    scope.launch {
                        if (applyWearOp(op)) publishSnapshot()
                    }
                }
            }
        }
    }

    private val node = WearLinkNode(appContext, WearNotesProtocol.CAPABILITY, listener)
    private var started = false
    private var pendingPublish: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            node.state.collect { linkState ->
                val present = linkState is WearLinkState.Connected
                val wasPresent = _state.value.companionPresent
                _state.update { it.copy(companionPresent = present) }
                if (present && !wasPresent) publishSnapshot()
            }
        }
        // Phone-side edits/deletes republish (debounced) while a watch is present.
        scope.launch {
            repository.notes.collect { onNotesChanged() }
        }
        node.start()
    }

    /** Manual sync; publishes even when presence is stale/unknown. */
    fun syncNow() {
        scope.launch { publishSnapshot() }
    }

    private fun onNotesChanged() {
        if (!_state.value.companionPresent) return
        pendingPublish?.cancel()
        pendingPublish = scope.launch {
            delay(1_000)
            publishSnapshot()
        }
    }

    /** Build the bounded snapshot from Room and publish it as a data item. */
    suspend fun publishSnapshot() {
        val snapshot = buildWearSnapshot(
            candidates = repository.getAll().map { it.toWearNote() },
            generatedAtMillis = System.currentTimeMillis(),
        )
        val sent = node.putData(
            WearNotesProtocol.SNAPSHOT_PATH,
            WearNotesProtocol.json.encodeToString(snapshot).encodeToByteArray(),
        )
        if (sent) {
            _state.update {
                it.copy(
                    lastPublishedAtMillis = snapshot.generatedAtMillis,
                    lastPublishedNoteCount = snapshot.notes.size,
                )
            }
        } else {
            Log.status(TAG, "publish snapshot not delivered (no live WearLink session)")
        }
    }

    /**
     * Applies one watch op under LWW. Returns true when the phone store
     * changed (caller republishes). Internal for unit tests.
     */
    internal suspend fun applyWearOp(op: WearNoteOp): Boolean {
        val incoming = op.note
        if (incoming.syncId.isBlank()) return false
        val existing = repository.getBySyncId(incoming.syncId)
        if (existing != null) {
            val incumbent = existing.toWearNote()
            if (!wearNoteWins(incoming, incumbent)) {
                Log.status(TAG, "wear op for ${incoming.syncId} loses LWW, ignored")
                return false
            }
        }
        return when (op.kind) {
            WearOpKind.DELETE -> {
                if (existing == null || existing.deleted) return false
                repository.upsertRaw(
                    existing.copy(deleted = true, updatedAt = incoming.updatedAt),
                )
                true
            }
            WearOpKind.UPSERT -> {
                if (existing != null && !existing.isTextOnly()) {
                    Log.status(TAG, "wear edit refused for non-text note ${incoming.syncId}")
                    return false
                }
                val base = existing ?: Note(
                    title = incoming.title,
                    content = incoming.body,
                    syncId = incoming.syncId,
                )
                val edited = base
                    .withBody(NoteBody(listOf(NoteBlock.Text(incoming.body))))
                    .copy(
                        title = incoming.title.take(WearNotesProtocol.MAX_TITLE_CHARS),
                        syncId = incoming.syncId,
                        updatedAt = incoming.updatedAt,
                        deleted = false,
                    )
                repository.upsertRaw(edited)
                true
            }
        }
    }

    /** Phone note -> wire note: body is the plain-text content; blocks decide editability. */
    private fun Note.toWearNote(): WearNote = WearNote(
        syncId = syncId,
        title = title,
        body = content,
        updatedAt = updatedAt,
        deleted = deleted,
        textOnly = isTextOnly(),
    )

    private fun Note.isTextOnly(): Boolean =
        body().blocks.all { it is NoteBlock.Text }
}
