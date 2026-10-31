package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSelectRow
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.domain.SortMode
import com.vayunmathur.todo.domain.TodoView

/** Everything the settings screen draws. */
data class SettingsUiState(
    val defaultView: TodoView = TodoView.Today,
    val defaultSort: SortMode = SortMode.MANUAL,
    val showCompleted: Boolean = true,
    val lists: List<TodoList> = emptyList(),
)

interface SettingsActions {
    fun back() {}
    fun setDefaultView(view: TodoView) {}
    fun setDefaultSort(sort: SortMode) {}
    fun setShowCompleted(show: Boolean) {}
}

/**
 * App settings. Sections owned by other features (reminders, calendar, data) arrive as
 * slots so this screen stays previewable without their dependencies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    extraSections: @Composable () -> Unit = {},
    dataSection: @Composable () -> Unit = {},
) {
    DetailScaffold(
        title = stringResource(R.string.settings),
        onNavigateBack = actions::back,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        SettingsSection(title = stringResource(R.string.settings_general)) {
            val views = TodoView.smartViews.filter { it != TodoView.Completed } + state.lists.map { TodoView.InList(it.syncId) }
            val titles = views.associateWith { viewTitle(it, state.lists) }
            SettingsSelectRow(
                title = stringResource(R.string.settings_start_view),
                selected = state.defaultView,
                options = views,
                label = { titles[it].orEmpty() },
                onSelect = actions::setDefaultView,
            )
            val sortLabels = SortMode.entries.associateWith { stringResource(sortLabel(it)) }
            SettingsSelectRow(
                title = stringResource(R.string.settings_default_sort),
                selected = state.defaultSort,
                options = SortMode.entries,
                label = { sortLabels.getValue(it) },
                onSelect = actions::setDefaultSort,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_show_completed),
                checked = state.showCompleted,
                onCheckedChange = actions::setShowCompleted,
            )
        }
        SettingsDivider()
        extraSections()
        SettingsSection(title = stringResource(R.string.settings_data)) {
            dataSection()
        }
    }
}
