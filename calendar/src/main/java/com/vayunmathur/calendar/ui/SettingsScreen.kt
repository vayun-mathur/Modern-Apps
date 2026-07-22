package com.vayunmathur.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.R
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: CalendarViewModel, backStack: NavBackStack<Route>) {
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val visibility by viewModel.calendarVisibility.collectAsStateWithLifecycle()

    // state for selection
    var selectedCalendarId by remember { mutableStateOf<Long?>(null) }

    val grouped = calendars.groupBy { it.accountName }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            backStack.add(Route.Settings.ImportIcs(uris.map { it.toString() }))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar({Text(stringResource(R.string.settings))}, navigationIcon = {
                IconNavigation(backStack)
            }, actions = {
                if(selectedCalendarId != null) {
                    val selectedCalendar = calendars.find { it.id == selectedCalendarId }
                    if (selectedCalendar?.canModify == true) {
                        IconButton(onClick = {
                            // open rename dialog via navigation
                            backStack.add(Route.Settings.RenameCalendar(selectedCalendarId!!))
                        }) {
                            IconEdit()
                        }
                        IconButton(onClick = { backStack.add(Route.Settings.DeleteCalendar(selectedCalendarId!!)) }) {
                            IconDelete()
                        }
                    }
                }
            })
        },
        floatingActionButton = {
            if (calendars.isNotEmpty()) {
                FloatingActionButton(onClick = { backStack.add(Route.Settings.AddCalendar()) }) {
                    IconAdd()
                }
            }
        }
    ) { paddingValues ->
        if (calendars.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Button(onClick = { backStack.add(Route.Settings.AddCalendar()) }) {
                    Text(text = stringResource(R.string.create_a_calendar))
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = paddingValues + PaddingValues(8.dp)) {
                item {
                    val currentLayout by viewModel.currentLayout.collectAsStateWithLifecycle()
                    var showDefaultLayoutMenu by remember { mutableStateOf(false) }

                    ListItem(
                        content = { Text("Default Layout") },
                        trailingContent = {
                            Box {
                                TextButton(onClick = { showDefaultLayoutMenu = true }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(currentLayout.prettyName)
                                        IconArrowDropDown()
                                    }
                                }
                                DropdownMenu(expanded = showDefaultLayoutMenu, onDismissRequest = { showDefaultLayoutMenu = false }) {
                                    CalendarViewModel.CalendarLayout.entries.forEach { layout ->
                                        DropdownMenuItem(
                                            text = { Text(layout.prettyName) },
                                            onClick = {
                                                viewModel.setLayout(layout)
                                                showDefaultLayoutMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }

                item {
                    val currentTheme by viewModel.themeMode.collectAsStateWithLifecycle()
                    var showThemeMenu by remember { mutableStateOf(false) }

                    ListItem(
                        content = { Text("Theme") },
                        trailingContent = {
                            Box {
                                TextButton(onClick = { showThemeMenu = true }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(currentTheme.prettyName)
                                        IconArrowDropDown()
                                    }
                                }
                                DropdownMenu(expanded = showThemeMenu, onDismissRequest = { showThemeMenu = false }) {
                                    CalendarViewModel.ThemeMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.prettyName) },
                                            onClick = {
                                                viewModel.setThemeMode(mode)
                                                showThemeMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }

                item {
                    ListItem(
                        content = { Text("Holiday calendars") },
                        supportingContent = { Text("Add public holidays for countries") },
                        modifier = Modifier.clickable { backStack.add(Route.Settings.HolidayCalendars) },
                        trailingContent = {
                            IconArrowDropDown()
                        },
                    )
                    HorizontalDivider()
                }

                item {
                    ListItem(
                        content = { Text(stringResource(R.string.import_ics_file)) },
                        supportingContent = { Text("Import events from .ics files") },
                        modifier = Modifier.clickable {
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
                        },
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
                                .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
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
                                            backStack.add(Route.Settings.ChangeColor(cal.id))
                                        } else Modifier)
                                )
                            },
                            trailingContent = {
                                val isChecked = visibility[cal.id] ?: true
                                com.vayunmathur.library.ui.Checkbox(checked = isChecked, onCheckedChange = { checked -> viewModel.setCalendarVisibility(cal.id, checked) })
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
