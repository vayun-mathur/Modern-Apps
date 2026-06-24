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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.R
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: CalendarViewModel, backStack: NavBackStack<Route>) {
    val calendars by viewModel.calendars.collectAsState()
    val visibility by viewModel.calendarVisibility.collectAsState()

    // state for selection
    var selectedCalendarId by remember { mutableStateOf<Long?>(null) }

    val grouped = calendars.groupBy { it.accountName }

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
                    val currentLayout by viewModel.currentLayout.collectAsState()
                    var showDefaultLayoutMenu by remember { mutableStateOf(false) }

                    ListItem(
                        headlineContent = { Text("Default Layout") },
                        trailingContent = {
                            Box {
                                TextButton(onClick = { showDefaultLayoutMenu = true }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(currentLayout.prettyName)
                                        Icon(painterResource(R.drawable.arrow_drop_down_24px), null)
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
                    ListItem(
                        headlineContent = { Text("Holiday calendars") },
                        supportingContent = { Text("Add public holidays for countries") },
                        modifier = Modifier.clickable { backStack.add(Route.Settings.HolidayCalendars) },
                        trailingContent = {
                            Icon(painterResource(R.drawable.arrow_drop_down_24px), null)
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
                            headlineContent = { Text(cal.displayName) },
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
                                androidx.compose.material3.Checkbox(checked = isChecked, onCheckedChange = { checked -> viewModel.setCalendarVisibility(cal.id, checked) })
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
