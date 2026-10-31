package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.todo.R
import com.vayunmathur.todo.Route
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.domain.TodoView
import com.vayunmathur.todo.platform.TodoViewModel
import com.vayunmathur.library.ui.R as UiR

/**
 * Wraps the nav graph with the views panel. At 840dp and wider the panel is permanent, which
 * together with the list-detail layout makes three panes.
 */
@Composable
fun TodoShell(
    shell: TodoShellState,
    viewModel: TodoViewModel,
    backStack: NavBackStack<Route>,
    content: @Composable () -> Unit,
) {
    val view by viewModel.view.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val lastSync by viewModel.settings.lastSyncAt.collectAsStateWithLifecycle(0L)
    var editing by remember { mutableStateOf<TodoList?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TodoList?>(null) }

    val state = DrawerUiState(view, counts, lists, tags, syncStatus(lastSync))
    val actions = object : DrawerActions {
        override fun select(view: TodoView) {
            viewModel.setView(view)
            backStack.reset(Route.TodoList)
            shell.closeDrawer()
        }
        override fun newList() { creating = true }
        override fun editList(list: TodoList) { editing = list }
        override fun openSettings() {
            backStack.add(Route.Settings)
            shell.closeDrawer()
        }
    }

    BoxWithConstraints {
        val wide = maxWidth >= WIDE
        SideEffect { shell.permanent = wide }
        if (wide) {
            Row {
                Surface(Modifier.width(PANEL).fillMaxHeight()) { ViewDrawerContent(state, actions) }
                content()
            }
        } else {
            ModalNavigationDrawer(
                drawerState = shell.drawer,
                drawerContent = { ModalDrawerSheet { ViewDrawerContent(state, actions) } },
                content = content,
            )
        }
    }

    if (creating || editing != null) {
        ListEditDialog(
            list = editing,
            onSave = { list ->
                viewModel.saveList(list)
                if (editing == null) viewModel.setView(TodoView.InList(list.syncId))
                creating = false
                editing = null
            },
            onDelete = { deleting = it },
            onDismiss = {
                creating = false
                editing = null
            },
        )
    }
    deleting?.let { list ->
        ConfirmDialog(
            title = stringResource(R.string.delete_list_title, list.name),
            message = stringResource(R.string.delete_list_message),
            confirmLabel = stringResource(R.string.delete),
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = {
                viewModel.deleteList(list)
                deleting = null
                editing = null
            },
            onDismiss = { deleting = null },
        )
    }
}

private val WIDE = 840.dp
private val PANEL = 280.dp
