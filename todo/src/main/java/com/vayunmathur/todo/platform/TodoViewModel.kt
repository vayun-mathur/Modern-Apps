package com.vayunmathur.todo.platform

import com.vayunmathur.todo.data.getLists
import com.vayunmathur.todo.data.nextListPosition
import com.vayunmathur.todo.data.upsertList
import com.vayunmathur.todo.data.upsertLists
import com.vayunmathur.todo.data.tombstoneList
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.data.TodoSettings
import com.vayunmathur.todo.domain.QuickAddParser
import com.vayunmathur.todo.domain.SortMode
import com.vayunmathur.todo.domain.TodoListModel
import com.vayunmathur.todo.domain.TodoQuery
import com.vayunmathur.todo.domain.TodoView
import com.vayunmathur.todo.domain.DueDates
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import com.vayunmathur.todo.domain.format.ExportFormat
import com.vayunmathur.todo.domain.format.ImportedTask
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** View model owning list state, counts, settings and task mutations for the todo screens. */
class TodoViewModel(
    application: Application,
    private val repository: TodoRepository,
    val settings: TodoSettings,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val io = repository.io

    val searchQuery: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")
    val sort: StateFlow<SortMode> = savedState.getStateFlow(KEY_SORT, SortMode.MANUAL.name)
        .map { runCatching { SortMode.valueOf(it) }.getOrDefault(SortMode.MANUAL) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SortMode.MANUAL)

    /** Minute ticks so "overdue" and relative dates stay current while the list is open. */
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    private val _hasLoadedTodos = MutableStateFlow(false)

    /** Flips true once the todos DAO emits at least once; the list starts empty. */
    val hasLoadedTodos: StateFlow<Boolean> = _hasLoadedTodos.asStateFlow()

    val todos: StateFlow<List<Todo>> = repository.todos
        .onEach { _hasLoadedTodos.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())
    val lists: StateFlow<List<TodoList>> = repository.lists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private data class Params(val query: String, val sort: SortMode, val now: Long)

    private val params = combine(searchQuery, sort, clock) { q, s, now -> Params(q, s, now) }

    /**
     * The list model for [view] (the view named by the current `Route.TodoList` entry).
     * The route is authoritative: callers pass its view explicitly and this flow is
     * derived from it, never from persisted view state. Each call creates a flow
     * scoped to [viewModelScope]; pages should remember one instance per view.
     */
    fun modelFor(view: TodoView): StateFlow<Pair<TodoListModel, Long>> =
        combine(todos, lists, params, settings.showCompleted) { todos, lists, p, showDone ->
            val zone = TimeZone.currentSystemDefault()
            val built = TodoQuery.build(TodoQuery.Input(todos, lists, view, p.query, p.sort, p.now, zone))
            (if (showDone) built else built.copy(completed = emptyList())) to p.now
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            TodoListModel() to System.currentTimeMillis(),
        )

    /** Open-task counts per view, for the drawer badges. */
    val counts: StateFlow<Map<TodoView, Int>> = combine(todos, lists, clock) { todos, lists, now ->
        TodoQuery.counts(todos, lists, now, TimeZone.currentSystemDefault())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    val tags: StateFlow<List<String>> = todos.map { TodoQuery.allTags(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    init {
        viewModelScope.launch(io) { repository.maintain() }
        viewModelScope.launch {
            if (!savedState.contains(KEY_SORT)) savedState[KEY_SORT] = settings.defaultSort.first().name
        }
    }

    /** Text waiting to open in the quick-add sheet (share target, shortcut, tile), or null. */
    private val _quickAddRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val quickAddRequest: StateFlow<String?> = _quickAddRequest

    fun requestQuickAdd(text: String) {
        _quickAddRequest.value = text
    }

    fun clearQuickAddRequest() {
        _quickAddRequest.value = null
    }

    suspend fun idForSyncId(syncId: String): Long? =
        kotlinx.coroutines.withContext(io) { repository.getBySyncId(syncId)?.takeIf { !it.deleted }?.id }

    fun saveList(list: TodoList) {
        viewModelScope.launch(io) {
            val toSave = if (list.id == 0L) list.copy(position = repository.nextListPosition()) else list
            repository.upsertList(toSave)
        }
    }

    fun deleteList(list: TodoList) {
        // Fallback to Inbox when the deleted list is being viewed is a routing
        // decision: the shell resets the back stack, not this ViewModel.
        viewModelScope.launch(io) { repository.tombstoneList(list) }
    }

    fun reorderLists(moved: List<TodoList>) {
        viewModelScope.launch(io) { repository.upsertLists(moved) }
    }

    /** Changes the next sync still has to carry; meaningful once a sync has happened. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pendingSync: StateFlow<Int> = settings.lastSyncAt
        .flatMapLatest { since -> repository.pendingSyncCount(since) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private val transfer = TodoDataTransfer(application, repository)

    fun export(uri: android.net.Uri, format: ExportFormat, onDone: () -> Unit) {
        viewModelScope.launch(io) {
            transfer.export(uri, format)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    suspend fun readImport(uri: android.net.Uri): List<ImportedTask> =
        kotlinx.coroutines.withContext(io) { runCatching { transfer.read(uri) }.getOrDefault(emptyList()) }

    fun import(tasks: List<ImportedTask>, onDone: (Int) -> Unit) {
        viewModelScope.launch(io) {
            val count = transfer.import(tasks)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(count) }
        }
    }

    fun updateSettings(block: suspend TodoSettings.() -> Unit) {
        viewModelScope.launch(io) { settings.block() }
    }

    fun setSearchQuery(query: String) {
        savedState[KEY_QUERY] = query
    }

    /** Changing view (a route change) starts with an empty search, as before. */
    fun clearSearchQuery() {
        savedState[KEY_QUERY] = ""
    }

    fun setSort(sort: SortMode) {
        savedState[KEY_SORT] = sort.name
    }

    fun quickAdd(texts: List<String>, view: TodoView) {
        val currentView = view
        viewModelScope.launch(io) {
            val lists = repository.getLists().map { QuickAddParser.ListName(it.syncId, it.name) }
            val zone = TimeZone.currentSystemDefault()
            val now = System.currentTimeMillis()
            for (text in texts.map { it.trim() }.filter { it.isNotEmpty() }) {
                val parsed = QuickAddParser.parse(text, now, zone, lists)
                repository.upsert(
                    newTodoFromTemplate(parsed, currentView, now, zone)
                        .copy(position = repository.nextPosition()),
                )
            }
        }
    }


    fun toggleDone(todo: Todo) {
        viewModelScope.launch(io) { repository.setDone(todo, !todo.done) }
    }

    fun complete(todos: List<Todo>) {
        viewModelScope.launch(io) { todos.filter { !it.done }.forEach { repository.setDone(it, true) } }
    }

    fun delete(todos: List<Todo>) {
        viewModelScope.launch(io) { todos.forEach { repository.tombstone(it) } }
    }

    fun restore(todos: List<Todo>) {
        viewModelScope.launch(io) { todos.forEach { repository.restore(it) } }
    }

    fun upsertAll(todos: List<Todo>) {
        if (todos.isEmpty()) return
        viewModelScope.launch(io) { repository.upsertAll(todos) }
    }

    fun move(todos: List<Todo>, listSyncId: String?) =
        upsertAll(todos.map { it.copy(listSyncId = listSyncId, section = null) })

    fun reschedule(todos: List<Todo>, dueAt: Long?) =
        upsertAll(todos.map { it.copy(dueAt = dueAt, dueAllDay = true) })

    fun setPriority(todos: List<Todo>, priority: Int) = upsertAll(todos.map { it.copy(priority = priority) })

    /** [view] is the route's current view, used only as the template for a new task. */
    fun openEditor(id: Long, newSyncId: String?, view: TodoView): TodoEditor {
        val template = templateForView(view, System.currentTimeMillis(), TimeZone.currentSystemDefault())
        return TodoEditor(repository, repository.appScope, id, newSyncId, template)
    }

    /** Saved-state keys, timing constants and the view-model factory. */
    companion object {
        private const val KEY_QUERY = "search_query"
        private const val KEY_SORT = "sort"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val TICK_MS = 60_000L

        fun factory(application: Application, repository: TodoRepository, settings: TodoSettings) = viewModelFactory {
            initializer { TodoViewModel(application, repository, settings, createSavedStateHandle()) }
        }
    }
}

/** View context fills what the text left out: the open list, today's date, the tag. */
private fun newTodoFromTemplate(p: QuickAddParser.Result, view: TodoView, now: Long, zone: TimeZone): Todo {
    val base = templateForView(view, now, zone)
    return base.copy(
        title = p.title.ifBlank { p.tokens.joinToString(" ") { it.text } },
        dueAt = p.dueAt ?: base.dueAt,
        dueAllDay = if (p.dueAt != null) p.dueAllDay else base.dueAllDay,
        recurrence = p.recurrence?.serialize(),
        tags = (base.tags + p.tags).distinctBy { it.lowercase() },
        priority = if (p.priority != 0) p.priority else base.priority,
        listSyncId = p.listSyncId ?: base.listSyncId,
        createdAt = now,
    )
}

/** A blank task belonging to [view], so it shows up where it was created. */
private fun templateForView(view: TodoView, now: Long, zone: TimeZone): Todo = Todo(
    dueAt = if (view == TodoView.Today) DueDates.startOfDay(DueDates.localDate(now, zone), zone) else null,
    listSyncId = (view as? TodoView.InList)?.listSyncId,
    tags = (view as? TodoView.Tagged)?.let { listOf(it.tag) }.orEmpty(),
    priority = if (view == TodoView.Priority) 1 else Todo.PRIORITY_NONE,
    createdAt = now,
)
