package com.vayunmathur.maps.domain
import com.vayunmathur.maps.util.OfflineRouter

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Query-time fields for the offline transit index, all derived in one timezone.
 * [weekday] is 0=Mon..6=Sun to match the index's service masks, [date] is
 * `yyyymmdd`, and `prev*` describe the preceding service day (GTFS trips stored
 * as `24:30:00` belong to it but run in this one).
 *
 * Lives outside [OfflineRouter] so it can be unit-tested: that object's
 * initializer loads the native library.
 */
internal data class TransitClock(
    val depSecs: Int,
    val weekday: Int,
    val date: Int,
    val prevWeekday: Int,
    val prevDate: Int,
    /** Epoch millis of midnight in that zone, so `depSecs` is absolute. */
    val midnightMillis: Long,
)

/**
 * Derive a [TransitClock] in IANA zone [zoneId] at [now], falling back to the
 * device zone when [zoneId] is null or unknown. The `.transit` index is
 * world-merged, so using the device zone for a distant feed picks trips hours
 * off.
 */
internal fun transitClock(
    zoneId: String?,
    now: (ZoneId) -> ZonedDateTime = { ZonedDateTime.now(it) },
): TransitClock {
    val zone = zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    val at = now(zone)
    val today = at.toLocalDate()
    val yesterday = today.minusDays(1)
    return TransitClock(
        depSecs = at.toLocalTime().toSecondOfDay(),
        // DayOfWeek is Mon=1..Sun=7.
        weekday = today.dayOfWeek.value - DAY_OF_WEEK_OFFSET,
        date = yyyymmdd(today),
        prevWeekday = yesterday.dayOfWeek.value - DAY_OF_WEEK_OFFSET,
        prevDate = yyyymmdd(yesterday),
        midnightMillis = today.atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}

private fun yyyymmdd(d: LocalDate): Int =
    d.year * YEAR_MULTIPLIER + d.monthValue * MONTH_MULTIPLIER + d.dayOfMonth

/**
 * Format a time in the query day's frame (which exceeds 86400 for anything
 * after midnight, and spans several days on a long-distance journey) as a wall
 * clock `HH:mm`, with a `+N` day marker once it falls on a later day, as
 * timetables print it. Empty for a missing time.
 */
internal fun formatServiceTime(secs: Int): String {
    if (secs <= 0) return ""
    val day = secs / SECONDS_PER_DAY
    val s = secs % SECONDS_PER_DAY
    val clock = "%02d:%02d".format(Locale.ROOT, s / SECONDS_PER_HOUR, (s % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE)
    return if (day > 0) "$clock +$day" else clock
}

/** DayOfWeek is Mon=1..Sun=7; the index wants Mon=0..Sun=6. */
private const val DAY_OF_WEEK_OFFSET = 1
private const val YEAR_MULTIPLIER = 10000
private const val MONTH_MULTIPLIER = 100
private const val SECONDS_PER_DAY = 86_400
private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60
