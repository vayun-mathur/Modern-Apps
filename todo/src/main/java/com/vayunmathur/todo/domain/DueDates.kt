package com.vayunmathur.todo.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Conversions between stored due values and calendar dates.
 *
 * All-day due values are local midnight. The Material date picker speaks UTC midnight, so
 * values crossing that boundary go through [fromPickerMillis]/[toPickerMillis]; feeding a
 * UTC-midnight value straight into local formatting shows the previous day west of UTC.
 */
object DueDates {
    private const val DAY_MS = 86_400_000L

    fun localDate(epochMs: Long, zone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date

    fun localTime(epochMs: Long, zone: TimeZone): LocalTime =
        Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).time

    fun startOfDay(date: LocalDate, zone: TimeZone): Long =
        date.atStartOfDayIn(zone).toEpochMilliseconds()

    fun at(date: LocalDate, time: LocalTime, zone: TimeZone): Long =
        LocalDateTime(date, time).toInstant(zone).toEpochMilliseconds()

    fun fromPickerMillis(utcMidnight: Long, zone: TimeZone): Long =
        startOfDay(localDate(utcMidnight, TimeZone.UTC), zone)

    fun toPickerMillis(localEpochMs: Long, zone: TimeZone): Long =
        startOfDay(localDate(localEpochMs, zone), TimeZone.UTC)

    /** Rewrites a legacy UTC-midnight all-day value as local midnight; other values pass through. */
    fun migrateLegacyAllDay(epochMs: Long, zone: TimeZone): Long =
        if (epochMs % DAY_MS == 0L) fromPickerMillis(epochMs, zone) else epochMs
}
