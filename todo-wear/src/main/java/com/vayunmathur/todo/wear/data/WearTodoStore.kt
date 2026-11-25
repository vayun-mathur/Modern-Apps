package com.vayunmathur.todo.wear.data

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.todo.wear.domain.WearTodoCatalog
import com.vayunmathur.todo.wear.domain.WearTodoOp
import com.vayunmathur.todo.wear.domain.WearTodoOpKind
import com.vayunmathur.todo.wear.domain.WearTodoPage
import com.vayunmathur.todo.wear.domain.WearTodoProtocol
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/**
 * The watch's local cache: the last phone catalog, every visited page
 * snapshot, and this watch's pending ops — all persisted as JSON in
 * app-private storage so every page reads fully offline (phone absent,
 * process cold-started). The phone stays the source of truth; pending ops
 * leave the queue only when a fresh page/catalog no longer needs their
 * local overlay, never on send alone. Reflected-op detection here is
 * deliberately simple: when a page for the op's view is republished with a
 * newer generation, ops targeting tasks it contains (or no longer
 * contains, for deletes) are dropped.
 */
class WearTodoStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WearTodoStore"

        @Volatile
        private var instance: WearTodoStore? = null

        /** The process-wide store (bridge and UI must agree on one). */
        fun get(context: Context): WearTodoStore = instance ?: synchronized(this) {
            instance ?: WearTodoStore(context.applicationContext).also { instance = it }
        }

        /** New op / quick-add task ids use the phone's syncId shape (32 hex chars). */
        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }

    private val catalogFile = File(context.filesDir, "wear-todo-catalog.json")
    private val pagesFile = File(context.filesDir, "wear-todo-pages.json")
    private val pendingFile = File(context.filesDir, "wear-todo-pending.json")

    private val _catalog = MutableStateFlow(loadCatalog())
    private val _pages = MutableStateFlow(loadPages())
    private val _pendingOps = MutableStateFlow(loadPending())

    val catalog: StateFlow<WearTodoCatalog> = _catalog.asStateFlow()

    /** Cached pages by "pageKey@offset". */
    val pages: StateFlow<Map<String, WearTodoPage>> = _pages.asStateFlow()

    /** Watch-originated ops not yet reflected by the phone. */
    val pendingOps: StateFlow<List<WearTodoOp>> = _pendingOps.asStateFlow()

    val appliedCatalogAtMillis: Long get() = _catalog.value.generatedAtMillis

    fun setCatalog(catalog: WearTodoCatalog) {
        if (catalog.generatedAtMillis < _catalog.value.generatedAtMillis) return
        _catalog.value = catalog
        persist(catalogFile, WearTodoProtocol.json.encodeToString(catalog))
        com.vayunmathur.todo.wear.sync.WearLinkBridge.publishWatchState(context)
    }

    fun setPage(page: WearTodoPage) {
        val key = pageKey(page.pageKey, page.offset)
        val existing = _pages.value[key]
        if (existing != null && page.generatedAtMillis < existing.generatedAtMillis) return
        _pages.value = _pages.value + (key to page)
        persist(pagesFile, WearTodoProtocol.json.encodeToString(_pages.value))
        dropReflected(page)
        com.vayunmathur.todo.wear.sync.WearLinkBridge.publishWatchState(context)
    }

    /** All cached slices of one view, in offset order (for offline paging). */
    fun pagesFor(pageKey: String): List<WearTodoPage> =
        _pages.value.values.filter { it.pageKey == pageKey }.sortedBy { it.offset }

    fun setDone(syncId: String, done: Boolean) =
        queue(WearTodoOp(WearTodoOpKind.SET_DONE, opId = newId(), syncId = syncId, done = done))

    fun snooze(syncId: String, tomorrow: Boolean) = queue(
        WearTodoOp(
            if (tomorrow) WearTodoOpKind.SNOOZE_TOMORROW else WearTodoOpKind.SNOOZE_HOUR,
            opId = newId(),
            syncId = syncId,
        ),
    )

    fun quickAdd(text: String, pageKey: String): WearTodoOp {
        val op = WearTodoOp(
            WearTodoOpKind.QUICK_ADD,
            opId = newId(),
            syncId = newId(),
            text = text.trim().take(WearTodoProtocol.MAX_TEXT_CHARS),
            pageKey = pageKey,
        )
        queue(op)
        return op
    }

    fun edit(syncId: String, title: String?, dueAt: Long?, clearDue: Boolean, priority: Int?) =
        queue(
            WearTodoOp(
                WearTodoOpKind.EDIT,
                opId = newId(),
                syncId = syncId,
                title = title,
                dueAt = dueAt,
                clearDue = clearDue,
                priority = priority,
            ),
        )

    fun delete(syncId: String) =
        queue(WearTodoOp(WearTodoOpKind.DELETE, opId = newId(), syncId = syncId))

    private fun queue(op: WearTodoOp) {
        // One pending op per (kind, task): a newer local action supersedes the
        // older one. QUICK_ADDs are exempt (opId-keyed, each is a new task).
        _pendingOps.value = _pendingOps.value.filter {
            op.kind == WearTodoOpKind.QUICK_ADD || it.kind != op.kind || it.syncId != op.syncId
        } + op
        persist(pendingFile, WearTodoProtocol.json.encodeToString(_pendingOps.value))
        com.vayunmathur.todo.wear.sync.WearLinkBridge.flushPending(context)
    }

    private fun dropReflected(page: WearTodoPage) {
        if (_pendingOps.value.isEmpty()) return
        val ids = page.tasks.flatMap { listOf(it.syncId) + it.subtasks.map { s -> s.syncId } }.toSet()
        val kept = _pendingOps.value.filter { op ->
            when (op.kind) {
                // A republished page decides these: present tasks carry the
                // phone's post-op state; a delete is reflected by absence
                // once its page is republished (checked by caller pages too).
                WearTodoOpKind.SET_DONE, WearTodoOpKind.EDIT -> op.syncId !in ids
                WearTodoOpKind.DELETE -> op.syncId in ids
                else -> true
            }
        }
        if (kept.size != _pendingOps.value.size) {
            _pendingOps.value = kept
            persist(pendingFile, WearTodoProtocol.json.encodeToString(kept))
        }
    }

    private fun pageKey(pageKey: String, offset: Int) = "$pageKey@$offset"

    private fun loadCatalog(): WearTodoCatalog = runCatching {
        WearTodoProtocol.json.decodeFromString<WearTodoCatalog>(catalogFile.readText())
    }.getOrDefault(WearTodoCatalog())

    private fun loadPages(): Map<String, WearTodoPage> = runCatching {
        WearTodoProtocol.json.decodeFromString<Map<String, WearTodoPage>>(pagesFile.readText())
    }.getOrDefault(emptyMap())

    private fun loadPending(): List<WearTodoOp> = runCatching {
        WearTodoProtocol.json.decodeFromString<List<WearTodoOp>>(pendingFile.readText())
    }.getOrDefault(emptyList())

    private fun persist(file: File, text: String) {
        runCatching { file.writeText(text) }
            .onFailure { Log.status(TAG, "persist ${file.name} failed: ${it.message}") }
    }
}
