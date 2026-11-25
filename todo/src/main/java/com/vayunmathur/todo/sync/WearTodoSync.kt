package com.vayunmathur.todo.sync

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.data.getLists
import com.vayunmathur.todo.data.newSyncId
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.GroupKey
import com.vayunmathur.todo.domain.QuickAddParser
import com.vayunmathur.todo.domain.Reminders
import com.vayunmathur.todo.domain.TodoQuery
import com.vayunmathur.todo.domain.TodoView
import com.vayunmathur.todo.domain.WearTodoCatalog
import com.vayunmathur.todo.domain.WearTodoOp
import com.vayunmathur.todo.domain.WearTodoOpKind
import com.vayunmathur.todo.domain.WearTodoPage
import com.vayunmathur.todo.domain.WearTodoPageRequest
import com.vayunmathur.todo.domain.WearTodoProtocol
import com.vayunmathur.todo.domain.WearTodoSubtask
import com.vayunmathur.todo.domain.WearTodoTask
import com.vayunmathur.todo.domain.WearTodoViewEntry
import com.vayunmathur.todo.domain.WearTodoWatchState
import com.vayunmathur.todo.domain.buildWearCatalog
import com.vayunmathur.todo.domain.buildWearPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.serialization.encodeToString

/** Phone-side wear status; absence of a watch is a normal state, never an error. */
data class WearTodoSyncState(
    val companionPresent: Boolean = false,
    val lastPublishedAtMillis: Long? = null,
    val lastWatchPendingOps: Int = 0,
)

/**
 * Phone side of the Todo wear companion over WearLink. Publishes the view
 * catalog and bounded per-view pages on connect/change, and applies
 * watch-originated ops here — the phone stays the source of truth, so a
 * completion runs the same [TodoRepository.setDone] path as the phone UI
 * (repeating tasks roll forward, subtasks reopen) and a quick-add runs the
 * phone's [QuickAddParser] against the phone's lists. After any applied
 * op the catalog and every page the watch has asked for are republished,
 * which is what drops the op from the watch's pending queue.
 */
class WearTodoSync private constructor(context: Context) {

    companion object {
        private const val TAG = "WearTodoSync"
        private const val MAX_TRACKED_PAGES = 6

        @Volatile
        private var instance: WearTodoSync? = null

        /** The process-wide sync (activity must agree on one). */
        fun get(context: Context): WearTodoSync = instance ?: synchronized(this) {
            instance ?: WearTodoSync(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository get() = TodoRepository.get(appContext)

    private val _state = MutableStateFlow(WearTodoSyncState())
    val state: StateFlow<WearTodoSyncState> = _state.asStateFlow()

    /** Pages the watch has opened, most recent last; republished after changes. */
    private val trackedPages = mutableListOf<WearTodoPageRequest>()

    private val listener = object : WearLinkListener {
        override fun onData(path: String, body: ByteArray) {
            if (path != WearTodoProtocol.WATCH_STATE_PATH) return
            val watchState = runCatching {
                WearTodoProtocol.json.decodeFromString<WearTodoWatchState>(body.decodeToString())
            }.getOrNull() ?: return
            _state.update { it.copy(lastWatchPendingOps = watchState.pendingOps) }
        }

        override fun onMessage(path: String, body: ByteArray) {
            when (path) {
                WearTodoProtocol.REQUEST_SYNC_PATH -> scope.launch {
                    publishCatalog()
                    publishTrackedOrDefault()
                }
                WearTodoProtocol.REQUEST_PAGE_PATH -> {
                    val request = runCatching {
                        WearTodoProtocol.json.decodeFromString<WearTodoPageRequest>(body.decodeToString())
                    }.getOrNull() ?: return
                    synchronized(trackedPages) {
                        trackedPages.removeAll { it.pageKey == request.pageKey }
                        trackedPages += request
                        while (trackedPages.size > MAX_TRACKED_PAGES) trackedPages.removeAt(0)
                    }
                    scope.launch { publishPage(request) }
                }
                WearTodoProtocol.OP_PATH -> {
                    val op = runCatching {
                        WearTodoProtocol.json.decodeFromString<WearTodoOp>(body.decodeToString())
                    }.getOrNull()
                    if (op == null) {
                        Log.status(TAG, "ignoring malformed wear op")
                        return
                    }
                    scope.launch {
                        if (applyWearOp(op)) refreshPublished()
                    }
                }
            }
        }
    }

    private val node = WearLinkNode(appContext, WearTodoProtocol.CAPABILITY, listener)
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
                if (present && !wasPresent) {
                    publishCatalog()
                    publishTrackedOrDefault()
                }
            }
        }
        scope.launch {
            repository.todos.collect { onTodosChanged() }
        }
        scope.launch {
            repository.lists.collect { onTodosChanged() }
        }
        node.start()
    }

    private fun onTodosChanged() {
        if (!_state.value.companionPresent) return
        pendingPublish?.cancel()
        pendingPublish = scope.launch {
            delay(1_000)
            refreshPublished()
        }
    }

    private suspend fun refreshPublished() {
        publishCatalog()
        val pages = synchronized(trackedPages) { trackedPages.toList() }
        pages.forEach { publishPage(it) }
    }

    private suspend fun publishTrackedOrDefault() {
        val pages = synchronized(trackedPages) { trackedPages.toList() }
        if (pages.isEmpty()) {
            publishPage(WearTodoPageRequest(defaultViewKey()))
        } else {
            pages.forEach { publishPage(it) }
        }
    }

    private suspend fun defaultViewKey(): String = runCatching {
        com.vayunmathur.todo.data.TodoSettings(appContext).defaultView.first().key
    }.getOrDefault(TodoView.Today.key)

    /** Build the view catalog (smart views, lists, tags — the phone drawer) and publish it. */
    suspend fun publishCatalog() {
        val catalog = buildCatalog(System.currentTimeMillis())
        val sent = node.putData(
            WearTodoProtocol.CATALOG_PATH,
            WearTodoProtocol.json.encodeToString(catalog).encodeToByteArray(),
        )
        if (sent) {
            _state.update { it.copy(lastPublishedAtMillis = catalog.generatedAtMillis) }
        } else {
            Log.status(TAG, "publish catalog not delivered (no live WearLink session)")
        }
    }

    internal suspend fun buildCatalog(now: Long): WearTodoCatalog {
        val todos = repository.getAll().filter { !it.deleted }
        val lists = repository.getLists().filter { !it.deleted }
        val zone = TimeZone.currentSystemDefault()
        val counts = TodoQuery.counts(todos, lists, now, zone)
        val views = mutableListOf<WearTodoViewEntry>()
        for (view in TodoView.smartViews) {
            views += WearTodoViewEntry(
                key = view.key,
                title = smartViewTitle(view),
                kind = "SMART",
                openCount = counts[view] ?: 0,
                totalCount = totalFor(view, todos),
            )
        }
        for (list in lists.sortedBy { it.position }) {
            val view = TodoView.InList(list.syncId)
            views += WearTodoViewEntry(
                key = view.key,
                title = list.name,
                kind = "LIST",
                openCount = counts[view] ?: 0,
                totalCount = todos.count { it.parentSyncId == null && it.listSyncId == list.syncId },
            )
        }
        for (tag in TodoQuery.allTags(todos)) {
            views += WearTodoViewEntry(
                key = TodoView.Tagged(tag).key,
                title = tag,
                kind = "TAG",
                openCount = todos.count {
                    !it.done && it.parentSyncId == null && it.tags.any { t -> t.equals(tag, true) }
                },
                totalCount = todos.count {
                    it.parentSyncId == null && it.tags.any { t -> t.equals(tag, true) }
                },
            )
        }
        return buildWearCatalog(views, now, defaultViewKey())
    }

    private fun totalFor(view: TodoView, todos: List<Todo>): Int = when (view) {
        TodoView.Completed -> todos.count { it.done && it.parentSyncId == null }
        TodoView.All -> todos.count { it.parentSyncId == null }
        else -> todos.count { it.parentSyncId == null && !it.done }
    }

    /** Build and publish one page snapshot for [request]. */
    suspend fun publishPage(request: WearTodoPageRequest) {
        val page = buildPage(request, System.currentTimeMillis())
        node.putData(
            WearTodoProtocol.PAGE_PATH,
            WearTodoProtocol.json.encodeToString(page).encodeToByteArray(),
        )
    }

    internal suspend fun buildPage(request: WearTodoPageRequest, now: Long): WearTodoPage {
        val view = TodoView.fromKey(request.pageKey)
        val todos = repository.getAll().filter { !it.deleted }
        val lists = repository.getLists().filter { !it.deleted }
        val zone = TimeZone.currentSystemDefault()
        val model = TodoQuery.build(TodoQuery.Input(todos, lists, view, now = now, zone = zone))
        val listsById = lists.associateBy { it.syncId }
        val children = todos.filter { it.parentSyncId != null }.groupBy { it.parentSyncId }
        fun toTask(row: com.vayunmathur.todo.domain.TodoRow, label: String): WearTodoTask {
            val todo = row.todo
            val subs = children[todo.syncId].orEmpty()
            return WearTodoTask(
                syncId = todo.syncId,
                title = todo.title,
                notes = todo.notes,
                done = todo.done,
                dueAt = todo.dueAt,
                dueAllDay = todo.dueAllDay,
                priority = todo.priority,
                listSyncId = todo.listSyncId,
                listName = todo.listSyncId?.let { listsById[it]?.name },
                section = todo.section,
                tags = todo.tags,
                recurrence = todo.recurrence,
                reminderAt = todo.reminderAt,
                completedAt = todo.completedAt,
                updatedAt = todo.updatedAt,
                subtasks = subs.map { WearTodoSubtask(it.syncId, it.title, it.done) },
                overdue = row.overdue,
                blocked = row.blocked,
                groupLabel = label,
            )
        }
        val tasks = mutableListOf<WearTodoTask>()
        for (group in model.groups) {
            val label = when (val key = group.key) {
                GroupKey.None -> ""
                is GroupKey.Bucket -> key.bucket.name.lowercase().replaceFirstChar { it.uppercase() }
                is GroupKey.Section -> key.name ?: ""
            }
            group.rows.forEach { tasks += toTask(it, label) }
        }
        if (view != TodoView.Completed) {
            model.completed.forEach { tasks += toTask(it, "Completed") }
        }
        val title = when (view) {
            is TodoView.InList -> listsById[view.listSyncId]?.name ?: "List"
            is TodoView.Tagged -> view.tag
            else -> smartViewTitle(view)
        }
        return buildWearPage(view.key, title, tasks, request.offset, now)
    }

    private fun smartViewTitle(view: TodoView): String = when (view) {
        TodoView.Inbox -> "Inbox"
        TodoView.Today -> "Today"
        TodoView.Upcoming -> "Upcoming"
        TodoView.Priority -> "Priority"
        TodoView.All -> "All tasks"
        TodoView.Completed -> "Completed"
        is TodoView.InList -> "List"
        is TodoView.Tagged -> view.tag
    }

    /**
     * Applies one watch op on the phone. Returns true when the store changed
     * (caller republishes). Internal for unit tests.
     */
    internal suspend fun applyWearOp(
        op: WearTodoOp,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): Boolean = when (op.kind) {
        WearTodoOpKind.SET_DONE -> {
            val todo = repository.getBySyncId(op.syncId)?.takeIf { !it.deleted } ?: return false
            if (todo.done == op.done) return false
            repository.setDone(todo, op.done, now)
            true
        }
        WearTodoOpKind.SNOOZE_HOUR, WearTodoOpKind.SNOOZE_TOMORROW -> {
            val todo = repository.getBySyncId(op.syncId)?.takeIf { !it.deleted } ?: return false
            val until = if (op.kind == WearTodoOpKind.SNOOZE_HOUR) {
                now + 3_600_000L
            } else {
                DueDates.at(
                    DueDates.localDate(now, zone).plus(1, DateTimeUnit.DAY),
                    Reminders.ALL_DAY_TIME,
                    zone,
                )
            }
            repository.upsert(todo.copy(reminderAt = until))
            true
        }
        WearTodoOpKind.QUICK_ADD -> applyQuickAdd(op, now, zone)
        WearTodoOpKind.EDIT -> {
            val todo = repository.getBySyncId(op.syncId)?.takeIf { !it.deleted } ?: return false
            val edited = todo.copy(
                title = op.title?.trim()?.take(WearTodoProtocol.MAX_TITLE_CHARS)
                    ?.takeIf { it.isNotEmpty() } ?: todo.title,
                dueAt = when {
                    op.clearDue -> null
                    op.dueAt != null -> op.dueAt
                    else -> todo.dueAt
                },
                dueAllDay = if (op.dueAt != null && !op.clearDue) true else todo.dueAllDay,
                priority = op.priority?.coerceIn(Todo.PRIORITY_NONE, Todo.PRIORITY_MAX)
                    ?: todo.priority,
            )
            if (edited == todo) return false
            repository.upsert(edited)
            true
        }
        WearTodoOpKind.DELETE -> {
            val todo = repository.getBySyncId(op.syncId)?.takeIf { !it.deleted } ?: return false
            repository.tombstone(todo)
            true
        }
    }

    /** Watch text/dictation entry, parsed here with the phone's QuickAddParser. */
    private suspend fun applyQuickAdd(op: WearTodoOp, now: Long, zone: TimeZone): Boolean {
        val text = op.text.trim().take(WearTodoProtocol.MAX_TEXT_CHARS)
        if (text.isEmpty()) return false
        val lists = repository.getLists().filter { !it.deleted }
        val parsed = QuickAddParser.parse(
            text,
            now,
            zone,
            lists.map { QuickAddParser.ListName(it.syncId, it.name) },
        )
        val view = TodoView.fromKey(op.pageKey)
        val title = parsed.title.ifBlank { parsed.tokens.joinToString(" ") { it.text } }
        if (title.isBlank()) return false
        repository.upsert(
            Todo(
                title = title,
                dueAt = parsed.dueAt
                    ?: if (view == TodoView.Today) {
                        DueDates.startOfDay(DueDates.localDate(now, zone), zone)
                    } else {
                        null
                    },
                dueAllDay = if (parsed.dueAt != null) parsed.dueAllDay else true,
                recurrence = parsed.recurrence?.serialize(),
                tags = (view as? TodoView.Tagged)?.let { listOf(it.tag) }.orEmpty() + parsed.tags,
                priority = when {
                    parsed.priority != 0 -> parsed.priority
                    view == TodoView.Priority -> 1
                    else -> Todo.PRIORITY_NONE
                },
                listSyncId = parsed.listSyncId ?: (view as? TodoView.InList)?.listSyncId,
                syncId = op.syncId.takeIf { it.isNotBlank() } ?: newSyncId(),
                createdAt = now,
                position = repository.nextPosition(),
            ),
        )
        return true
    }
}
