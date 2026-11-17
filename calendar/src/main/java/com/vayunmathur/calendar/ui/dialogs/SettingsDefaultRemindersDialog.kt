package com.vayunmathur.calendar.ui.dialogs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.ui.REMINDER_PRESETS
import com.vayunmathur.calendar.ui.reminderLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDefaultRemindersDialog(viewModel: CalendarViewModel, backStack: NavBackStack<Route>, calendarId: Long) {
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val cal = calendars.find { it.id == calendarId } ?: run {
        backStack.pop()
        return
    }
    if (!cal.canModify) {
        backStack.pop()
        return
    }
    val context = LocalContext.current

    var selected by remember { mutableStateOf(viewModel.getDefaultReminders(calendarId).toSet()) }
    var applyToExisting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text(stringResource(R.string.default_reminders)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.default_reminders_description, cal.displayName),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                REMINDER_PRESETS.forEach { minutes ->
                    val checked = minutes in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (checked) selected - minutes else selected + minutes
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { isChecked ->
                                selected = if (isChecked) selected + minutes else selected - minutes
                            },
                        )
                        Text(reminderLabel(context, minutes), Modifier.padding(start = 8.dp))
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.apply_to_existing_events), Modifier.weight(1f))
                    Switch(applyToExisting, { applyToExisting = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.setDefaultReminders(calendarId, selected.toList(), applyToExisting)
                backStack.pop()
            }) { Text(stringResource(UiR.string.save)) }
        },
        dismissButton = {
            Button(onClick = { backStack.pop() }) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}
