package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.todo.Route
import com.vayunmathur.todo.domain.TodoView
import com.vayunmathur.todo.platform.IntentRouter
import com.vayunmathur.todo.platform.TodoViewModel

/** Acts on the request MainActivity recorded from its launch intent, once. */
@Composable
fun IntentEffect(backStack: NavBackStack<Route>, viewModel: TodoViewModel) {
    val request by IntentRouter.pending.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        when (val r = request ?: return@LaunchedEffect) {
            is IntentRouter.Request.OpenTask -> viewModel.idForSyncId(r.syncId)?.let { id ->
                backStack.reset(Route.TodoList(currentViewKey(backStack)), Route.TodoDetail(id))
            }
            is IntentRouter.Request.OpenView -> {
                viewModel.clearSearchQuery()
                backStack.reset(Route.TodoList(TodoView.fromKey(r.viewKey).key))
            }
            is IntentRouter.Request.QuickAdd -> {
                backStack.reset(Route.TodoList(currentViewKey(backStack)))
                viewModel.requestQuickAdd(r.text)
            }
        }
        IntentRouter.consume()
    }
}

/** The view of the list entry already in the stack, so resets keep showing it. */
private fun currentViewKey(backStack: NavBackStack<Route>): String =
    backStack.backStack.filterIsInstance<Route.TodoList>().lastOrNull()?.viewKey
        ?: TodoView.Inbox.key
