package com.vayunmathur.calendar.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.calendar.ContactViewModel
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.util.pop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRenameCalendarDialog(viewModel: ContactViewModel, backStack: NavBackStack<Route>, calendarId: Long) {
    val calendars by viewModel.calendars.collectAsState()
    val cal = calendars.find { it.id == calendarId } ?: run {
        backStack.pop()
        return
    }
    var renameText by remember { mutableStateOf(cal.displayName) }

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text("Rename calendar") },
        text = {
            Column {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New name") },
                    modifier = Modifier
                        .padding(0.dp)
                )
            }
        },
        confirmButton = {
            Button(enabled = renameText.isNotBlank(), onClick = {
                viewModel.renameCalendar(calendarId, renameText)
                backStack.pop()
            }) { Text("Rename") }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text("Cancel") }
        }
    )
}
