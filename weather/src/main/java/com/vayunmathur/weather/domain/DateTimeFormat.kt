package com.vayunmathur.weather.domain

import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.util.localizedAmPmMarker
import kotlinx.datetime.format.DateTimeFormat
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedDayOfWeekNames
import com.vayunmathur.library.util.localizedMonthNames
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Centralized date/time display formatting for the weather module, built on
 * kotlinx-datetime format builders instead of hand-rolled weekday/month/hour
 * arrays. Every helper preserves the exact strings the screens rendered before.
 */

/** "3 PM" / "12 AM" — 12-hour clock, no minutes. */
private val HourAmPm: DateTimeFormat<LocalTime> get() = LocalTime.Format {
    amPmHour(Padding.NONE)
    char(' ')
    localizedAmPmMarker()
}

/** "09:00" / "15:00" — 24-hour hour, padded, minutes pinned to :00 for hour axes. */
private val HourOfDayWithZero = LocalTime.Format {
    hour()
    char(':')
    char('0')
    char('0')
}

/** "9" / "15" — bare 24-hour hour, no padding. */
private val HourOfDayBare = LocalTime.Format {
    hour(Padding.NONE)
}

/** "Mon" … "Sun". */
private val WeekdayShort: DateTimeFormat<LocalDate> get() = LocalDate.Format {
    dayOfWeek(DayOfWeekNames(localizedDayOfWeekNames(DateNameStyle.SHORT)))
}

/** "Wed 25 Jun". */
private val DayMonthLabel: DateTimeFormat<LocalDate> get() = LocalDate.Format {
    dayOfWeek(DayOfWeekNames(localizedDayOfWeekNames(DateNameStyle.SHORT)))
    char(' ')
    day(Padding.NONE)
    char(' ')
    monthName(MonthNames(localizedMonthNames(DateNameStyle.SHORT)))
}

/**
 * Wall-clock time for [epochSec] in the forecast location's zone. Pass the
 * response's `utc_offset_seconds`; null keeps the device time zone (previews
 * and callers with no location context).
 */
private fun localTimeAt(epochSec: Long, utcOffsetSeconds: Int? = null): LocalTime {
    // A location's wall clock is the UTC instant shifted by its offset, read
    // back as UTC fields. (Instant.toLocalDateTime(UtcOffset) is internal in
    // this kotlinx-datetime version, so shift-then-read-in-UTC instead.)
    return if (utcOffsetSeconds != null) {
        Instant.fromEpochSeconds(epochSec + utcOffsetSeconds).toLocalDateTime(TimeZone.UTC).time
    } else {
        Instant.fromEpochSeconds(epochSec).toLocalDateTime(TimeZone.currentSystemDefault()).time
    }
}

/** Hour-axis label "3 PM" / "09:00" for an epoch second in the location's zone. */
fun formatHourAxisLabel(epochSec: Long, use24Hour: Boolean, utcOffsetSeconds: Int? = null): String =
    DateString.hourLabel(localTimeAt(epochSec, utcOffsetSeconds), use24Hour)

/** Hourly-strip label "3 PM" / "9" for an epoch second in the location's zone. */
fun formatStripHour(epochSec: Long, use24Hour: Boolean, utcOffsetSeconds: Int? = null): String =
    localTimeAt(epochSec, utcOffsetSeconds).format(if (use24Hour) HourOfDayBare else HourAmPm)

/** Clock time "3:05 PM" / "15:00" for an epoch second in the location's zone. */
fun formatClockTime(epochSec: Long, use24Hour: Boolean, utcOffsetSeconds: Int? = null): String =
    DateString.time(localTimeAt(epochSec, utcOffsetSeconds), use24Hour)

/** "Wed 25 Jun" for an ISO date like 2026-06-25; echoes the input on parse failure. */
fun formatDayMonthLabel(isoDate: String): String {
    val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
    return date.format(DayMonthLabel)
}

/** "3 PM · Wed" (or "15:00 · Wed") for an ISO time like 2026-06-25T15:00. */
fun formatSelectedHourLabel(isoTime: String, use24Hour: Boolean): String {
    val ldt = runCatching { LocalDateTime.parse(isoTime) }.getOrNull() ?: return isoTime
    val hour = ldt.time.format(if (use24Hour) HourOfDayWithZero else HourAmPm)
    val weekday = ldt.date.format(WeekdayShort)
    return "$hour · $weekday"
}

/**
 * "3 PM · Wed" (or "15:00 · Wed") for a UTC instant ISO string (e.g.
 * `2026-07-01T18:00Z`, or an offset/naive-UTC variant), rendered in [zone].
 * Used by the map to localize model UTC steps to the viewer's — or the
 * zoomed-in region's — time zone. Echoes the input on parse failure.
 */
fun formatInstantInZone(utcIso: String, zone: TimeZone, use24Hour: Boolean): String {
    val epochSec = parseUtcIsoToEpochSec(utcIso) ?: return utcIso
    val ldt = Instant.fromEpochSeconds(epochSec).toLocalDateTime(zone)
    val hour = ldt.time.format(if (use24Hour) HourOfDayWithZero else HourAmPm)
    val weekday = ldt.date.format(WeekdayShort)
    return "$hour · $weekday"
}

/** Parse a UTC ISO string with a trailing `Z`, an explicit offset, or none. */
private fun parseUtcIsoToEpochSec(iso: String): Long? =
    runCatching { Instant.parse(iso).epochSeconds }.getOrNull()
        ?: runCatching { LocalDateTime.parse(iso.removeSuffix("Z")).toInstant(UtcOffset.ZERO).epochSeconds }.getOrNull()

/**
 * The bundle mapper emits local-time ISO strings with no offset (e.g.
 * `2024-05-30T05:42`). Combine with `utc_offset_seconds` from the response to
 * recover a true epoch, falling back to explicit-offset / `Z` strings.
 */
fun parseLocalIsoToEpochSec(iso: String, utcOffsetSec: Int = 0): Long? = runCatching {
    LocalDateTime.parse(iso).toInstant(UtcOffset(seconds = utcOffsetSec)).epochSeconds
}.getOrNull()
    ?: runCatching { Instant.parse("$iso:00Z").epochSeconds }.getOrNull()
    ?: runCatching { Instant.parse(iso).epochSeconds }.getOrNull()
