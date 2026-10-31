package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.domain.TodoView

@Composable
fun viewTitle(view: TodoView, lists: List<TodoList>): String = when (view) {
    TodoView.Inbox -> stringResource(R.string.view_inbox)
    TodoView.Today -> stringResource(R.string.view_today)
    TodoView.Upcoming -> stringResource(R.string.view_upcoming)
    TodoView.Priority -> stringResource(R.string.view_priority)
    TodoView.All -> stringResource(R.string.view_all)
    TodoView.Completed -> stringResource(R.string.view_completed)
    is TodoView.InList -> lists.firstOrNull { it.syncId == view.listSyncId }?.name ?: stringResource(R.string.untitled)
    is TodoView.Tagged -> "#" + view.tag
}
