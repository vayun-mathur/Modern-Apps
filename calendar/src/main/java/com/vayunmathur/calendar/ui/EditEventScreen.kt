package com.vayunmathur.calendar.ui

import com.vayunmathur.library.ui.R as UiR
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.ProvideTextStyle
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.platform.describe
import com.vayunmathur.calendar.domain.RRule
import com.vayunmathur.calendar.domain.RecurrenceDates
import com.vayunmathur.calendar.domain.RecurrenceParams
import com.vayunmathur.calendar.Route
import com.vayunmathur.library.util.ResultEffect
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.appBarScrollBehavior

// Result keys for the date/time pickers
private const val KEY_START_DATE = "EditEvent.startDate"
private const val KEY_END_DATE = "EditEvent.endDate"
private const val KEY_START_TIME = "EditEvent.startTime"
private const val KEY_END_TIME = "EditEvent.endTime"
private const val KEY_RECURRENCE = "EditEvent.recurrence"
private const val KEY_CALENDAR = "EditEvent.calendar"
private const val KEY_TIMEZONE = "EditEvent.timezone"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEventScreen(viewModel: CalendarViewModel, editRoute: Route.EditEvent, backStack: NavBackStack<Route>) {
    val eventId = editRoute.id
    val events by viewModel.events.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()

    val context = LocalContext.current

    val event = events.find { it.id == eventId }

    // The event list loads async: first composition usually seeds from null, then the
    // event arrives. Every seeded remember below is keyed on the loaded id so the
    // blank first-composition values reset instead of overwriting the event.
    val znow = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val today = znow.date
    val now = znow.time

    var title by remember(event?.id) { mutableStateOf(event?.title ?: editRoute.title ?: "") }
    var descriptionText by remember(event?.id) { mutableStateOf(event?.description ?: editRoute.description ?: "") }
    // Keyed like the state above so an async-loaded event replaces the blank controller.
    val descriptionController = key(event?.id) {
        com.vayunmathur.library.ui.rememberOdfMarkdownEditorController(initialMarkdown = descriptionText) { descriptionText = it }
    }
    var location by remember(event?.id) { mutableStateOf(event?.location ?: editRoute.location ?: "") }
    // default to the event's calendar if editing; otherwise the last calendar the user picked, then first editable
    var selectedCalendar by remember(event?.id) {
        mutableLongStateOf(
            event?.calendarID
                ?: viewModel.getDefaultCalendarId()?.takeIf { id -> calendars.any { it.id == id && it.canModify } }
                ?: calendars.firstOrNull { it.canModify }?.id ?: calendars.firstOrNull()?.id ?: -1L
        )
    }
    // If calendars load/refresh after composition, ensure the default remains a valid, editable calendar when creating a new event
    LaunchedEffect(calendars) {
        if (event == null) {
            val current = selectedCalendar
            val currentIsEditable = calendars.any { it.id == current && it.canModify }
            if (!currentIsEditable) {
                val fallback = viewModel.getDefaultCalendarId()?.takeIf { id -> calendars.any { it.id == id && it.canModify } }
                    ?: calendars.firstOrNull { it.canModify }?.id ?: calendars.firstOrNull()?.id
                if (fallback != null) selectedCalendar = fallback
            }
        }
    }

    val initialBeginLdt = editRoute.beginTime?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()) }
    val initialEndLdt = editRoute.endTime?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()) }
        ?: initialBeginLdt?.let {
            val tz = TimeZone.currentSystemDefault()
            it.toInstant(tz).plus(30.minutes).toLocalDateTime(tz)
        }

    var allDay by remember(event?.id) { mutableStateOf(event?.allDay ?: editRoute.allDay ?: false) }
    var startDate by remember(event?.id) { mutableStateOf(event?.startDateTimeDisplay?.date ?: initialBeginLdt?.date ?: today) }
    var startTime by remember(event?.id) { mutableStateOf(event?.startDateTimeDisplay?.time ?: initialBeginLdt?.time ?: now) }
    // A new event with no supplied end defaults to 30 minutes long (the old fallback reused
    // startTime, producing a 0-minute event). Rolls to the next day when past midnight.
    val defaultEndLdt = remember(event?.id) {
        if (event != null || initialEndLdt != null) null
        else (startDate.atTime(startTime).toInstant(TimeZone.currentSystemDefault()) + 30.minutes)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    }
    // Stored all-day end is exclusive (midnight after the last day), so show the last covered day.
    var endDate by remember(event?.id) {
        mutableStateOf(
            event?.let { if (it.allDay) it.endDateTimeDisplay.date.minus(DatePeriod(days = 1)) else it.endDateTimeDisplay.date }
                ?: initialEndLdt?.date ?: defaultEndLdt?.date ?: startDate
        )
    }
    var endTime by remember(event?.id) { mutableStateOf(event?.endDateTimeDisplay?.time ?: initialEndLdt?.time ?: defaultEndLdt?.time ?: startTime) }
    // All-day events are stored in UTC as a provider/RFC 5545 artifact, not a real user zone.
    // Seed the picker with the device zone so switching to a timed event doesn't reinterpret the
    // wall-clock time in UTC (which the day/week/month views would then shift by the local offset).
    var timezone by remember(event?.id) {
        mutableStateOf(
            if (event?.allDay == true) TimeZone.currentSystemDefault().id
            else event?.timezone ?: TimeZone.currentSystemDefault().id
        )
    }
    var rruleObj by remember(event?.id) { mutableStateOf(event?.rrule) }
    var rdateObj by remember(event?.id) { mutableStateOf(event?.rdate ?: emptyList()) }
    // The event's own date counts as an occurrence alongside the picked ones.
    val rdateSummary = pluralStringResource(R.plurals.repeat_dates_summary, rdateObj.size + 1, rdateObj.size + 1)
    val repeatSummary by remember {
        derivedStateOf {
            when {
                rdateObj.isNotEmpty() -> rdateSummary
                else -> rruleObj?.describe(context) ?: ""
            }
        }
    }
    var reminders by remember(event?.id) { mutableStateOf(event?.reminders ?: emptyList()) }

    // A new event inherits the reminders configured as defaults for its calendar; switching the
    // calendar re-applies that calendar's defaults. Editing an existing event keeps its own set.
    LaunchedEffect(selectedCalendar) {
        if (event == null && selectedCalendar != -1L) {
            reminders = viewModel.getDefaultReminders(selectedCalendar)
        }
    }

    // Shift the end date/time to preserve the current event duration when the start moves.
    fun applyStartChange(newStartDate: LocalDate, newStartTime: LocalTime) {
        val tz = TimeZone.of(timezone)
        val oldStart = startDate.atTime(startTime).toInstant(tz)
        val oldEnd = endDate.atTime(endTime).toInstant(tz)
        var dur = oldEnd - oldStart
        if (dur.isNegative()) dur = Duration.ZERO
        startDate = newStartDate
        startTime = newStartTime
        val newEndLdt = (newStartDate.atTime(newStartTime).toInstant(tz) + dur).toLocalDateTime(tz)
        endDate = newEndLdt.date
        endTime = newEndLdt.time
    }

    // Collect results from pickers
    ResultEffect<LocalDate>(KEY_START_DATE) { selected ->
        applyStartChange(selected, startTime)
    }

    ResultEffect<LocalDate>(KEY_END_DATE) { selected ->
        // ensure end date is not before start date
        endDate = maxOf(startDate, selected)
    }

    ResultEffect<LocalTime>(KEY_START_TIME) { selected ->
        applyStartChange(startDate, selected)
    }

    ResultEffect<LocalTime>(KEY_END_TIME) { selected ->
        // ensure end time is not before start time when on same date
        endTime = if (endDate == startDate) {
            maxOf(selected, startTime)
        } else {
            selected
        }
    }

    // Recurrence dialog result: either a pattern or a hand-picked set of dates, never both.
    ResultEffect<RRule>(KEY_RECURRENCE) { res ->
        rruleObj = res
        rdateObj = emptyList()
    }
    ResultEffect<RecurrenceDates>(KEY_RECURRENCE) { res ->
        rdateObj = res.dates
        rruleObj = null
    }

    // Result key for calendar picker
    // open dialog via navigation and handle result
    ResultEffect<Long>(KEY_CALENDAR) { calId ->
        selectedCalendar = calId
    }

    // Timezone selector (navigation dialog) - open via Nav route and handle result
    ResultEffect<String>(KEY_TIMEZONE) { z -> timezone = z }

    DetailScaffold(
        title = "",
        onNavigateBack = { backStack.pop() },
        actions = {
            // The series + resolution live in EditEventScope.kt (file-length limit):
            // a series edited from its detail screen asks which occurrences the edit
            // applies to (delete does the same) instead of always rewriting everything.
            EditSaveActions(
                viewModel = viewModel,
                eventId = eventId,
                event = event,
                instanceId = editRoute.instanceId,
                snapshot = {
                    EditFormSnapshot(
                        title = title,
                        description = descriptionText,
                        location = location,
                        calendarId = selectedCalendar,
                        allDay = allDay,
                        startDate = startDate,
                        endDate = endDate,
                        startTime = startTime,
                        endTime = endTime,
                        timezone = timezone,
                        rrule = rruleObj,
                        rdate = rdateObj,
                        reminders = reminders,
                        color = event?.color,
                        exdate = event?.exdate ?: emptyList(),
                    )
                },
                onSaved = { backStack.pop() },
            )
        },
        bottomBar = {
            if (descriptionController.focused) {
                com.vayunmathur.library.ui.OdfMarkdownEditorToolbar(descriptionController)
            }
        },
    ) {
            // The detail screen's title morphs into this field. Keyed on the instance, matching the
            // detail side - the grid keys per instance so a recurring event has one origin per key.
            // Null when the edit was not reached from a detail screen.
            LabeledTextField(
                value = title,
                onValueChange = { title = it },
                label = stringResource(R.string.label_title),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                sharedTextKey = editRoute.instanceId?.let { "calendar-event-title-$it" },
            )

            // Calendar selector: moved above the datetime section — only when creating a new event
            if (eventId == null) {
                Item(
                    { Box(modifier = Modifier.size(24.dp).background(Color(calendars.find { it.id == selectedCalendar }?.color ?: 0))) },
                    { Text(calendars.find { it.id == selectedCalendar }?.displayName ?: stringResource(R.string.select_calendar), Modifier.clickable { backStack.add(Route.EditEvent.CalendarPickerDialog(KEY_CALENDAR)) }) },
                    {}
                )
            }

            EditEventDescription(
                descriptionText = descriptionText,
                descriptionController = descriptionController,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            EditEventDateTimeSection(
                allDay = allDay,
                onAllDay = { allDay = it },
                startDate = startDate,
                endDate = endDate,
                startTime = startTime,
                endTime = endTime,
                timezone = timezone,
                rruleObj = rruleObj,
                rdateObj = rdateObj,
                repeatSummary = repeatSummary,
                onRecurrence = { r, d -> rruleObj = r; rdateObj = d },
                backStack = backStack,
                startDateKey = KEY_START_DATE,
                endDateKey = KEY_END_DATE,
                startTimeKey = KEY_START_TIME,
                endTimeKey = KEY_END_TIME,
                recurrenceKey = KEY_RECURRENCE,
                timezoneKey = KEY_TIMEZONE,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            EditEventReminders(reminders = reminders, onReminders = { reminders = it })

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // The location line on the detail screen morphs into this field. sharedTextKey, not a
            // modifier: the editable line fills the field's width, so keying the field itself would
            // balloon the arriving text out to that width and snap it back on landing. Null for a new
            // event, which has no detail screen to have come from.
            LabeledTextField(
                value = location,
                onValueChange = { location = it },
                label = stringResource(R.string.label_location),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                singleLine = false,
                sharedTextKey = editRoute.id?.let { "calendar-event-location-$it" },
            )
    }
}
