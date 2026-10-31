package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.DueBuckets
import com.vayunmathur.todo.domain.DueDates
import kotlinx.datetime.TimeZone

/** "Today", "Tomorrow", "Fri", "3 days ago", "Mar 9" — plus the time for timed tasks. */
@Composable
fun dueLabel(dueAt: Long, allDay: Boolean, now: Long): String {
    val zone = TimeZone.currentSystemDefault()
    val delta = DueBuckets.dayDelta(dueAt, now, zone)
    val date = DueDates.localDate(dueAt, zone)
    val day = when {
        delta == 0 -> stringResource(R.string.bucket_today)
        delta == 1 -> stringResource(R.string.bucket_tomorrow)
        delta == -1 -> stringResource(R.string.due_yesterday)
        delta < -1 -> pluralStringResource(R.plurals.due_days_ago, -delta, -delta)
        delta < WEEK -> DateString.dateWeekdayNoYear(date).substringBefore(',')
        else -> DateString.dateShort(date)
    }
    if (allDay) return day
    return day + " " + DateString.time(DueDates.localTime(dueAt, zone), rememberIs24Hour())
}

private const val WEEK = 7
