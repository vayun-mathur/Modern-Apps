package com.vayunmathur.todo

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.DetailPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.todo.platform.TodoViewModel
import com.vayunmathur.todo.ui.SettingsPage
import com.vayunmathur.todo.ui.TodoDetailPage
import com.vayunmathur.todo.ui.TodoListPage
import com.vayunmathur.todo.ui.TodoShell
import com.vayunmathur.todo.ui.rememberTodoShellState

@Composable
fun Navigation(todoViewModel: TodoViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.TodoList)
    val shell = rememberTodoShellState()
    TodoShell(shell, todoViewModel, backStack) {
        MainNavigation(backStack) {
            entry<Route.TodoList>(metadata = ListPage()) {
                TodoListPage(backStack, todoViewModel, onMenu = if (shell.permanent) null else shell::openDrawer)
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
