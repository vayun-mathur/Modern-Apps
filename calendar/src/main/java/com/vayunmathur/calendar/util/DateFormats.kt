package com.vayunmathur.calendar.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.char
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// Full yyyyMMdd'T'HHmmssZ pattern (ISO_BASIC handles 'Z' or '+HHmm').
val BasicIsoInstantFormat = DateTimeComponents.Format {
    year(); monthNumber(); day()
    char('T')
    hour(); minute(); second()
    offset(UtcOffset.Formats.ISO_BASIC)
}

// Date-only yyyyMMdd (RFC 5545 basic date).
val AllDayFormat = LocalDate.Format {
    year(); monthNumber(); day()
}

/** RFC 5545 basic date string (YYYYMMDD) for this date. */
fun LocalDate.toIcalBasic(): String = format(AllDayFormat)

// UTC datetime yyyyMMdd'T'HHmmss'Z'.
private val UtcBasicDateTimeFormat = LocalDateTime.Format {
    year(); monthNumber(); day()
    char('T')
    hour(); minute(); second()
    chars("Z")
}

// Zone-local datetime yyyyMMdd'T'HHmmss, which only means anything alongside a TZID parameter.
private val LocalBasicDateTimeFormat = LocalDateTime.Format {
    year(); monthNumber(); day()
    char('T')
    hour(); minute(); second()
}

/** RFC 5545 UTC datetime (YYYYMMDDTHHMMSSZ) for the instant [millis]. */
fun icalUtcDateTime(millis: Long): String =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).format(UtcBasicDateTimeFormat)

/** RFC 5545 datetime for [millis] as wall time in [timeZone]; pair it with a TZID parameter. */
fun icalLocalDateTime(millis: Long, timeZone: TimeZone): String =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone).format(LocalBasicDateTimeFormat)

/**
 * RFC 5545 value for one occurrence of a timed event: this date at [timeOfDay] in [timeZone],
 * written as a UTC datetime so the value needs no TZID parameter. A date alone would be read back
 * as midnight, putting the occurrence at the wrong time of day.
 */
fun LocalDate.toIcalUtcDateTime(timeOfDay: LocalTime, timeZone: TimeZone): String =
    atTime(timeOfDay).toInstant(timeZone).toLocalDateTime(TimeZone.UTC).format(UtcBasicDateTimeFormat)

/** Length of the YYYYMMDD prefix parsed as a date (a trailing time is ignored). */
private const val BASIC_DATE_PREFIX_LENGTH = 8

/** Parses a basic iCal date (YYYYMMDD, optionally followed by a time) to a [LocalDate]. */
fun parseIcalBasicDate(value: String): LocalDate? =
    runCatching { AllDayFormat.parse(value.take(BASIC_DATE_PREFIX_LENGTH)) }.getOrNull()

/** Parses an RFC 5545 UNTIL value (date or datetime) to a [LocalDate] in [timeZone]. */
fun parseIcalUntil(value: String, timeZone: TimeZone): LocalDate? {
    if ('T' !in value) {
        return runCatching { AllDayFormat.parse(value) }.getOrNull()
    }
    return runCatching {
        BasicIsoInstantFormat.parse(value).toInstantUsingOffset().toLocalDateTime(timeZone).date
    }.getOrNull()
        // A floating UNTIL (no 'Z', no offset) is valid RFC 5545 alongside a
        // floating DTSTART, which ICS exporters do emit. It carries no instant
        // to convert, so the written calendar day is the only meaning it has;
        // refusing it here made RRule.parse drop the whole recurrence and the
        // event silently became one-off on import/read.
        ?: parseIcalBasicDate(value)
}

/**
 * The calendar day one EXDATE/RDATE value falls on, resolved in [timeZone].
 *
 * A date-only value already is that day. A datetime is an instant, so it has to be converted before
 * the day is taken: 22:00 in New York is written as the following day in UTC, and reading the day
 * straight off the string would move the occurrence. Falls back to the leading date for a datetime
 * that carries no offset, which is all a floating value can mean without its TZID.
 */
fun parseIcalOccurrenceDate(value: String, timeZone: TimeZone): LocalDate? =
    parseIcalUntil(value, timeZone) ?: parseIcalBasicDate(value)
