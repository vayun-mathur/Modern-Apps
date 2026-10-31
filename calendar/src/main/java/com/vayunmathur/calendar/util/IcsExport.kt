package com.vayunmathur.calendar.util

import com.vayunmathur.calendar.data.Event
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.io.Writer
import kotlin.time.Clock

/**
 * RFC 5545 writer for the app's own events.
 *
 * Takes no Context and no Uri so the serialization is testable on its own; opening the destination
 * is the caller's job (see `CalendarViewModel.exportIcs`).
 */

private const val PRODUCT_ID = "-//vayunmathur//Modern Apps Calendar//EN"

/** UIDs have to be globally unique, and the provider's row id only is within this app. */
private const val UID_DOMAIN = "com.vayunmathur.calendar"

/** RFC 5545 caps a content line at 75 octets, excluding the CRLF. */
private const val MAX_LINE_OCTETS = 75

/** Writes [events] as a single VCALENDAR. [now] is the DTSTAMP for every event. */
fun writeIcs(
    events: List<Event>,
    out: Writer,
    now: Long = Clock.System.now().toEpochMilliseconds(),
) {
    out.fold("BEGIN:VCALENDAR")
    out.fold("VERSION:2.0")
    out.fold("PRODID:$PRODUCT_ID")
    out.fold("CALSCALE:GREGORIAN")
    events.forEachIndexed { index, event -> out.writeEvent(event, index, now) }
    out.fold("END:VCALENDAR")
}

private fun Writer.writeEvent(event: Event, index: Int, now: Long) {
    // All-day dates already resolve in UTC (see startDateTimeDisplay), so the
    // export zone only matters for timed occurrences.
    val zone = if (event.allDay) TimeZone.UTC else TimeZone.of(event.timezone)

    fold("BEGIN:VEVENT")
    fold("UID:${event.id ?: "generated-$index"}@$UID_DOMAIN")
    fold("DTSTAMP:${icalUtcDateTime(now)}")

    if (event.allDay) {
        // DTEND is exclusive for a date value, which is how `end` is already stored.
        fold("DTSTART;VALUE=DATE:${event.startDateTimeDisplay.date.toIcalBasic()}")
        fold("DTEND;VALUE=DATE:${event.endDateTimeDisplay.date.toIcalBasic()}")
    } else {
        fold("DTSTART${dateTimeValue(event.start, zone)}")
        fold("DTEND${dateTimeValue(event.end, zone)}")
    }

    fold("SUMMARY:${esc(event.title)}")
    if (event.description.isNotEmpty()) fold("DESCRIPTION:${esc(event.description)}")
    if (event.location.isNotEmpty()) fold("LOCATION:${esc(event.location)}")

    event.rrule?.let { fold("RRULE:${it.asString(event.startDateTimeDisplay.date, zone)}") }

    // The model holds these as days, so an all-day event writes dates and a timed one writes the
    // occurrence's own time of day - a bare date would be read back as midnight.
    if (event.exdate.isNotEmpty()) fold("EXDATE${occurrenceList(event, event.exdate, zone)}")
    if (event.rdate.isNotEmpty()) fold("RDATE${occurrenceList(event, event.rdate, zone)}")

    for (minutes in event.reminders) {
        fold("BEGIN:VALARM")
        fold("ACTION:DISPLAY")
        fold("DESCRIPTION:${esc(event.title)}")
        fold("TRIGGER:${if (minutes <= 0) "PT0S" else "-PT${minutes}M"}")
        fold("END:VALARM")
    }

    fold("END:VEVENT")
}

/**
 * The parameters-and-value tail of a timed DTSTART/DTEND.
 *
 * A named zone is written with TZID and wall time so the event keeps the zone the user entered it
 * in. UTC, and any id that cannot go in a parameter unquoted (`GMT+05:30` and friends, which the
 * provider does hand out), are written as a UTC instant instead.
 *
 * Note that no VTIMEZONE is emitted for the TZID. Clients that resolve IANA names themselves
 * (this app, Google, Apple) read these correctly; strict readers that require the component will
 * not.
 */
private fun dateTimeValue(millis: Long, zone: TimeZone): String =
    if (zone == TimeZone.UTC || !zone.id.isNameableTzid()) ":${icalUtcDateTime(millis)}"
    else ";TZID=${zone.id}:${icalLocalDateTime(millis, zone)}"

/** Whether this zone id can be a bare parameter value: no separator may appear in it. */
private fun String.isNameableTzid(): Boolean =
    isNotEmpty() && none { it == ':' || it == ';' || it == ',' || it == '"' }

private fun occurrenceList(
    event: Event,
    dates: List<LocalDate>,
    zone: TimeZone,
): String = if (event.allDay) {
    ";VALUE=DATE:" + dates.joinToString(",") { it.toIcalBasic() }
} else {
    ":" + dates.joinToString(",") { it.toIcalUtcDateTime(event.startDateTimeDisplay.time, zone) }
}

/** RFC 5545 TEXT escaping. Backslash first, or it would double the escapes added after it. */
private fun esc(value: String): String = value
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace("\r\n", "\\n")
    .replace("\n", "\\n")
    .replace("\r", "\\n")

/**
 * Writes one content line, folded to [MAX_LINE_OCTETS] UTF-8 octets and CRLF terminated.
 *
 * The limit is in octets, not characters, so a line of non-ASCII text folds sooner than its length
 * suggests; folding is also never allowed to fall between the halves of a surrogate pair.
 */
private fun Writer.fold(line: String) {
    var budget = MAX_LINE_OCTETS
    var index = 0
    while (index < line.length) {
        val codePoint = line.codePointAt(index)
        val chars = Character.charCount(codePoint)
        val octets = utf8Octets(codePoint)
        if (octets > budget) {
            // The leading space of a continuation line counts against its 75.
            write("\r\n ")
            budget = MAX_LINE_OCTETS - 1
        }
        write(line, index, chars)
        budget -= octets
        index += chars
    }
    write("\r\n")
}

private fun utf8Octets(codePoint: Int): Int = when {
    codePoint < UTF8_1BYTE_LIMIT -> UTF8_1BYTE_OCTETS
    codePoint < UTF8_2BYTE_LIMIT -> UTF8_2BYTE_OCTETS
    codePoint < UTF8_3BYTE_LIMIT -> UTF8_3BYTE_OCTETS
    else -> UTF8_4BYTE_OCTETS
}

/** UTF-8 lead-byte thresholds: below each limit the code point takes that many octets. */
private const val UTF8_1BYTE_LIMIT = 0x80
private const val UTF8_2BYTE_LIMIT = 0x800
private const val UTF8_3BYTE_LIMIT = 0x10000
private const val UTF8_1BYTE_OCTETS = 1
private const val UTF8_2BYTE_OCTETS = 2
private const val UTF8_3BYTE_OCTETS = 3
private const val UTF8_4BYTE_OCTETS = 4
