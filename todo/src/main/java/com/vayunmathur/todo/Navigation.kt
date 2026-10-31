package com.vayunmathur.todo

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.todo.platform.TodoViewModel
import com.vayunmathur.todo.ui.TodoDetailPage
import com.vayunmathur.todo.ui.TodoListPage

@Composable
fun Navigation(todoViewModel: TodoViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.TodoList)
    MainNavigation(backStack) {
        entry<Route.TodoList>(metadata = ListPage()) {
            TodoListPage(backStack, todoViewModel)
        }
        entry<Route.TodoDetail>(metadata = ListDetailPage() + MorphPage()) {
            TodoDetailPage(backStack, todoViewModel, it)
        }
    }
}
