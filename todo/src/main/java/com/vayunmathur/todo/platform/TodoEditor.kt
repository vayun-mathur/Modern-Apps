package com.vayunmathur.todo.platform

import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.domain.Completion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Edit session for one task.
 *
 * Writes run on the repository's app scope, so leaving the screen mid-debounce cannot drop
 * the last edit: [close] flushes it. A new task is identified by its pre-assigned sync id,
 * so the session can find the row again after the first save, and is only inserted once it
 * has a title; a new task whose title is cleared again is discarded on [close].
 */
class TodoEditor internal constructor(
    private val repository: TodoRepository,
    private val scope: CoroutineScope,
    private val id: Long,
    private val newSyncId: String?,
    private val template: Todo,
) {
    private val _state = MutableStateFlow<Todo?>(null)
    val state: StateFlow<Todo?> = _state

    @OptIn(ExperimentalCoroutinesApi::class)
    val subtasks: Flow<List<Todo>> = _state.filterNotNull().map { it.syncId }.distinctUntilChanged()
        .flatMapLatest { repository.subtasksFlow(it) }

    private val mutex = Mutex()
    @Volatile private var dirty = false
    @Volatile private var finished = false
    private var createdHere = false
    private var saveJob: Job? = null
    private val observeJob: Job = scope.launch { observe() }

    private suspend fun observe() {
        val initial = load()
        _state.compareAndSet(null, initial)
        repository.todoBySyncIdFlow(initial.syncId).collect { stored ->
            if (stored != null && !stored.deleted && !dirty) _state.value = stored
        }
    }

    private suspend fun load(): Todo {
        if (id != 0L) repository.getById(id)?.let { return it }
        newSyncId?.let { syncId -> repository.getBySyncId(syncId)?.let { return it } }
        return template.copy(
            syncId = newSyncId ?: template.syncId,
            position = repository.nextPosition(),
            createdAt = System.currentTimeMillis(),
        )
    }

    fun update(transform: (Todo) -> Todo) {
        if (finished) return
        val current = _state.value ?: return
        _state.value = transform(current)
        dirty = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            save()
        }
    }

    /** Completing a repeating task rolls it forward and reopens its subtasks. */
    fun setDone(done: Boolean) {
        val before = _state.value ?: return
        val now = System.currentTimeMillis()
        val after = if (done) {
            Completion.complete(before, now, TimeZone.currentSystemDefault())
        } else {
            Completion.uncomplete(before)
        }
        update { after }
        if (Completion.rolledForward(before, after)) scope.launch { repository.reopenSubtasks(before.syncId) }
    }

    /** Saves first so a brand-new parent exists before its first subtask. */
    fun addSubtask(title: String) {
        if (title.isBlank()) return
        scope.launch {
            saveNow()
            val parent = _state.value ?: return@launch
            if (parent.id != 0L) repository.addSubtask(parent, title.trim())
        }
    }

    fun duplicate(onCreated: (Long) -> Unit) {
        scope.launch {
            saveNow()
            val value = _state.value ?: return@launch
            if (value.id == 0L) return@launch
            val copy = repository.duplicate(value)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onCreated(copy.id) }
        }
    }

    private suspend fun saveNow() {
        saveJob?.cancel()
        save()
    }

    private suspend fun save() = mutex.withLock {
        if (!dirty) return@withLock
        val value = _state.value ?: return@withLock
        if (value.id == 0L && value.title.isBlank()) return@withLock
        dirty = false
        val newId = repository.upsert(value)
        if (value.id == 0L) {
            createdHere = true
            _state.update { it?.copy(id = newId) }
        }
    }

    fun delete() {
        finished = true
        saveJob?.cancel()
        observeJob.cancel()
        scope.launch {
            mutex.withLock {
                dirty = false
                val value = _state.value ?: return@withLock
                if (value.id != 0L) repository.tombstone(value)
            }
        }
    }

    fun close() {
        if (finished) return
        finished = true
        saveJob?.cancel()
        observeJob.cancel()
        scope.launch {
            save()
            val value = _state.value ?: return@launch
            if (createdHere && value.id != 0L && value.title.isBlank()) repository.tombstone(value)
        }
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 300L
    }
}
