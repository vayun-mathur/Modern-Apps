package com.vayunmathur.calendar.ui

import android.content.ContentValues
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.calendar.ContactViewModel
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.RRule
import com.vayunmathur.calendar.RecurrenceParams
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.library.util.pop
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

// Result keys for the date/time pickers
private const val KEY_START_DATE = "EditEvent.startDate"
private const val KEY_END_DATE = "EditEvent.endDate"
private const val KEY_START_TIME = "EditEvent.startTime"
private const val KEY_END_TIME = "EditEvent.endTime"
private const val KEY_RECURRENCE = "EditEvent.recurrence"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEventScreen(viewModel: ContactViewModel, eventId: Long?, backStack: NavBackStack<Route>) {
    val events by viewModel.events.collectAsState()
    val calendars by viewModel.calendars.collectAsState()

    val event = events.find { it.id == eventId }

    val znow = ZonedDateTime.now(ZoneId.systemDefault())
    val today = LocalDate(znow.year, znow.monthValue, znow.dayOfMonth)
    val now = LocalTime(znow.hour, znow.minute)

    var title by remember { mutableStateOf(event?.title ?: "") }
    var description by remember { mutableStateOf(event?.description ?: "") }
    var location by remember { mutableStateOf(event?.location ?: "") }
    // default to the event's calendar if editing; otherwise prefer the first editable calendar
    var selectedCalendar by remember { mutableStateOf(event?.calendarID ?: (calendars.firstOrNull { it.canModify }?.id ?: calendars.firstOrNull()?.id ?: -1L)) }
    // If calendars load/refresh after composition, ensure the default remains an editable calendar when creating a new event
    LaunchedEffect(calendars) {
        if (event == null) {
            val current = selectedCalendar
            val currentIsEditable = calendars.any { it.id == current && it.canModify }
            if (!currentIsEditable) {
                val editable = calendars.firstOrNull { it.canModify } ?: calendars.firstOrNull()
                if (editable != null) selectedCalendar = editable.id
            }
        }
    }
    var allDay by remember { mutableStateOf(event?.allDay ?: false) }
    var startDate by remember { mutableStateOf(event?.startDateTimeDisplay?.date ?: today) }
    var endDate by remember { mutableStateOf(event?.endDateTimeDisplay?.date ?: today) }
    var startTime by remember { mutableStateOf(event?.startDateTimeDisplay?.time ?: now) }
    var endTime by remember { mutableStateOf(event?.endDateTimeDisplay?.time ?: now) }
    var timezone by remember { mutableStateOf(event?.timezone ?: TimeZone.currentSystemDefault().id) }
    var rruleObj by remember { mutableStateOf(event?.rrule) }
    val rruleString by remember { derivedStateOf {rruleObj?.toString(startDate) ?: ""} }

    // Collect results from pickers
    ResultEffect<LocalDate>(KEY_START_DATE) { selected ->
        // preserve duration between old start and end
        val tz = TimeZone.of(timezone)
        val oldStart = startDate.atTime(startTime).toInstant(tz)
        val oldEnd = endDate.atTime(endTime).toInstant(tz)
        var dur = oldEnd - oldStart
        if (dur.isNegative()) dur = Duration.ZERO

        startDate = selected

        val newStart = startDate.atTime(startTime).toInstant(tz)
        val newEnd = newStart + dur
        val newEndLdt = newEnd.toLocalDateTime(tz)
        endDate = newEndLdt.date
        endTime = newEndLdt.time
    }

    ResultEffect<LocalDate>(KEY_END_DATE) { selected ->
        // ensure end date is not before start date
        if (selected < startDate) {
            endDate = startDate
        } else {
            endDate = selected
        }
    }

    ResultEffect<LocalTime>(KEY_START_TIME) { selected ->
        // preserve duration between old start and end
        val tz = TimeZone.of(timezone)
        val oldStart = startDate.atTime(startTime).toInstant(tz)
        val oldEnd = endDate.atTime(endTime).toInstant(tz)
        var dur = oldEnd - oldStart
        if (dur.isNegative()) dur = Duration.ZERO

        startTime = selected

        val newStart = startDate.atTime(startTime).toInstant(tz)
        val newEnd = newStart + dur
        val newEndLdt = newEnd.toLocalDateTime(tz)
        endDate = newEndLdt.date
        endTime = newEndLdt.time
    }

    ResultEffect<LocalTime>(KEY_END_TIME) { selected ->
        // ensure end time is not before start time when on same date
        if (endDate == startDate) {
            val before = selected.hour < startTime.hour || (selected.hour == startTime.hour && selected.minute < startTime.minute)
            if (before) {
                // clamp to startTime
                endTime = startTime
            } else {
                endTime = selected
            }
        } else {
            endTime = selected
        }
    }

    // Recurrence dialog result: receives an RRULE string or empty string
    ResultEffect<RRule>(KEY_RECURRENCE) { res ->
        rruleObj = res
    }

    // Result key for calendar picker
    val KEY_CALENDAR = "EditEvent.calendar"
    // open dialog via navigation and handle result
    ResultEffect<Long>(KEY_CALENDAR) { calId ->
        selectedCalendar = calId
    }

    // Timezone selector (navigation dialog) - open via Nav route and handle result
    val KEY_TIMEZONE = "EditEvent.timezone"
    ResultEffect<String>(KEY_TIMEZONE) { z -> timezone = z }
    if (!allDay) {
        Item(
            { Icon(painterResource(R.drawable.globe_24px), null) },
            { Text(timezone, Modifier.clickable { backStack.add(Route.EditEvent.TimezonePickerDialog(KEY_TIMEZONE)) }) }
        )
    }

    Scaffold(topBar = {
        TopAppBar({}, navigationIcon = {
            IconButton({
                backStack.pop()
            }) {
                Icon(painterResource(R.drawable.arrow_back_24px), contentDescription = "Back")
            }
        })
    }, floatingActionButton = {
        FloatingActionButton(onClick = {
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.CALENDAR_ID, selectedCalendar)
                val tz = if(allDay) "UTC" else timezone
                val dtstart = startDate.atTime(startTime).toInstant(TimeZone.of(tz)).toEpochMilliseconds()
                val dtendActual = endDate.atTime(endTime).toInstant(TimeZone.of(tz)).toEpochMilliseconds()
                put(CalendarContract.Events.DTSTART, dtstart)
                if (rruleObj != null) {
                    // For recurring events, DTEND must be 0 and DURATION set to the event length
                    put(CalendarContract.Events.DTEND, null as Long?)
                    var duration = (dtendActual - dtstart).milliseconds
                    if(allDay) duration += 1.days
                    put(CalendarContract.Events.DURATION, duration.toIsoString())
                    put(CalendarContract.Events.RRULE, rruleObj!!.asString(startDate, TimeZone.of(timezone)))
                } else {
                    put(CalendarContract.Events.DTEND, dtendActual)
                    // clear DURATION and RRULE if present
                    put(CalendarContract.Events.DURATION, null as String?)
                    put(CalendarContract.Events.RRULE, null as String?)
                }
                put(CalendarContract.Events.ALL_DAY, if(allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            }
            viewModel.upsertEvent(eventId, values)
            backStack.pop()
        }) {
            IconSave()
        }
    }, contentWindowInsets = WindowInsets()) { paddingValues ->
        Column(Modifier.padding(paddingValues).verticalScroll(rememberScrollState())) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().padding(8.dp), label = { Text("Title") })

            // Calendar selector: moved above the datetime section — only when creating a new event
            if (eventId == null) {
                Item(
                    { Box(modifier = Modifier.size(24.dp).background(Color(calendars.find { it.id == selectedCalendar }?.color ?: 0))) },
                    { Text(calendars.find { it.id == selectedCalendar }?.displayName ?: "Select calendar", Modifier.clickable { backStack.add(Route.EditEvent.CalendarPickerDialog(KEY_CALENDAR)) }) },
                    {}
                )
            }

            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth().padding(8.dp), label = { Text("Description") })
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Item(
                { Icon(painterResource(R.drawable.nest_clock_farsight_analog_24px), null) },
                {Text("All-day")},
                { Switch(allDay, { allDay = it }) }
            )

            // Recurrence selector
            Item(
                { /* icon placeholder */ },
                { Text(if (rruleObj == null) "Does not repeat" else rruleString.ifBlank { "Repeats" }, Modifier.clickable {
                    // pass initial RecurrenceParams based on existing rrule
                    val initial = RecurrenceParams.fromRRule(rruleObj)
                    backStack.add(Route.EditEvent.RecurrenceDialog(KEY_RECURRENCE, startDate, initial))
                }) },
                { if (rruleObj != null) Text("Remove", Modifier.clickable {
                    rruleObj = null
                }) }
            )

            Item(
                {},
                { Text(startDate.format(dateFormat), Modifier.clickable {
                    // open date picker dialog
                    backStack.add(Route.EditEvent.DatePickerDialog(KEY_START_DATE, startDate))
                }) },
                { if(!allDay) Text(startTime.format(timeFormat), Modifier.clickable {
                    // open time picker dialog
                    // no min time for start
                    backStack.add(Route.EditEvent.TimePickerDialog(KEY_START_TIME, startTime, null))
                }) }
            )
            Item(
                {},
                { Text(endDate.format(dateFormat), Modifier.clickable {
                    // when opening end date, prevent selecting a date before startDate
                    backStack.add(Route.EditEvent.DatePickerDialog(KEY_END_DATE, endDate, startDate))
                }) },
                { if(!allDay) Text(endTime.format(timeFormat), Modifier.clickable{
                    // when opening end time, supply minTime if endDate equals startDate
                    val minTime = if (endDate == startDate) startTime else null
                    backStack.add(Route.EditEvent.TimePickerDialog(KEY_END_TIME, endTime, minTime))
                }) }
            )

            // Timezone selector (navigation dialog) - open via Nav route and handle result
            val KEY_TIMEZONE = "EditEvent.timezone"
            ResultEffect<String>(KEY_TIMEZONE) { z -> timezone = z }
            if (!allDay) {
                Item(
                    { Box(modifier = Modifier.size(24.dp).background(Color.Transparent)) { Icon(painterResource(R.drawable.globe_24px), null) } },
                    { Text(timezone, Modifier.clickable { backStack.add(Route.EditEvent.TimezonePickerDialog(KEY_TIMEZONE)) }) }
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            OutlinedTextField(location, { location = it }, Modifier.fillMaxWidth().padding(8.dp), label = { Text("Location") })
        }
    }
}

@Composable
fun Item(icon: @Composable () -> Unit = {}, left: @Composable () -> Unit, right: @Composable () -> Unit = {}) {
    Row(Modifier.padding(8.dp).padding(horizontal = 8.dp).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
            Box(Modifier.size(24.dp)) {
                icon()
            }
            Spacer(Modifier.width(24.dp))
            Box(Modifier.weight(1f)) {
                left()
            }
            right()
        }
    }
}

val dateFormat = LocalDate.Format {
    dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
    chars(", ")
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    chars(" ")
    day(Padding.NONE)
    chars(", ")
    year(Padding.NONE)
}

val timeFormat = LocalTime.Format {
    amPmHour(Padding.NONE)
    chars(":")
    minute()
    chars(" ")
    amPmMarker("AM", "PM")
}