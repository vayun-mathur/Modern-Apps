package com.vayunmathur.calendar.ui

import com.vayunmathur.library.log.Log
import com.vayunmathur.calendar.domain.AllDayFormat
import com.vayunmathur.calendar.domain.BasicIsoInstantFormat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/** Reverses RFC 5545 TEXT escaping. */
internal fun unescapeIcsText(value: String): String {
    if ('\\' !in value) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
            when (val next = value[i + 1]) {
                'n', 'N' -> out.append('\n')
                else -> out.append(next)
            }
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/** The days a set of RDATE or EXDATE lines names, in [zone]. */
internal fun parseIcsDates(props: List<Pair<String, String>>, zone: TimeZone): List<LocalDate> =
    props.flatMap { (left, values) -> values.split(",").map { left to it.trim() } }
        .mapNotNull { (left, value) ->
            if (value.length == 8 && value.all { it.isDigit() }) {
                runCatching { AllDayFormat.parse(value) }.getOrNull()
            } else {
                parseICSTime(left, value).first?.let {
                    Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date
                }
            }
        }
        .distinct()
        .sorted()

// Parse ICS time value with optional params-left (like DTSTART;TZID=America/Los_Angeles)
internal fun parseICSTime(propLeft: String?, value: String?): Triple<Long?, Boolean, String?> {
    if (value == null) return Triple(null, false, null)

    val left = propLeft ?: ""
    val up = left.uppercase()

    val isAllDay = up.contains("VALUE=DATE") || value.length == 8 && value.all { it.isDigit() }

    return try {
        if (isAllDay) {
            val dt = AllDayFormat.parse(value)
            val start = dt.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
            Triple(start, true, "UTC")
        } else {
            if (value.endsWith("Z")) {
                val instant = runCatching { BasicIsoInstantFormat.parse(value).toInstantUsingOffset() }
                    .getOrElse { Instant.parse(value) }
                Triple(instant.toEpochMilliseconds(), false, "UTC")
            } else if (hasExplicitOffset(value)) {
                val instant = runCatching { BasicIsoInstantFormat.parse(value).toInstantUsingOffset() }
                    .getOrElse { Instant.parse(value) }
                Triple(instant.toEpochMilliseconds(), false, "UTC")
            } else {
                val tzid = extractTZID(left)
                val parsed = parseFloatingTime(value, tzid)
                if (parsed != null) {
                    Triple(parsed.toEpochMilliseconds(), false,
                        tzid ?: TimeZone.currentSystemDefault().id)
                } else {
                    Triple(null, false, tzid)
                }
            }
        }
    } catch (expected: Exception) {
        Log.error("IcsImport", "Error parsing ICS time: $value", expected)
        Triple(null, false, null)
    }
}

internal fun extractTZID(left: String): String? =
    left.split(';')
        .map { it.split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0].uppercase() == "TZID" }
        ?.get(1)

/** Parses a floating (offset-less) ICS datetime in [tzid], trying each known format. */
private fun parseFloatingTime(value: String, tzid: String?): Instant? {
    val candidates = listOf(
        DateTimeFormat, DateTimeShortFormat, DashedDateTimeFormat, DashedDateTimeShortFormat,
    )
    for (fmt in candidates) {
        try {
            val ldt = LocalDateTime.parse(value, fmt)
            val zone = tzid?.let { TimeZone.of(it) } ?: TimeZone.currentSystemDefault()
            return ldt.toInstant(zone)
        } catch (_: IllegalArgumentException) {
            // try next candidate
        }
    }
    return null
}

internal fun tryParseDurationMillis(duration: String, startMillis: Long): Long? =
    runCatching { startMillis + Duration.parse(duration).inWholeMilliseconds }.getOrNull()

// Formats for local times without offset
val DateTimeFormat = LocalDateTime.Format {
    year(); monthNumber(); day()
    char('T')
    hour(); minute(); second()
}

val DateTimeShortFormat = LocalDateTime.Format {
    year(); monthNumber(); day()
    char('T')
    hour(); minute()
}

// Dashed ISO-8601 floating forms (e.g. 2025-01-01T09:00:00).
val DashedDateTimeFormat = LocalDateTime.Format {
    year(); char('-'); monthNumber(); char('-'); day()
    char('T')
    hour(); char(':'); minute(); char(':'); second()
}

val DashedDateTimeShortFormat = LocalDateTime.Format {
    year(); char('-'); monthNumber(); char('-'); day()
    char('T')
    hour(); char(':'); minute()
}

/** True when [value] carries an explicit numeric UTC offset (not a date dash). */
internal fun hasExplicitOffset(value: String): Boolean {
    if ('+' in value) return true
    val tIndex = value.indexOf('T')
    if (tIndex < 0) return false
    return '-' in value.substring(tIndex + 1)
}

/**
 * Parses a VALARM TRIGGER value to minutes-before-start, or null when it cannot be expressed
 * that way. Handles negative durations, zero (at-start = 0), and duration-with-absolute-time
 * forms (keeps only the duration half before ';'). Absolute datetimes and positive
 * (post-start) durations return null.
 */
internal fun parseTriggerMinutesBefore(raw: String): Int? {
    val durationPart = raw.substringBefore(';').trim()
    if (durationPart.isEmpty()) return null
    val s = durationPart.uppercase()
    if (!s.startsWith("P") && !s.startsWith("-P")) return null
    val duration = runCatching { Duration.parse(durationPart) }.getOrNull() ?: return null
    if (duration.isPositive()) return null
    val minutes = duration.absoluteValue.inWholeMinutes
    return if (minutes > Int.MAX_VALUE) Int.MAX_VALUE else minutes.toInt()
}
