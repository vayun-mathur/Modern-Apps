package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.todo.Route
import com.vayunmathur.todo.data.TodoSettings
import com.vayunmathur.todo.domain.SortMode
import com.vayunmathur.todo.domain.TodoView
import com.vayunmathur.todo.platform.TodoViewModel

/** Binds [TodoViewModel]'s settings to the stateless [SettingsScreen]. */
@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: TodoViewModel) {
    val settings = viewModel.settings
    val defaultView by settings.defaultView.collectAsStateWithLifecycle(TodoView.Today)
    val defaultSort by settings.defaultSort.collectAsStateWithLifecycle(SortMode.MANUAL)
    val showCompleted by settings.showCompleted.collectAsStateWithLifecycle(true)
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val digestEnabled by settings.digestEnabled.collectAsStateWithLifecycle(false)
    val digestMinutes by settings.digestMinutes.collectAsStateWithLifecycle(TodoSettings.DEFAULT_DIGEST_MINUTES)

    SettingsScreen(
        state = SettingsUiState(defaultView, defaultSort, showCompleted, lists),
        actions = object : SettingsActions {
            override fun back() { backStack.pop() }
            override fun setDefaultView(view: TodoView) = viewModel.updateSettings { setDefaultView(view) }
            override fun setDefaultSort(sort: SortMode) = viewModel.updateSettings { setDefaultSort(sort) }
            override fun setShowCompleted(show: Boolean) = viewModel.updateSettings { setShowCompleted(show) }
        },
        extraSections = {
            ReminderSettingsSection(
                digestEnabled = digestEnabled,
                digestMinutes = digestMinutes,
                onDigestEnabled = { on -> viewModel.updateSettings { setDigestEnabled(on) } },
                onDigestMinutes = { m -> viewModel.updateSettings { setDigestMinutes(m) } },
            )
        },
        dataSection = {
            Row(Modifier.padding(horizontal = 16.dp)) { TodoBackupButtons() }
        },
    )
}
