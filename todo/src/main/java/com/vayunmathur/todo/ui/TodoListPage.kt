package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.todo.Route
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.newSyncId
import com.vayunmathur.todo.platform.TodoListActions
import com.vayunmathur.todo.platform.TodoListUiState
import com.vayunmathur.todo.platform.TodoViewModel

/** Binds [TodoViewModel] and the nav back stack to the stateless [TodoListScreen]. */
@Composable
fun TodoListPage(backStack: NavBackStack<Route>, viewModel: TodoViewModel) {
    val todos by viewModel.visibleTodos.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()

    val actions = remember(backStack, viewModel) {
        object : TodoListActions {
            override fun openTodo(id: Long) { backStack.add(Route.TodoDetail(id)) }
            override fun createTodo() { backStack.add(Route.TodoDetail(0, newSyncId())) }
            override fun setSearchQuery(query: String) = viewModel.setSearchQuery(query)
            override fun toggleDone(todo: Todo) = viewModel.toggleDone(todo)
            override fun delete(todo: Todo) = viewModel.delete(todo)
            override fun upsertAll(todos: List<Todo>) = viewModel.upsertAll(todos)
        }
    }

    TodoListScreen(
        state = TodoListUiState(
            todos = todos,
            searchQuery = query,
            showAddButton = backStack.last() !is Route.TodoDetail,
        ),
        actions = actions,
        backupButtons = { TodoBackupButtons() },
    )
}
