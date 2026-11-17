package com.vayunmathur.calendar.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.Route
import com.vayunmathur.calendar.domain.RRule
import com.vayunmathur.calendar.domain.RecurrenceParams
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import com.vayunmathur.library.ui.R as UiR

@Composable
internal fun EditEventDateTimeSection(
    allDay: Boolean,
    onAllDay: (Boolean) -> Unit,
    startDate: LocalDate,
    endDate: LocalDate,
    startTime: LocalTime,
    endTime: LocalTime,
    timezone: String,
    rruleObj: RRule?,
    rdateObj: List<LocalDate>,
    repeatSummary: String,
    onRecurrence: (RRule?, List<LocalDate>) -> Unit,
    backStack: NavBackStack<Route>,
    startDateKey: String,
    endDateKey: String,
    startTimeKey: String,
    endTimeKey: String,
    recurrenceKey: String,
    timezoneKey: String,
) {
    val context = LocalContext.current
    Item(
        { IconSchedule() },
        { Text(stringResource(R.string.all_day)) },
        { Switch(allDay, onAllDay) }
    )

    // Recurrence selector
    val repeats = rruleObj != null || rdateObj.isNotEmpty()
    Item(
        { /* icon placeholder */ },
        { Text(if (!repeats) stringResource(R.string.does_not_repeat) else repeatSummary.ifBlank { stringResource(R.string.repeats) }, Modifier.clickable {
            // pass initial RecurrenceParams based on existing rrule
            val initial = RecurrenceParams.fromRRule(rruleObj)
            backStack.add(Route.EditEvent.RecurrenceDialog(recurrenceKey, startDate, initial, rdateObj))
        }) },
        { if (repeats) Text(stringResource(UiR.string.remove), Modifier.clickable {
            onRecurrence(null, emptyList())
        }) }
    )

    Item(
        {},
        { Text(DateString.dateWeekday(startDate), Modifier.clickable {
            // open date picker dialog
            backStack.add(Route.EditEvent.DatePickerDialog(startDateKey, startDate))
        }) },
        { if(!allDay) Text(DateString.time(startTime, DateFormat.is24HourFormat(context)), Modifier.clickable {
            // open time picker dialog
            // no min time for start
            backStack.add(Route.EditEvent.TimePickerDialog(startTimeKey, startTime, null))
        }) }
    )
    Item(
        {},
        { Text(DateString.dateWeekday(endDate), Modifier.clickable {
            // when opening end date, prevent selecting a date before startDate
            backStack.add(Route.EditEvent.DatePickerDialog(endDateKey, endDate, startDate))
        }) },
        { if(!allDay) Text(DateString.time(endTime, DateFormat.is24HourFormat(context)), Modifier.clickable{
            // when opening end time, supply minTime if endDate equals startDate
            val minTime = if (endDate == startDate) startTime else null
            backStack.add(Route.EditEvent.TimePickerDialog(endTimeKey, endTime, minTime))
        }) }
    )

    if (!allDay) {
        Item(
            { Box(modifier = Modifier.size(24.dp).background(Color.Transparent)) { IconGlobe() } },
            { Text(timezone, Modifier.clickable { backStack.add(Route.EditEvent.TimezonePickerDialog(timezoneKey)) }) }
        )
    }
}
