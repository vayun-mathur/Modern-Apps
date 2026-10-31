package com.vayunmathur.calendar.ui

import android.content.ContentValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.util.CalendarViewModel
import com.vayunmathur.calendar.util.RRule
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberMessenger
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Scoped saves for recurring-series edits (item 6).
 *
 * The editor seeds its fields from the series, so a plain save would silently rewrite every
 * occurrence when the user only meant one. The save-scope chooser in [EditEventScreen] routes
 * through these helpers, which reuse only the existing delete-instance + insert/update paths:
 *
 * - this occurrence: cut it out of the series ([CalendarViewModel.deleteEventInstance]) and
 *   re-insert the edited values standalone;
 * - this and following: truncate the series before the occurrence (RRULE gets an UNTIL, RDATEs
 *   are filtered) and start a new series here with the edited values;
 * - all: plain [CalendarViewModel.upsertEvent], handled at the call site.
 *
 * Field edits are treated as deltas from the seeded series values and re-anchored onto the
 * edited occurrence, so saving "this only" without touching anything keeps that occurrence's
 * date instead of jumping back to the series start.
 */

/** The editor's field values, detached from composition so scoped saves can snapshot them. */
internal data class EditFormSnapshot(
    val title: String,
    val description: String,
    val location: String,
    val calendarId: Long,
    val allDay: Boolean,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val timezone: String,
    val rrule: RRule?,
    val rdate: List<LocalDate>,
    val reminders: List<Int>,
    val color: Int?,
    val exdate: List<LocalDate>,
)

/** The edited occurrence, resolved against the provider's expanded instances. */
internal data class ResolvedOccurrence(
    val begin: Long,
    /** How many of the series' occurrences start before this one (for rebasing COUNT). */
    val elapsedBefore: Int,
)

/**
 * The save button for the event editor. A recurring series edited from its detail screen
 * offers a scope chooser (this / this-and-following / all) mirroring the delete UI,
 * instead of always overwriting the whole series. Lives here rather than in
 * `EditEventScreen.kt` to keep that file under the ui-package length limit.
 */
@Composable
internal fun EditSaveActions(
    viewModel: CalendarViewModel,
    eventId: Long?,
    event: Event?,
    instanceId: Long?,
    snapshot: () -> EditFormSnapshot,
    onSaved: () -> Unit,
) {
    val messenger = rememberMessenger()
    val occurrenceNotFoundMessage = stringResource(R.string.edit_occurrence_not_found)
    var showScopeMenu by remember { mutableStateOf(false) }

    // The series being edited, once the async event list has loaded it.
    val series = event?.takeIf { eventId != null && it.isRecurring }

    // Resolve the edited occurrence's begin instant for scoped saves. The provider only
    // expands instances inside a queried window, so ask for a wide one around the
    // series start and today; a miss fails safe with a message instead of saving wrong.
    // The earlier-occurrence count rebases a COUNT end when splitting a series.
    val occurrence by produceState<ResolvedOccurrence?>(initialValue = null, event?.id, instanceId) {
        val targetId = instanceId
        val loaded = event
        value = if (loaded?.id == null || targetId == null) null else runCatching {
            val now = Clock.System.now().toEpochMilliseconds()
            val start = Instant.fromEpochMilliseconds(minOf(loaded.start, now) - 1.days.inWholeMilliseconds)
            val end = Instant.fromEpochMilliseconds(maxOf(loaded.start, now) + (365 * 3).days.inWholeMilliseconds)
            val window = viewModel.visibleInstances(start, end)
                .filter { it.eventID == loaded.id }
                .sortedBy { it.begin }
            val target = window.find { it.id == targetId } ?: return@runCatching null
            ResolvedOccurrence(
                begin = target.begin,
                elapsedBefore = window.count { it.begin < target.begin },
            )
        }.getOrNull()
    }

    fun buildEventValues(): ContentValues {
        val form = snapshot()
        return form.toEvent(eventId).toContentValues(form.calendarId)
    }

    if (series != null && instanceId != null) {
        androidx.compose.foundation.layout.Box {
            IconButton(onClick = { showScopeMenu = true }) {
                IconSave()
            }
            DropdownMenu(
                expanded = showScopeMenu,
                onDismissRequest = { showScopeMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.save_this_event)) },
                    onClick = {
                        showScopeMenu = false
                        // Detach: cut this occurrence out of the series, re-insert it
                        // standalone with the edited values. Uses only the existing
                        // delete-instance + insert paths.
                        val resolved = occurrence
                        if (resolved == null) {
                            messenger.show(occurrenceNotFoundMessage)
                        } else {
                            saveScopedThis(viewModel, series, resolved.begin, snapshot())
                            onSaved()
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.save_this_and_following_events)) },
                    onClick = {
                        showScopeMenu = false
                        // Split: truncate the series before this occurrence, start a new
                        // series here with the edited values.
                        val resolved = occurrence
                        if (resolved == null) {
                            messenger.show(occurrenceNotFoundMessage)
                        } else {
                            saveScopedFollowing(viewModel, series, resolved, snapshot())
                            onSaved()
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.save_all_events)) },
                    onClick = {
                        showScopeMenu = false
                        viewModel.upsertEvent(eventId, buildEventValues(), snapshot().reminders)
                        onSaved()
                    }
                )
            }
        }
    } else {
        IconButton(onClick = {
            viewModel.upsertEvent(eventId, buildEventValues(), snapshot().reminders)
            onSaved()
        }) {
            IconSave()
        }
    }
}

internal fun EditFormSnapshot.toEvent(id: Long?): Event {
    val buildTz = if (allDay) TimeZone.UTC else TimeZone.of(timezone)
    // All-day events are stored at midnight UTC with an exclusive end (the midnight
    // after the last selected day), matching RFC 5545 / the Android calendar provider.
    val startInstant = if (allDay) startDate.atStartOfDayIn(buildTz)
        else startDate.atTime(startTime).toInstant(buildTz)
    val endInstant = if (allDay) endDate.plus(DatePeriod(days = 1)).atStartOfDayIn(buildTz)
        else endDate.atTime(endTime).toInstant(buildTz)
    return Event(
        id = id,
        calendarID = calendarId,
        title = title,
        description = description,
        location = location,
        color = color,
        start = startInstant.toEpochMilliseconds(),
        end = endInstant.toEpochMilliseconds(),
        timezone = if (allDay) "UTC" else timezone,
        allDay = allDay,
        rrule = rrule,
        exdate = exdate,
        rdate = rdate,
        reminders = reminders,
    )
}

internal fun RRule.withEnd(end: RRule.EndCondition): RRule = when (this) {
    is RRule.EveryXDays -> copy(endCondition = end)
    is RRule.EveryXWeeks -> copy(endCondition = end)
    is RRule.EveryXMonths -> copy(endCondition = end)
    is RRule.EveryXYears -> copy(endCondition = end)
}

/** Shift the form's dates from the series start onto [occurrenceDate], preserving duration. */
private fun EditFormSnapshot.anchoredOn(seriesStartDate: LocalDate, occurrenceDate: LocalDate): EditFormSnapshot {
    val userShift = (startDate.toEpochDays() - seriesStartDate.toEpochDays()).toInt()
    val newStartDate = occurrenceDate.plus(DatePeriod(days = userShift))
    val durationDays = (endDate.toEpochDays() - startDate.toEpochDays()).toInt()
    return copy(
        startDate = newStartDate,
        endDate = newStartDate.plus(DatePeriod(days = durationDays)),
    )
}

private fun occurrenceDateOf(series: Event, begin: Long): LocalDate {
    // All-day occurrences are UTC-midnight wall dates: resolve in UTC so a
    // legacy local-zone row cannot shift the day across midnight.
    val zone = if (series.allDay) TimeZone.UTC
        else runCatching { TimeZone.of(series.timezone) }.getOrNull()
            ?: TimeZone.currentSystemDefault()
    return Instant.fromEpochMilliseconds(begin).toLocalDateTime(zone).date
}

internal fun saveScopedThis(
    viewModel: CalendarViewModel,
    series: Event,
    occurrenceBegin: Long,
    form: EditFormSnapshot,
): Boolean {
    val id = series.id ?: return false
    val date = occurrenceDateOf(series, occurrenceBegin)
    // Cut this occurrence out, then re-insert the edited values standalone.
    viewModel.deleteEventInstance(id, occurrenceBegin)
    val standalone = form
        .anchoredOn(series.startDateTimeDisplay.date, date)
        .copy(rrule = null, rdate = emptyList(), exdate = emptyList())
        .toEvent(null)
    viewModel.upsertEvent(null, standalone.toContentValues(form.calendarId), form.reminders)
    return true
}

internal fun saveScopedFollowing(
    viewModel: CalendarViewModel,
    series: Event,
    occurrence: ResolvedOccurrence,
    form: EditFormSnapshot,
): Boolean {
    val id = series.id ?: return false
    val seriesStartDate = series.startDateTimeDisplay.date
    val date = occurrenceDateOf(series, occurrence.begin)
    if (date <= seriesStartDate) {
        // Editing from the first occurrence covers the whole series.
        viewModel.upsertEvent(id, form.toEvent(id).toContentValues(form.calendarId), form.reminders)
        return true
    }
    // Truncate the original: everything before this occurrence stays as-is. Converting a
    // COUNT end to UNTIL keeps exactly the past occurrences; the tail moves to the new series.
    val truncated = series.copy(
        rrule = series.rrule?.withEnd(RRule.EndCondition.Until(date.minus(DatePeriod(days = 1)))),
        rdate = series.rdate.filter { it < date },
    )
    viewModel.upsertEvent(id, truncated.toContentValues(truncated.calendarID), reminders = null)
    // The new series starts here with the edited values. A COUNT end is rebased on the
    // occurrences already past so the tail doesn't run long.
    val rebasedEnd = (form.rrule?.endCondition as? RRule.EndCondition.Count)?.let { count ->
        RRule.EndCondition.Count((count.count - occurrence.elapsedBefore).coerceAtLeast(1))
    }
    val split = form
        .anchoredOn(seriesStartDate, date)
        .copy(
            rrule = form.rrule?.let { if (rebasedEnd != null) it.withEnd(rebasedEnd) else it },
            rdate = form.rdate.filter { it >= date },
            exdate = series.exdate.filter { it >= date },
        )
        .toEvent(null)
    viewModel.upsertEvent(null, split.toContentValues(form.calendarId), form.reminders)
    return true
}
