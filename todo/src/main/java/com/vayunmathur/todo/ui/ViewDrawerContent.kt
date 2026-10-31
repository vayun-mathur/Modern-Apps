package com.vayunmathur.todo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.IconEvent
import com.vayunmathur.library.ui.IconFlag
import com.vayunmathur.library.ui.IconInbox
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconLocalOffer
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconToday
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.NavigationDrawerItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.domain.TodoView

/** Everything the view drawer draws. */
data class DrawerUiState(
    val view: TodoView = TodoView.Inbox,
    val counts: Map<TodoView, Int> = emptyMap(),
    val lists: List<TodoList> = emptyList(),
    val tags: List<String> = emptyList(),
    /** e.g. "Synced 5 min ago", or null when sync was never set up. */
    val syncStatus: String? = null,
)

interface DrawerActions {
    fun select(view: TodoView) {}
    fun newList() {}
    fun editList(list: TodoList) {}
    fun openSettings() {}
}

/** Smart views, lists (long-press to edit), tags, and settings. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewDrawerContent(state: DrawerUiState, actions: DrawerActions, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        TodoView.smartViews.forEach { view ->
            val count = state.counts[view]
            NavigationDrawerItem(
                label = { Text(viewTitle(view, state.lists)) },
                selected = state.view == view,
                onClick = { actions.select(view) },
                icon = { SmartViewIcon(view) },
                badge = count?.takeIf { it > 0 }?.let { { Text(it.toString()) } },
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionLabel(stringResource(R.string.lists))
        state.lists.forEach { list ->
            val view = TodoView.InList(list.syncId)
            val tint = list.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
            NavigationDrawerItem(
                label = { Text(list.name) },
                selected = state.view == view,
                onClick = { actions.select(view) },
                icon = { ListIcon(list.icon, tint = tint) },
                badge = state.counts[view]?.takeIf { it > 0 }?.let { { Text(it.toString()) } },
                modifier = Modifier.combinedClickable(onClick = { actions.select(view) }, onLongClick = { actions.editList(list) }),
            )
        }
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.new_list)) },
            selected = false,
            onClick = actions::newList,
            icon = { IconAdd() },
        )
        if (state.tags.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel(stringResource(R.string.tags))
            state.tags.forEach { tag ->
                val view = TodoView.Tagged(tag)
                NavigationDrawerItem(
                    label = { Text(tag) },
                    selected = state.view == view,
                    onClick = { actions.select(view) },
                    icon = { IconLocalOffer() },
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.settings)) },
            selected = false,
            onClick = actions::openSettings,
            icon = { IconSettings() },
        )
        state.syncStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SmartViewIcon(view: TodoView) = when (view) {
    TodoView.Inbox -> IconInbox()
    TodoView.Today -> IconToday()
    TodoView.Upcoming -> IconEvent()
    TodoView.Priority -> IconFlag()
    TodoView.All -> IconList()
    TodoView.Completed -> IconCheckCircle()
    else -> Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
}
