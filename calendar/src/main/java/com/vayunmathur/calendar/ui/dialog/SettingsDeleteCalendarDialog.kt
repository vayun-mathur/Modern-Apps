package com.vayunmathur.calendar.ui.dialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.calendar.ContactViewModel
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.util.pop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDeleteCalendarDialog(viewModel: ContactViewModel, backStack: NavBackStack<Route>, calendarId: Long) {
    val calendars by viewModel.calendars.collectAsState()
    val cal = calendars.find { it.id == calendarId }

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text("Delete calendar") },
        text = { Text(text = "Are you sure you want to delete \"${cal?.displayName ?: "this calendar"}\"? This will remove all events in the calendar.") },
        confirmButton = {
            Button(onClick = {
                viewModel.deleteCalendar(calendarId)
                backStack.pop()
            }) { Text("Delete") }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text("Cancel") }
        }
    )
}
