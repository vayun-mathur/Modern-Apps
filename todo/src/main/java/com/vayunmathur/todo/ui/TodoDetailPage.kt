package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.todo.Route
import com.vayunmathur.todo.platform.TodoActions
import com.vayunmathur.todo.platform.TodoUiState
import com.vayunmathur.todo.platform.TodoViewModel

/** Binds a [TodoEditor][com.vayunmathur.todo.platform.TodoEditor] session to the stateless [TodoDetailScreen]. */
@Composable
fun TodoDetailPage(backStack: NavBackStack<Route>, viewModel: TodoViewModel, route: Route.TodoDetail) {
    val editor = remember(route) { viewModel.openEditor(route.id, route.newSyncId) }
    DisposableEffect(editor) { onDispose { editor.close() } }
    val todo by editor.state.collectAsStateWithLifecycle()

    val actions = remember(backStack, editor) {
        object : TodoActions {
            override fun back() { backStack.pop() }
            override fun setTitle(title: String) = editor.update { it.copy(title = title) }
            override fun setNotes(notes: String) = editor.update { it.copy(notes = notes) }
            override fun setDone(done: Boolean) = editor.update { it.copy(done = done) }
            override fun setDueAt(dueAt: Long?) = editor.update { it.copy(dueAt = dueAt) }
            override fun deleteTodo() {
                editor.delete()
                backStack.pop()
            }
        }
    }

    val current = todo ?: return
    TodoDetailScreen(
        state = TodoUiState(title = current.title, notes = current.notes, done = current.done, dueAt = current.dueAt),
        actions = actions,
    )
}
