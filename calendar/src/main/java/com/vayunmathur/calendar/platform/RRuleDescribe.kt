package com.vayunmathur.calendar.platform

import android.content.Context
import com.vayunmathur.calendar.R
import com.vayunmathur.calendar.domain.RRule
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedDayOfWeekNames
import kotlinx.datetime.isoDayNumber

/**
 * Localized human-readable description of a recurrence rule.
 *
 * Kept in `platform/` rather than with the pure parsing/expansion logic in
 * `domain/RRule.kt` because it needs an Android [Context] for string resources.
 */
fun RRule.describe(context: Context): String = describeImpl(context) + endCondition.describeSuffix(context)

private fun RRule.EndCondition.describeSuffix(context: Context): String = when (this) {
    is RRule.EndCondition.Never -> ""
    is RRule.EndCondition.Count -> context.resources.getQuantityString(
        R.plurals.rrule_count_suffix,
        count.toInt(),
        count,
    )
    is RRule.EndCondition.Until -> context.getString(R.string.rrule_until_suffix, DateString.dateWeekday(date))
}

private fun RRule.describeImpl(context: Context): String = when (this) {
    is RRule.EveryXYears ->
        if (years == 1) context.getString(R.string.rrule_yearly)
        else context.resources.getQuantityString(R.plurals.rrule_every_years, years, years)
    is RRule.EveryXMonths ->
        if (months == 1) context.getString(R.string.rrule_monthly)
        else context.resources.getQuantityString(R.plurals.rrule_every_months, months, months)
    is RRule.EveryXWeeks -> {
        val prefix = if (weeks == 1) context.getString(R.string.rrule_weekly)
        else context.resources.getQuantityString(R.plurals.rrule_every_weeks, weeks, weeks)
        if (daysOfWeek.isEmpty()) prefix
        else {
            val days = daysOfWeek.sorted().joinToString(", ") {
                localizedDayOfWeekNames(DateNameStyle.SHORT)[it.isoDayNumber - 1]
            }
            context.getString(R.string.rrule_on_days, prefix, days)
        }
    }
    is RRule.EveryXDays ->
        if (days == 1) context.getString(R.string.rrule_daily)
        else context.resources.getQuantityString(R.plurals.rrule_every_days, days, days)
}
