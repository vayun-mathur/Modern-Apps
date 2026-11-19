package com.vayunmathur.clock.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.clock.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.platform.AlarmScheduler
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import com.vayunmathur.library.ui.rememberTimePickerState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAlarmDialog(
    backStack: NavBackStack<Route>,
    clockViewModel: ClockViewModel,
    initialHour: Int?,
    initialMinutes: Int?,
    initialMessage: String?,
    initialDays: ArrayList<Int>?,
) {
    var nameState by remember { mutableStateOf(TextFieldValue(initialMessage ?: "")) }
    val context = LocalContext.current
    val nowTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour ?: nowTime.hour,
        initialMinute = initialMinutes ?: nowTime.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text(stringResource(R.string.label_alarm)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.field_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedTextField(
                        value = nameState,
                        onValueChange = { nameState = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.alarm_label_hint)) },
                        singleLine = true,
                    )
                }
                TimePicker(state = timePickerState)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val time = LocalTime(timePickerState.hour, timePickerState.minute)
                    var daysMask = 0
                    // Only Calendar.SUNDAY..SATURDAY (1..7) are weekdays;
                    // other values arriving via the SET_ALARM intent are ignored.
                    initialDays?.forEach { day ->
                        if (day in 1..7) daysMask = daysMask or (1 shl (day - 1))
                    }
                    val newAlarm = clockViewModel.buildDefaultAlarm(time, nameState.text, daysMask)
                    clockViewModel.upsert(newAlarm) { id ->
                        AlarmScheduler.schedule(context, newAlarm.copy(id = id))
                    }
                    backStack.pop()
                },
            ) {
                Text(stringResource(UiR.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = { backStack.pop() }) {
                Text(stringResource(UiR.string.cancel))
            }
        },
    )
}
