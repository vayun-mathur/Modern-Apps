package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.todo.Route
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.newSyncId
import com.vayunmathur.todo.domain.QuickAddParser
import com.vayunmathur.todo.domain.SortMode
import com.vayunmathur.todo.platform.TodoListActions
import com.vayunmathur.todo.platform.TodoListUiState
import com.vayunmathur.todo.platform.TodoViewModel

/** Binds [TodoViewModel] and the nav back stack to the stateless [TodoListScreen]. */
@Composable
fun TodoListPage(backStack: NavBackStack<Route>, viewModel: TodoViewModel, onMenu: (() -> Unit)?) {
    val model by viewModel.model.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val quickAddRequest by viewModel.quickAddRequest.collectAsStateWithLifecycle()

    val actions = remember(backStack, viewModel, onMenu) {
        object : TodoListActions {
            override fun openTodo(id: Long) { backStack.add(Route.TodoDetail(id)) }
            override fun createTodo() { backStack.add(Route.TodoDetail(0, newSyncId())) }
            override fun setSearchQuery(query: String) = viewModel.setSearchQuery(query)
            override fun openDrawer() { onMenu?.invoke() }
            override fun setSort(sort: SortMode) = viewModel.setSort(sort)
            override fun quickAdd(texts: List<String>) = viewModel.quickAdd(texts)
            override fun toggleDone(todo: Todo) = viewModel.toggleDone(todo)
            override fun delete(todos: List<Todo>) = viewModel.delete(todos)
            override fun restore(todos: List<Todo>) = viewModel.restore(todos)
            override fun upsertAll(todos: List<Todo>) = viewModel.upsertAll(todos)
            override fun complete(todos: List<Todo>) = viewModel.complete(todos)
            override fun move(todos: List<Todo>, listSyncId: String?) = viewModel.move(todos, listSyncId)
            override fun reschedule(todos: List<Todo>, dueAt: Long?) = viewModel.reschedule(todos, dueAt)
            override fun setPriority(todos: List<Todo>, priority: Int) = viewModel.setPriority(todos, priority)
        }
    }

    TodoListScreen(
        state = TodoListUiState(
            view = view,
            model = model.first,
            lists = lists,
            searchQuery = query,
            sort = sort,
            showAddButton = backStack.last() !is Route.TodoDetail,
            now = model.second,
        ),
        actions = actions,
        quickAddLeading = { VoiceInputButton(onResult = viewModel::requestQuickAdd) },
        showMenu = onMenu != null,
    )

    quickAddRequest?.let { text ->
        QuickAddSheet(
            initialText = text,
            lists = remember(lists) { lists.map { QuickAddParser.ListName(it.syncId, it.name) } },
            onAdd = viewModel::quickAdd,
            onDismiss = viewModel::clearQuickAddRequest,
        )
    }
}
