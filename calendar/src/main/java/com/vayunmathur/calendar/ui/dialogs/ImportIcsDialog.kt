package com.vayunmathur.calendar.ui.dialogs

import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Calendar
import com.vayunmathur.calendar.ui.EventCard
import com.vayunmathur.calendar.util.CalendarViewModel

@Composable
fun ImportIcsDialog(
    viewModel: CalendarViewModel,
    uris: List<Uri>,
    onDismiss: () -> Unit
) {
    val parsedEvents by viewModel.parsedIcsEvents.collectAsState()
    val calendars by viewModel.calendars.collectAsState()
    var selectedCalendar by remember { mutableStateOf<Calendar?>(null) }
    var showDropdown by remember { mutableStateOf(false) }
    var showAddCalendar by remember { mutableStateOf(false) }
    var newCalName by remember { mutableStateOf("") }

    // Kick off parsing in the VM (Dispatchers.IO). Re-keying on `uris` ensures
    // we re-parse when the caller passes a new file set.
    LaunchedEffect(uris) {
        viewModel.parseIcsUris(uris)
    }

    // Clear VM-held parsed state when the dialog leaves composition.
    DisposableEffect(Unit) {
        onDispose { viewModel.clearParsedIcs() }
    }

    // Auto-dismiss when parsing completes and yielded no events.
    LaunchedEffect(parsedEvents) {
        if (parsedEvents != null && parsedEvents!!.isEmpty() && uris.isNotEmpty()) {
            onDismiss()
        }
    }

    if (showAddCalendar) {
        AlertDialog(
            onDismissRequest = { showAddCalendar = false },
            title = { Text("New Local Calendar") },
            text = {
                TextField(
                    value = newCalName,
                    onValueChange = { newCalName = it },
                    label = { Text("Calendar Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newCalName.isNotBlank()) {
                        viewModel.createLocalCalendar(
                            "Offline Calendar",
                            newCalName,
                            0xFF2196F3.toInt(),
                            true,
                            CalendarContract.Calendars.CAL_ACCESS_EDITOR,
                        )
                        showAddCalendar = false
                        newCalName = ""
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCalendar = false }) { Text("Cancel") }
            }
        )
    }

    val events = parsedEvents
    if (events == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            title = { Text("Loading events...") },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() } }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Import Events") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text("Select calendar to import to:")
                    Spacer(Modifier.height(8.dp))
                    
                    Box {
                        val editable = calendars.filter(Calendar::canModify)
                        val grouped = editable.groupBy { it.accountName.ifEmpty { "(Local)" } }
                        
                        ListItem(
                            headlineContent = { Text(selectedCalendar?.displayName ?: "Select Calendar") },
                            leadingContent = {
                                selectedCalendar?.color?.let { Box(Modifier.size(24.dp).background(Color(it), RectangleShape)) }
                            },
                            trailingContent = {
                                Icon(painterResource(R.drawable.arrow_drop_down_24px), contentDescription = null)
                            },
                            modifier = Modifier.clickable { showDropdown = true }
                        )
                        
                        DropdownMenu(expanded = showDropdown, onDismissRequest = { showDropdown = false }) {
                            grouped.forEach { (account, cals) ->
                                DropdownMenuItem(text = { Text(account) }, onClick = {}, enabled = false)
                                cals.forEach { cal ->
                                    DropdownMenuItem(
                                        text = { Text(cal.displayName) },
                                        leadingIcon = { Box(Modifier.size(16.dp).background(Color(cal.color), RectangleShape)) },
                                        onClick = {
                                            selectedCalendar = cal
                                            showDropdown = false
                                        }
                                    )
                                }
                            }
                            Divider()
                            DropdownMenuItem(
                                text = { Text("+ Create new calendar") },
                                onClick = {
                                    showAddCalendar = true
                                    showDropdown = false
                                }
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(events, key = { "${it.calendarID}|${it.start}|${it.title}" }) { event ->
                            EventCard(event = event)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedCalendar != null,
                    onClick = {
                        val calId = selectedCalendar?.id ?: return@TextButton
                        viewModel.importIcsEvents(events, calId, onDone = onDismiss)
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}
