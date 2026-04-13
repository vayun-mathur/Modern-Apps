package com.vayunmathur.calendar.ui.dialogs
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.util.ContactViewModel
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDeleteCalendarDialog(viewModel: ContactViewModel, backStack: NavBackStack<Route>, calendarId: Long) {
    val calendars by viewModel.calendars.collectAsState()
    val cal = calendars.find { it.id == calendarId }
    val thisCalendar = stringResource(R.string.this_calendar)

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text(stringResource(R.string.delete_calendar)) },
        text = { Text(stringResource(R.string.delete_calendar_confirm, cal?.displayName ?: thisCalendar)) },
        confirmButton = {
            Button(onClick = {
                viewModel.deleteCalendar(calendarId)
                backStack.pop()
            }) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text(stringResource(R.string.cancel)) }
        }
    )
}
