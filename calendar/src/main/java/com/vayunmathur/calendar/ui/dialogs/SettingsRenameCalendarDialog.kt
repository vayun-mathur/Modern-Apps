package com.vayunmathur.calendar.ui.dialogs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRenameCalendarDialog(viewModel: CalendarViewModel, backStack: NavBackStack<Route>, calendarId: Long) {
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val cal = calendars.find { it.id == calendarId } ?: run {
        backStack.pop()
        return
    }
    if (!cal.canModify) {
        backStack.pop()
        return
    }
    var renameText by remember { mutableStateOf(cal.displayName) }

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text(stringResource(R.string.rename_calendar)) },
        text = {
            Column {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.new_name)) },
                    modifier = Modifier
                        .padding(0.dp)
                )
            }
        },
        confirmButton = {
            Button(enabled = renameText.isNotBlank(), onClick = {
                viewModel.renameCalendar(calendarId, renameText)
                backStack.pop()
            }) { Text(stringResource(UiR.string.rename)) }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text(stringResource(UiR.string.cancel)) }
        }
    )
}
