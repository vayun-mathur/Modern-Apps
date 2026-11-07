package com.vayunmathur.everysync.domain.format

import com.vayunmathur.everysync.data.RemoteEvent
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal iCalendar (RFC 5545) VEVENT reader/writer covering the fields
 * EverySync maps into CalendarContract. Self-contained; RRULE lines are carried
 * through verbatim.
 */
object ICalendar {

    /** Parse a VCALENDAR body into its VEVENTs. [calendarId] tags each result. */
    fun parse(text: String, calendarId: String, fallbackUid: String? = null): List<RemoteEvent> {
        val lines = unfold(text)
        val events = mutableListOf<RemoteEvent>()
        var cur: MutableMap<String, Pair<String, String>>? = null // key -> (params, value)
        for (line in lines) {
            val trimmed = line.trimEnd()
            when {
                trimmed.equals("BEGIN:VEVENT", true) -> cur = mutableMapOf()
                trimmed.equals("END:VEVENT", true) -> {
                    cur?.let { events += toEvent(it, calendarId, fallbackUid) }
                    cur = null
                }
                cur != null -> {
                    val prop = splitProperty(trimmed)
                    if (prop != null) {
                        val (name, params, value) = prop
                        cur[name.uppercase()] = params to value
                    }
                }
            }
        }
        return events
    }

    fun serialize(e: RemoteEvent): String = buildString {
        append("BEGIN:VCALENDAR\r\n")
        append("VERSION:2.0\r\n")
        append("PRODID:-//vayunmathur//EverySync//EN\r\n")
        append("BEGIN:VEVENT\r\n")
        append("UID:${e.uid}\r\n")
        e.recurrenceId?.takeIf { it.isNotBlank() }?.let { append("RECURRENCE-ID:${it}\r\n") }
        if (e.allDay) {
            append("DTSTART;VALUE=DATE:${dateOnly(e.startMillis)}\r\n")
            append("DTEND;VALUE=DATE:${dateOnly(e.endMillis)}\r\n")
        } else {
            append("DTSTART:${utc(e.startMillis)}\r\n")
            append("DTEND:${utc(e.endMillis)}\r\n")
        }
        if (e.summary.isNotBlank()) append("SUMMARY:${esc(e.summary)}\r\n")
        if (e.description.isNotBlank()) append("DESCRIPTION:${esc(e.description)}\r\n")
        if (e.location.isNotBlank()) append("LOCATION:${esc(e.location)}\r\n")
        e.rrule?.takeIf { it.isNotBlank() }?.let { append("RRULE:${it.removePrefix("RRULE:")}\r\n") }
        append("END:VEVENT\r\n")
        append("END:VCALENDAR\r\n")
    }

    private fun toEvent(
        props: Map<String, Pair<String, String>>,
        calendarId: String,
        fallbackUid: String?,
    ): RemoteEvent {
        val uid = props["UID"]?.second?.ifBlank { null } ?: fallbackUid ?: props.hashCode().toString()
        val (startMillis, startAllDay, tz) = parseTime(props["DTSTART"])
        val (endMillisRaw, _, _) = parseTime(props["DTEND"])
        val start = startMillis ?: 0L
        var end = endMillisRaw ?: (start + if (startAllDay) DAY_MS else HOUR_MS)
        if (startAllDay && end == start) end = start + DAY_MS
        return RemoteEvent(
            uid = uid,
            calendarId = calendarId,
            summary = unescape(props["SUMMARY"]?.second ?: ""),
            description = unescape(props["DESCRIPTION"]?.second ?: ""),
            location = unescape(props["LOCATION"]?.second ?: ""),
            startMillis = start,
            endMillis = end,
            allDay = startAllDay,
            timezone = tz ?: "UTC",
            rrule = props["RRULE"]?.second?.ifBlank { null },
            recurrenceId = props["RECURRENCE-ID"]?.second?.ifBlank { null },
        )
    }

    /** Returns (epochMillis?, isAllDay, tzid?). */
    private fun parseTime(prop: Pair<String, String>?): Triple<Long?, Boolean, String?> {
        if (prop == null) return Triple(null, false, null)
        val (params, value) = prop
        val up = params.uppercase()
        val allDay = up.contains("VALUE=DATE") ||
            (value.length == DATE_ONLY_LEN && value.all { it.isDigit() })
        return try {
            when {
                allDay -> {
                    val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    Triple(fmt.parse(value)?.time, true, "UTC")
                }
                value.endsWith("Z") -> {
                    val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    Triple(fmt.parse(value)?.time, false, "UTC")
                }
                else -> {
                    val tzid = extractTzid(params)
                    val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                        timeZone = tzid?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getTimeZone("UTC")
                    }
                    Triple(fmt.parse(value)?.time, false, tzid ?: "UTC")
                }
            }
        } catch (_: Exception) {
            Triple(null, allDay, null)
        }
    }

    private fun extractTzid(params: String): String? =
        params.split(";").map { it.split("=", limit = 2) }
            .firstOrNull { it.size == 2 && it[0].uppercase() == "TZID" }?.get(1)

    private fun utc(millis: Long): String =
        SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

    private fun dateOnly(millis: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(millis)

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").split("\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + raw.trimStart()
            } else {
                out.add(raw)
            }
        }
        return out
    }

    private fun splitProperty(line: String): Triple<String, String, String>? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val left = line.take(colon)
        val value = line.substring(colon + 1)
        val semi = left.indexOf(';')
        return if (semi > 0) Triple(left.take(semi), left.substring(semi + 1), value)
        else Triple(left, "", value)
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    /**
     * Single-pass unescape. Sequential String.replace calls corrupt text
     * that itself contains a backslash: an escaped backslash followed by
     * 'n' ("\\n" on the wire, a literal backslash + n in the value) would
     * first become backslash + newline.
     */
    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    ',', ';', '\\' -> out.append(s[i + 1])
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val DAY_MS = 24L * HOUR_MS
    private const val DATE_ONLY_LEN = 8
}
