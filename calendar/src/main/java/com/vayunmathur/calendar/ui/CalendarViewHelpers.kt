package com.vayunmathur.calendar.ui

import androidx.compose.ui.unit.dp
import com.vayunmathur.calendar.platform.CalendarViewModel
import com.vayunmathur.calendar.data.Instance
import com.vayunmathur.library.util.localeFirstDayOfWeek
import java.util.Locale
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.time.Instant

internal fun firstDayOfWeekOffset(date: LocalDate, locale: Locale): Int {
    val firstDayOfWeek = localeFirstDayOfWeek(locale)
    return (date.dayOfWeek.isoDayNumber - firstDayOfWeek + 7) % 7
}

/**
 * The key that morphs one event's title into the title on its detail screen, or null when this copy
 * is not the one the morph should start from.
 *
 * Keyed on the instance rather than the event: every occurrence of a repeating event is on screen at
 * once in a month, and they all share an event id. Keyed only on the day the occurrence starts,
 * because an event spanning days is drawn once per day it covers - and the week and month pagers keep
 * the neighbouring pages composed, so those copies are live too. One key, one origin.
 */
internal fun eventTitleMorphKey(instance: Instance, date: LocalDate): String? =
    if (date == instance.startDateTime.date) "calendar-event-title-${instance.id}" else null

internal fun LocalDate.atEndOfDayIn(currentSystemDefault: TimeZone): Instant {
    return this.plus(DatePeriod(days = 1)).atStartOfDayIn(currentSystemDefault)
}

/** Width of the hour-label gutter down the left of the day/week grid. */
internal val HourGutterWidth = 56.dp

/**
 * First workday of the locale week containing [date]. The week boundaries come from
 * [localeFirstDayOfWeek]; Saturday and Sunday are then skipped, so a Sunday-first locale
 * shows the coming Monday–Friday instead of the previous week (and a Saturday-first
 * locale starts on Monday too). Monday–Friday is consecutive in every locale week.
 */
internal fun workWeekStart(date: LocalDate, locale: Locale): LocalDate {
    val weekStart = date.minus(DatePeriod(days = firstDayOfWeekOffset(date, locale)))
    val shift = when (weekStart.dayOfWeek.isoDayNumber) {
        6 -> 2 // Saturday -> Monday
        7 -> 1 // Sunday -> Monday
        else -> 0
    }
    return weekStart.plus(DatePeriod(days = shift))
}

/**
 * The first visible day of a pager page: the week's start day for week layouts (not the
 * anchor-derived page start, which can sit mid-week), the day itself for the day layout.
 * Idempotent, so mapping a reported day back to a page is stable.
 */
internal fun weekStartForLayout(
    pageStartDate: LocalDate,
    layout: CalendarViewModel.CalendarLayout,
    locale: Locale,
): LocalDate = when (layout) {
    CalendarViewModel.CalendarLayout.Day -> pageStartDate
    CalendarViewModel.CalendarLayout.WorkWeek,
    CalendarViewModel.CalendarLayout.WorkWeekSummary,
    CalendarViewModel.CalendarLayout.WorkWeekCompact -> workWeekStart(pageStartDate, locale)
    else -> pageStartDate.minus(DatePeriod(days = firstDayOfWeekOffset(pageStartDate, locale)))
}
