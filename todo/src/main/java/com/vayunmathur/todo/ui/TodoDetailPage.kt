package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.todo.Route
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.TodoQuery
import com.vayunmathur.todo.platform.TodoActions
import com.vayunmathur.todo.platform.TodoUiState
import com.vayunmathur.todo.platform.TodoViewModel

/** Binds a [TodoEditor][com.vayunmathur.todo.platform.TodoEditor] session to the stateless [TodoDetailScreen]. */
@Composable
fun TodoDetailPage(backStack: NavBackStack<Route>, viewModel: TodoViewModel, route: Route.TodoDetail) {
    val editor = remember(route) { viewModel.openEditor(route.id, route.newSyncId) }
    DisposableEffect(editor) { onDispose { editor.close() } }
    val todo by editor.state.collectAsStateWithLifecycle()
    val subtasks by editor.subtasks.collectAsStateWithLifecycle(emptyList())
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val allTodos by viewModel.todos.collectAsStateWithLifecycle()

    val actions = remember(backStack, editor) {
        object : TodoActions {
            override fun back() { backStack.pop() }
            override fun update(transform: (Todo) -> Todo) = editor.update(transform)
            override fun setDone(done: Boolean) = editor.setDone(done)
            override fun addSubtask(title: String) = editor.addSubtask(title)
            override fun updateSubtask(subtask: Todo) = viewModel.upsertAll(listOf(subtask))
            override fun toggleSubtask(subtask: Todo) = viewModel.toggleDone(subtask)
            override fun deleteSubtask(subtask: Todo) = viewModel.delete(listOf(subtask))
            override fun duplicate() = editor.duplicate { id -> backStack.setLast(Route.TodoDetail(id)) }
            override fun deleteTodo() {
                editor.delete()
                backStack.pop()
            }
        }
    }

    val current = todo ?: return
    val excluded = remember(current.syncId, subtasks) { subtasks.map { it.syncId }.toSet() + current.syncId }
    TodoDetailScreen(
        state = TodoUiState(
            todo = current,
            subtasks = subtasks,
            lists = lists,
            allTags = remember(allTodos) { TodoQuery.allTags(allTodos) },
            blockerCandidates = allTodos.filter { !it.done && it.parentSyncId == null && it.syncId !in excluded },
            isNew = route.id == 0L && current.id == 0L,
        ),
        actions = actions,
    )
}
