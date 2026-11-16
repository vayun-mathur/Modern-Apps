package com.vayunmathur.todo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.DetailPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.todo.platform.TodoViewModel
import com.vayunmathur.todo.ui.IntentEffect
import com.vayunmathur.todo.ui.SettingsPage
import com.vayunmathur.todo.ui.TodoDetailPage
import com.vayunmathur.todo.ui.TodoListPage
import com.vayunmathur.todo.ui.TodoShell
import com.vayunmathur.todo.ui.rememberTodoShellState

@Composable
fun Navigation(todoViewModel: TodoViewModel) {
    // The initial route is the user's default view. Wait for its first emission
    // so the retained back stack is created with that view, not a placeholder;
    // later default-view changes do not rewrite the live stack.
    val defaultView by todoViewModel.settings.defaultView
        .collectAsStateWithLifecycle(initialValue = null)
    val initialView = defaultView ?: return
    val backStack = rememberNavBackStack<Route>(Route.TodoList(initialView.key))
    val shell = rememberTodoShellState()
    IntentEffect(backStack, todoViewModel)
    TodoShell(shell, todoViewModel, backStack) {
        MainNavigation(backStack) {
            entry<Route.TodoList>(metadata = ListPage()) { route ->
                TodoListPage(
                    backStack,
                    todoViewModel,
                    route,
                    onMenu = if (shell.permanent) null else shell::openDrawer,
                )
            }
            entry<Route.TodoDetail>(metadata = ListDetailPage() + MorphPage()) {
                TodoDetailPage(backStack, todoViewModel, it)
            }
            entry<Route.Settings>(metadata = DetailPage()) {
                SettingsPage(backStack, todoViewModel)
            }
        }
    }
}
