package com.vayunmathur.contacts.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * UTC-midnight encoding for all-day calendar events (birthdays,
 * anniversaries) written to the Android calendar provider.
 *
 * The provider treats ALL_DAY rows as timezone-independent wall dates only
 * when DTSTART lands on midnight UTC with EVENT_TIMEZONE "UTC". Anchoring
 * the same wall date at local midnight in the device zone instead makes
 * the stored instant depend on the offset in force at sync time; when the
 * provider later expands the FREQ=YEARLY recurrence under a different DST
 * regime the instance slides across the UTC-midnight boundary and the day
 * renders one early (birthdays in April-October shifted for US/EU zones).
 * Converting through UTC keeps the stored instant identical year-round, so
 * DST transitions cannot move it.
 */
const val ALL_DAY_EVENT_TIMEZONE = "UTC"

const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

/**
 * Epoch millis of UTC midnight starting [date]: the DTSTART for a one-day
 * all-day event on that date. Pure in/out over [LocalDate] so DST behavior
 * stays unit-testable without a device or provider.
 */
fun LocalDate.allDayEventStartMillis(): Long =
    atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

/** Exclusive DTEND for a one-day all-day event starting at [startMillis]. */
fun allDayEventEndMillis(startMillis: Long): Long = startMillis + MILLIS_PER_DAY
