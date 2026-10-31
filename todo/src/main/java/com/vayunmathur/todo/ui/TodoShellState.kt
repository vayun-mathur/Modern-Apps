package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.rememberDrawerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The navigation shell: a modal drawer on narrow windows, a permanent panel on wide ones. */
class TodoShellState internal constructor(internal val drawer: DrawerState, private val scope: CoroutineScope) {
    /** True when the views panel is always visible, so screens hide their menu button. */
    var permanent by mutableStateOf(false)
        internal set

    fun openDrawer() {
        scope.launch { drawer.open() }
    }

    fun closeDrawer() {
        scope.launch { drawer.close() }
    }
}

@Composable
fun rememberTodoShellState(): TodoShellState {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    return remember(drawer, scope) { TodoShellState(drawer, scope) }
}
