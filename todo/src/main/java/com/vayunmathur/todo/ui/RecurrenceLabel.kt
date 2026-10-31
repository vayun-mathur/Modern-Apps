package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.Recurrence
import java.time.format.TextStyle
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.datetime.isoDayNumber

/** "Every day", "Every 2 weeks", "Weekly on Mon, Fri", "Monthly on day 15", … */
@Composable
fun recurrenceLabel(recurrence: Recurrence): String = when (recurrence) {
    is Recurrence.Daily -> pluralStringResource(R.plurals.repeat_days, recurrence.interval, recurrence.interval)
    is Recurrence.Weekly -> if (recurrence.days.isEmpty()) {
        pluralStringResource(R.plurals.repeat_weeks, recurrence.interval, recurrence.interval)
    } else {
        val locale = LocalConfiguration.current.locales[0]
        val days = recurrence.days.sortedBy { it.isoDayNumber }.joinToString(", ") {
            java.time.DayOfWeek.of(it.isoDayNumber).getDisplayName(TextStyle.SHORT, locale)
        }
        pluralStringResource(R.plurals.repeat_weeks_on, recurrence.interval, recurrence.interval, days)
    }
    is Recurrence.Monthly -> when (val day = recurrence.dayOfMonth) {
        null -> pluralStringResource(R.plurals.repeat_months, recurrence.interval, recurrence.interval)
        Recurrence.LAST_DAY -> stringResource(R.string.repeat_month_last_day)
        else -> stringResource(R.string.repeat_month_day, day)
    }
    is Recurrence.Yearly -> pluralStringResource(R.plurals.repeat_years, recurrence.interval, recurrence.interval)
    is Recurrence.AfterCompletion ->
        pluralStringResource(R.plurals.repeat_after_completion, recurrence.interval, recurrence.interval)
}
