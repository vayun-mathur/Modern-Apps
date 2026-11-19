package com.vayunmathur.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSelectRow
import com.vayunmathur.library.ui.Text

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.calendar.platform.SettingsActions
import com.vayunmathur.calendar.platform.SettingsUiState
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.R
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconSchedule

/** Binds [CalendarViewModel] to the stateless [SettingsScreen]. */
@Composable
fun SettingsScreen(viewModel: CalendarViewModel, backStack: NavBackStack<Route>) {
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val visibility by viewModel.calendarVisibility.collectAsStateWithLifecycle()
    val currentLayout by viewModel.currentLayout.collectAsStateWithLifecycle()
    val currentTheme by viewModel.themeMode.collectAsStateWithLifecycle()

    // Kept in the binder rather than the screen: rememberLauncherForActivityResult needs an
    // ActivityResultRegistryOwner, which a @Preview has no way to supply.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            backStack.add(Route.Settings.ImportIcs(uris.map { it.toString() }))
        }
    }

    // Which calendar the pending export is for, since the contract only hands back a Uri.
    // null means every visible calendar. Saveable because the document picker is another activity,
    // and losing the id would silently widen the export to every calendar.
    var exportCalendarId by rememberSaveable { mutableStateOf<Long?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri ->
        if (uri != null) viewModel.exportIcs(uri, exportCalendarId)
    }

    SettingsScreen(
        state = SettingsUiState(
            calendars = calendars,
            calendarVisibility = visibility,
            layout = currentLayout,
            themeMode = currentTheme,
        ),
        actions = object : SettingsActions by viewModel {
            override fun closeSettings() {
                backStack.pop()
            }

            override fun openAddCalendar() {
                backStack.add(Route.Settings.AddCalendar())
            }

            override fun openRenameCalendar(calendarId: Long) {
                backStack.add(Route.Settings.RenameCalendar(calendarId))
            }

            override fun openDeleteCalendar(calendarId: Long) {
                backStack.add(Route.Settings.DeleteCalendar(calendarId))
            }

            override fun openChangeColor(calendarId: Long) {
                backStack.add(Route.Settings.ChangeColor(calendarId))
            }

            override fun openDefaultReminders(calendarId: Long) {
                backStack.add(Route.Settings.DefaultReminders(calendarId))
            }

            override fun openHolidayCalendars() {
                backStack.add(Route.Settings.HolidayCalendars)
            }

            override fun importIcs() {
                importLauncher.launch(arrayOf(
                    "text/calendar",
                    "application/calendar",
                    "application/ics",
                    "text/x-vcalendar",
                    "application/x-icalendar",
                    "text/x-icalendar",
                    "text/icalendar",
                    "*/*"
                ))
            }

            override fun exportIcs() {
                exportCalendarId = null
                exportLauncher.launch("calendar.ics")
            }

            override fun exportCalendarIcs(calendarId: Long) {
                exportCalendarId = calendarId
                exportLauncher.launch("calendar-$calendarId.ics")
            }
        },
    )
}

/**
 * The settings screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions) {
    val calendars = state.calendars

    // state for selection
    var selectedCalendarId by remember { mutableStateOf<Long?>(null) }

    val grouped = calendars.groupBy { it.accountName }

    AppScaffold(
        title = stringResource(UiR.string.settings),
        onNavigateBack = actions::closeSettings,
        actions = {
            if(selectedCalendarId != null) {
                val selectedCalendar = calendars.find { it.id == selectedCalendarId }
                // Not gated on canModify: a read-only calendar is still exportable.
                IconButton(onClick = { actions.exportCalendarIcs(selectedCalendarId!!) }) {
                    IconDownload()
                }
                if (selectedCalendar?.canModify == true) {
                    IconButton(onClick = { actions.openDefaultReminders(selectedCalendarId!!) }) {
                        IconSchedule()
                    }
                    IconButton(onClick = {
                        // open rename dialog via navigation
                        actions.openRenameCalendar(selectedCalendarId!!)
                    }) {
                        IconEdit()
                    }
                    IconButton(onClick = { actions.openDeleteCalendar(selectedCalendarId!!) }) {
                        IconDelete()
                    }
                }
            }
        },
        floatingActionButton = {
            if (calendars.isNotEmpty()) {
                FloatingActionButton(onClick = { actions.openAddCalendar() }) {
                    IconAdd()
                }
            }
        },
    ) { paddingValues ->
        if (calendars.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Button(onClick = { actions.openAddCalendar() }) {
                    Text(text = stringResource(R.string.create_a_calendar))
                }
            }
        } else {
            val layoutLabels = CalendarViewModel.CalendarLayout.entries.associateWith { stringResource(it.prettyNameRes) }
            val themeLabels = CalendarViewModel.ThemeMode.entries.associateWith { stringResource(it.prettyNameRes) }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = paddingValues + PaddingValues(8.dp)) {
                item {
                    SettingsSection {
                        SettingsSelectRow<CalendarViewModel.CalendarLayout>(
                            title = stringResource(R.string.default_layout),
                            selected = state.layout,
                            options = CalendarViewModel.CalendarLayout.entries,
                            label = { layoutLabels.getValue(it) },
                            onSelect = { actions.setLayout(it) },
                        )
                        SettingsDivider()
                        SettingsSelectRow<CalendarViewModel.ThemeMode>(
                            title = stringResource(R.string.theme),
                            selected = state.themeMode,
                            options = CalendarViewModel.ThemeMode.entries,
                            label = { themeLabels.getValue(it) },
                            onSelect = { actions.setThemeMode(it) },
                        )
                        SettingsDivider()
                    }
                }

                item {
                    ListItem(
                        content = { Text(stringResource(R.string.holiday_calendars)) },
                        supportingContent = { Text(stringResource(R.string.add_public_holidays_for_countries)) },
                        modifier = Modifier.clickable { actions.openHolidayCalendars() },
                        trailingContent = {
                            IconArrowDropDown()
                        },
                    )
                    HorizontalDivider()
                }

                item {
                    ListItem(
                        content = { Text(stringResource(R.string.import_ics_file)) },
                        supportingContent = { Text(stringResource(R.string.import_events_from_ics_files)) },
                        modifier = Modifier.clickable { actions.importIcs() },
                        trailingContent = {
                            IconArrowDropDown()
                        },
                    )
                    HorizontalDivider()
                }

                item {
                    ListItem(
                        content = { Text(stringResource(R.string.export_ics_file)) },
                        supportingContent = { Text(stringResource(R.string.export_events_to_ics_file)) },
                        modifier = Modifier.clickable { actions.exportIcs() },
                        trailingContent = {
                            IconArrowDropDown()
                        },
                    )
                    HorizontalDivider()
                }

                grouped.forEach { (account, cals) ->
                    item {
                        Text(text = account.ifEmpty { stringResource(R.string.no_account) }, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    items(cals, key = { it.id }) { cal ->
                        val isSelected = selectedCalendarId == cal.id
                        ListItem(
                            content = { Text(cal.displayName) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // select this calendar (or deselect if already selected)
                                    selectedCalendarId = if (isSelected) null else cal.id
                                },
                            supportingContent = { Text(text = stringResource(R.string.calendar_id_label, cal.id)) },
                            leadingContent = {
                                // colored circle showing calendar color; clickable to open color picker
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(cal.color))
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                            shape = CircleShape
                                        )
                                        .then(if (cal.canModify) Modifier.clickable {
                                            // navigate to color change dialog
                                            actions.openChangeColor(cal.id)
                                        } else Modifier)
                                )
                            },
                            trailingContent = {
                                val isChecked = state.calendarVisibility[cal.id] ?: true
                                com.vayunmathur.library.ui.Checkbox(checked = isChecked, onCheckedChange = { checked -> actions.setCalendarVisibility(cal.id, checked) })
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = if(selectedCalendarId == cal.id) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                            )
                        )
                        HorizontalDivider()
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(64.dp))
                }
            }
        }
    }
}
