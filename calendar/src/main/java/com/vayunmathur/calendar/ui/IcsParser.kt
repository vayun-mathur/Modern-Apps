package com.vayunmathur.calendar.ui

import com.vayunmathur.library.log.Log
import com.vayunmathur.calendar.data.Event
import com.vayunmathur.calendar.domain.AllDayFormat
import com.vayunmathur.calendar.domain.RRule
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import java.io.BufferedInputStream
import java.io.InputStream
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

// Non-UI ICS parsing implementation. Kept separate from IcsImport.kt so the UI file
// stays under the FileLength limit. No composables in this file.
internal fun parseICSFileImpl(iS: InputStream): List<Event> {
    val events = mutableListOf<Event>()
    val lines = unfoldIcsLines(BufferedInputStream(iS).bufferedReader().readLines())

    var current = mutableMapOf<String, String>()
    // RDATE and EXDATE can appear on several lines in one VEVENT, so they accumulate.
    var rdateProps = mutableListOf<Pair<String, String>>()
    var exdateProps = mutableListOf<Pair<String, String>>()
    var inEvent = false
    // VALARM sits inside VEVENT and carries its own DESCRIPTION; only TRIGGER is read.
    var inAlarm = false
    var alarmReminders = mutableListOf<Int>()
    var skippedCount = 0

    for (raw in lines) {
        val line = raw.trimEnd()
        if (line.equals("BEGIN:VALARM", ignoreCase = true)) {
            inAlarm = true
            continue
        }
        if (line.equals("END:VALARM", ignoreCase = true)) {
            inAlarm = false
            continue
        }
        if (inAlarm) {
            handleAlarmLine(line)?.let { alarmReminders.add(it) }
            continue
        }
        if (line.equals("BEGIN:VEVENT", ignoreCase = true)) {
            inEvent = true
            current = mutableMapOf()
            rdateProps = mutableListOf()
            exdateProps = mutableListOf()
            alarmReminders = mutableListOf()
            continue
        }
        if (line.equals("END:VEVENT", ignoreCase = true)) {
            if (!buildEventFromProps(current, rdateProps, exdateProps, alarmReminders, events)) {
                skippedCount++
            }
            inEvent = false
            current = mutableMapOf()
            rdateProps = mutableListOf()
            exdateProps = mutableListOf()
            alarmReminders = mutableListOf()
            continue
        }

        if (!inEvent) continue
        handlePropertyLine(line, current, rdateProps, exdateProps)
    }

    return events.also {
        if (skippedCount > 0) Log.status("IcsImport", "Skipped $skippedCount VEVENT(s)")
    }
}

/**
 * Unfolds ICS lines: a line starting with a space or tab continues the previous
 * one, with exactly one fold-marker character removed.
 */
private fun unfoldIcsLines(rawLines: List<String>): List<String> {
    val lines = mutableListOf<String>()
    for (line in rawLines) {
        if (line.startsWith(" ") || line.startsWith('\t')) {
            val continued = line.substring(1)
            if (lines.isNotEmpty()) {
                val prev = lines.removeAt(lines.size - 1)
                lines.add(prev + continued)
            } else {
                lines.add(continued)
            }
        } else {
            lines.add(line)
        }
    }
    return lines
}

/**
 * Reads one VALARM content line, returning the minutes-before-start when it is a
 * start-relative TRIGGER (RELATED=END counts from event end and is skipped).
 */
private fun handleAlarmLine(line: String): Int? {
    // Only TRIGGER feeds Event.reminders; RELATED=END counts from event end.
    val alarmColon = line.indexOf(':')
    if (alarmColon <= 0) return null
    val alarmLeft = line.take(alarmColon)
    if (!alarmLeft.substringBefore(';').equals("TRIGGER", ignoreCase = true) ||
        alarmLeft.uppercase().contains("RELATED=END")
    ) {
        return null
    }
    return parseTriggerMinutesBefore(line.substring(alarmColon + 1))
}

/** Routes one VEVENT property line into the current property map or date lists. */
private fun handlePropertyLine(
    line: String,
    current: MutableMap<String, String>,
    rdateProps: MutableList<Pair<String, String>>,
    exdateProps: MutableList<Pair<String, String>>,
) {
    val colonIndex = line.indexOf(':')
    if (colonIndex <= 0) return
    val left = line.take(colonIndex)
    val value = line.substring(colonIndex + 1)

    val semicolonIndex = left.indexOf(';')
    val propName = if (semicolonIndex > 0) left.take(semicolonIndex).uppercase() else left.uppercase()

    when (propName) {
        "DTSTART" -> {
            current["DTSTART"] = value
            current["DTSTART_PROP"] = left
        }
        "DTEND" -> {
            current["DTEND"] = value
            current["DTEND_PROP"] = left
        }
        "RECURRENCE-ID" -> {
            current["RECURRENCE-ID"] = value
            current["RECURRENCE-ID_PROP"] = left
        }
        "RDATE" -> rdateProps.add(left to value)
        "EXDATE" -> exdateProps.add(left to value)
        else -> current[propName] = value
    }
}

/**
 * Builds one [Event] from accumulated VEVENT properties, appending it to [events].
 * Returns false when DTSTART was unparseable and the event was skipped.
 */
private fun buildEventFromProps(
    current: Map<String, String>,
    rdateProps: List<Pair<String, String>>,
    exdateProps: List<Pair<String, String>>,
    alarmReminders: List<Int>,
    events: MutableList<Event>,
): Boolean {
    var added = false
    try {
        val uid = current["UID"] ?: current["ID"] ?: ""
        val id = if (uid.isNotBlank()) uid.hashCode().toLong() else null
        val title = unescapeIcsText(current["SUMMARY"] ?: "Untitled")
        val description = unescapeIcsText(current["DESCRIPTION"] ?: "")
        val location = unescapeIcsText(current["LOCATION"] ?: "")

        val (startMillis, startAllDay, startTz) = parseICSTime(current["DTSTART_PROP"], current["DTSTART"])
        val (endMillisRaw, _, endTzRaw) = parseICSTime(current["DTEND_PROP"], current["DTEND"])

        if (startMillis == null) {
            Log.status("IcsImport", "Skipping VEVENT with unparseable DTSTART: $current")
        } else {
            var endMillis = endMillisRaw ?: startMillis
            val duration = current["DURATION"]
            if (endMillisRaw == null && duration != null) {
                endMillis = tryParseDurationMillis(duration, startMillis) ?: startMillis
            }
            val timezone = startTz ?: endTzRaw ?: "UTC"
            val rrule = current["RRULE"]?.let { RRule.parse(it, TimeZone.of(timezone)) }
            if (startAllDay && endMillis == startMillis) {
                endMillis = startMillis + 1.days.inWholeMilliseconds
            }
            val zone = TimeZone.of(timezone)
            val startDate = Instant.fromEpochMilliseconds(startMillis).toLocalDateTime(zone).date
            // DTSTART is an occurrence in its own right; keep RDATE as extra days only.
            val rdate = parseIcsDates(rdateProps, zone).filter { it != startDate }
            val exdate = parseIcsDates(exdateProps, zone)

            val recurrenceId = recurrenceIdDate(current, zone)
            val evt = Event(id, -1, title, description, location, null, startMillis, endMillis, timezone,
                startAllDay, rrule, exdate = exdate, rdate = rdate,
                reminders = alarmReminders.distinct().sorted())
            if (recurrenceId == null) {
                events.add(evt)
            } else {
                applyRecurrenceOverride(events, evt, recurrenceId, current)
            }
            added = true
        }
    } catch (expected: Exception) {
        Log.error("IcsImport", "Error parsing VEVENT", expected)
    }
    return added
}

/** The day a RECURRENCE-ID names, or null when this VEVENT is not an override. */
internal fun recurrenceIdDate(current: Map<String, String>, zone: TimeZone): LocalDate? {
    val raw = current["RECURRENCE-ID"] ?: return null
    // Pair RECURRENCE-ID with its own property line (TZID=...), not DTSTART's.
    val propLeft = current["RECURRENCE-ID_PROP"] ?: ""
    return recurrenceIdDateFrom(raw, propLeft, zone)
}

/** Splits one RECURRENCE-ID value into its occurrence day, resolved in [zone]. */
internal fun recurrenceIdDateFrom(raw: String, propLeft: String, zone: TimeZone): LocalDate? {
    val value = raw.trim().substringAfterLast(':')
    if (value.length == 8 && value.all { it.isDigit() }) {
        return runCatching { AllDayFormat.parse(value) }.getOrNull()
    }
    val (millis, _, _) = parseICSTime(propLeft, value)
    return millis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date }
}

/**
 * Folds a RECURRENCE-ID override VEVENT into the series parsed from the same UID.
 *
 * Exporters express a changed or deleted occurrence as a second VEVENT carrying the same UID
 * plus RECURRENCE-ID naming the overridden date. Without this the override arrives as its own
 * event, and its ghost occurrence stays in the series forever (#825).
 *
 * - plain RECURRENCE-ID: cut the date out of the series (RRULE -> EXDATE, RDATE -> dropped)
 *   and, when the override carries its own DTSTART, re-insert it standalone;
 * - RANGE=THISANDFUTURE: truncate the series before the date (RRULE gets an UNTIL, later
 *   RDATEs are dropped) and start the override as the new series there.
 *
 * The series is found by UID hash, matching the ids assigned in [buildEventFromProps].
 */
internal fun applyRecurrenceOverride(
    events: MutableList<Event>,
    override: Event,
    recurrenceId: LocalDate,
    current: Map<String, String>,
) {
    val seriesIndex = events.indexOfFirst { it.id == override.id && it.isRecurring }
    if (seriesIndex < 0) {
        // No series seen for this UID (or the override came first): keep the occurrence
        // standalone rather than dropping the user's data.
        events.add(override.copy(rrule = null, rdate = emptyList(), exdate = emptyList()))
        return
    }
    val series = events[seriesIndex]
    val thisAndFuture = current["RECURRENCE-ID_PROP"]?.uppercase()?.contains("RANGE=THISANDFUTURE") == true
    // Only STATUS:CANCELLED marks a deleted occurrence. DTSTART alone cannot: an override whose
    // DTSTART matches the recurrence id may be a deletion marker, but it may also carry updated
    // metadata (a renamed occurrence at the same time). Treating a match as deleted would
    // silently drop the latter, so a match without CANCELLED is kept as a standalone event.
    val isDeletion = current["STATUS"]?.equals("CANCELLED", ignoreCase = true) == true
    if (thisAndFuture) {
        val truncated = series.copy(
            rrule = series.rrule?.let { rule ->
                rule.withEnd(RRule.EndCondition.Until(recurrenceId.minus(DatePeriod(days = 1))))
            },
            rdate = series.rdate.filter { it < recurrenceId },
            exdate = series.exdate.filter { it < recurrenceId },
        )
        events[seriesIndex] = truncated
        if (!isDeletion) {
            events.add(override.copy(exdate = emptyList()))
        }
        return
    }
    // Cut the date out of the series. An RDATE-listed date is dropped from the list (an
    // EXDATE would be meaningless there — EXDATE only suppresses RRULE-generated instances);
    // an RRULE occurrence is suppressed via EXDATE.
    val cut = if (series.rdate.contains(recurrenceId)) {
        series.copy(rdate = series.rdate.filter { it != recurrenceId })
    } else {
        series.copy(exdate = (series.exdate + recurrenceId).distinct().sorted())
    }
    events[seriesIndex] = cut
    if (!isDeletion) {
        events.add(override.copy(rrule = null, rdate = emptyList(), exdate = emptyList()))
    }
}
