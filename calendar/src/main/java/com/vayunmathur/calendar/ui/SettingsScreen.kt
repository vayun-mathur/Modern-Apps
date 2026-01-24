package com.vayunmathur.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.calendar.ContactViewModel
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ContactViewModel, backStack: NavBackStack<Route>) {
    val calendars by viewModel.calendars.collectAsState()
    val visibility by viewModel.calendarVisibility.collectAsState()

    // state for selection
    var selectedCalendarId by remember { mutableStateOf<Long?>(null) }

    val grouped = calendars.groupBy { it.accountName }

    Scaffold(
        topBar = {
            TopAppBar({Text("Settings")}, navigationIcon = {
                IconNavigation(backStack)
            }, actions = {
                if(selectedCalendarId != null) {
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
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { backStack.add(Route.Settings.AddCalendar()) }) {
                IconAdd()
            }
        },
        contentWindowInsets = WindowInsets()
    ) { paddingValues ->
        // wrap content in a Box so we can overlay action buttons in the corner
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                grouped.forEach { (account, cals) ->
                    item {
                        Text(text = account.ifEmpty { "(No account)" }, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    items(cals) { cal ->
                        val isSelected = selectedCalendarId == cal.id
                        ListItem(
                            headlineContent = { Text(cal.displayName) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (isSelected) Modifier.background(Color(0x11000000)) else Modifier)
                                .clickable {
                                    // select this calendar (or deselect if already selected)
                                    selectedCalendarId = if (isSelected) null else cal.id
                                },
                            supportingContent = { Text(text = "ID: ${cal.id}") },
                            leadingContent = {
                                // colored circle showing calendar color; clickable to open color picker
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(cal.color))
                                        .border(
                                            width = 1.dp,
                                            color = Color.Black.copy(alpha = 0.12f),
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

            // dialogs have been moved to their own files and are shown via navigation entries
        }
    }
}
