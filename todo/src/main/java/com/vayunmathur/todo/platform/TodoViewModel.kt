package com.vayunmathur.todo.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.domain.TodoSearch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodoViewModel(
    application: Application,
    private val repository: TodoRepository,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val io = repository.io

    val searchQuery: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")

    val visibleTodos: StateFlow<List<Todo>> = combine(repository.todos, searchQuery) { todos, query ->
        TodoSearch.filter(todos, query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    init {
        viewModelScope.launch(io) { repository.maintain() }
    }

    fun setSearchQuery(query: String) {
        savedState[KEY_QUERY] = query
    }

    fun toggleDone(todo: Todo) {
        viewModelScope.launch(io) { repository.setDone(todo, !todo.done) }
    }

    fun delete(todo: Todo) {
        viewModelScope.launch(io) { repository.tombstone(todo) }
    }

    fun upsertAll(todos: List<Todo>) {
        if (todos.isEmpty()) return
        viewModelScope.launch(io) { repository.upsertAll(todos) }
    }

    fun openEditor(id: Long, newSyncId: String?): TodoEditor =
        TodoEditor(repository, repository.appScope, id, newSyncId, Todo())

    companion object {
        private const val KEY_QUERY = "search_query"
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(application: Application, repository: TodoRepository) = viewModelFactory {
            initializer { TodoViewModel(application, repository, createSavedStateHandle()) }
        }
    }
}
