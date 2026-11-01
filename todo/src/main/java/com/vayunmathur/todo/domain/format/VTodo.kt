package com.vayunmathur.todo.domain.format

import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.DueDates
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * iCalendar VTODO (RFC 5545). All-day due dates use `DUE;VALUE=DATE`, timed ones UTC.
 * Priority 1–4 maps onto iCalendar's 1–9 scale as 1/3/5/7; subtasks use RELATED-TO;
 * the list rides in X-TODO-LIST so a round trip keeps it.
 */
object VTodo {
    private const val FOLD_AT = 75
    private const val PRODID = "-//Modern Apps//Todo//EN"
    private const val UID_HOST = "@com.vayunmathur.todo"
    private val ICAL_PRIORITY = mapOf(1 to 1, 2 to 3, 3 to 5, 4 to 7)
    private const val ICAL_LOWEST = 9

    fun export(source: ExportSource): String {
        val out = StringBuilder()
        out.line("BEGIN:VCALENDAR")
        out.line("VERSION:2.0")
        out.line("PRODID:$PRODID")
        source.todos.filter { !it.deleted }.forEach { t -> vtodo(out, t, source) }
        out.line("END:VCALENDAR")
        return out.toString()
    }

    private fun vtodo(out: StringBuilder, t: Todo, source: ExportSource) {
        out.line("BEGIN:VTODO")
        out.line("UID:${t.syncId}$UID_HOST")
        out.line("DTSTAMP:${utc(t.updatedAt)}")
        out.line("CREATED:${utc(t.createdAt)}")
        out.line("LAST-MODIFIED:${utc(t.updatedAt)}")
        out.line("SUMMARY:${escape(t.title)}")
        if (t.notes.isNotBlank()) out.line("DESCRIPTION:${escape(t.notes)}")
        val zone = TimeZone.currentSystemDefault()
        t.dueAt?.let { due ->
            if (t.dueAllDay) out.line("DUE;VALUE=DATE:${localDate(due, zone)}") else out.line("DUE:${utc(due)}")
        }
        t.startAt?.let { out.line("DTSTART;VALUE=DATE:${localDate(it, zone)}") }
        out.line("STATUS:${if (t.done) "COMPLETED" else "NEEDS-ACTION"}")
        t.completedAt?.takeIf { t.done }?.let { out.line("COMPLETED:${utc(it)}") }
        ICAL_PRIORITY[t.priority]?.let { out.line("PRIORITY:$it") }
        if (t.tags.isNotEmpty()) out.line("CATEGORIES:${t.tags.joinToString(",") { escape(it) }}")
        t.recurrence?.substringBefore(";X-")?.let { out.line("RRULE:$it") }
        t.parentSyncId?.let { out.line("RELATED-TO;RELTYPE=PARENT:$it$UID_HOST") }
        source.listName(t)?.let { out.line("X-TODO-LIST:${escape(it)}") }
        t.section?.let { out.line("X-TODO-SECTION:${escape(it)}") }
        t.recurrence?.takeIf { "X-FROM=COMPLETION" in it }?.let { out.line("X-TODO-RECURRENCE:$it") }
        out.line("END:VTODO")
    }

    fun parse(text: String, zone: TimeZone): List<ImportedTask> {
        val lines = unfold(text)
        val tasks = mutableListOf<ImportedTask>()
        var props: MutableMap<String, Pair<Map<String, String>, String>>? = null
        for (line in lines) {
            when {
                line.equals("BEGIN:VTODO", true) -> props = mutableMapOf()
                line.equals("END:VTODO", true) -> props?.let { tasks += toTask(it, zone) }.also { props = null }
                props != null -> parseProperty(line)?.let { (name, params, value) -> props!![name] = params to value }
            }
        }
        return tasks
    }

    private fun toTask(p: Map<String, Pair<Map<String, String>, String>>, zone: TimeZone): ImportedTask {
        fun v(name: String) = p[name]?.second
        fun at(name: String) = p[name]?.let { (params, value) -> parseDateTime(value, params, zone) }
        val due = at("DUE")
        return ImportedTask(
            title = unescape(v("SUMMARY").orEmpty()),
            notes = unescape(v("DESCRIPTION").orEmpty()),
            done = v("STATUS").equals("COMPLETED", true) || v("COMPLETED") != null,
            dueAt = due?.first,
            dueAllDay = due?.second ?: true,
            priority = fromIcalPriority(v("PRIORITY")?.toIntOrNull() ?: 0),
            listName = v("X-TODO-LIST")?.let { unescape(it) },
            section = v("X-TODO-SECTION")?.let { unescape(it) },
            tags = v("CATEGORIES")?.split(',')?.map { unescape(it).trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            recurrence = v("X-TODO-RECURRENCE") ?: v("RRULE"),
            startAt = at("DTSTART")?.first,
            createdAt = at("CREATED")?.first,
            completedAt = at("COMPLETED")?.first,
            ref = v("UID")?.substringBefore(UID_HOST),
            parentRef = v("RELATED-TO")?.substringBefore(UID_HOST),
        )
    }

    /** iCalendar 1 (highest) … 9 onto 1 … 4; 0 is undefined. */
    private fun fromIcalPriority(ical: Int): Int = when (ical) {
        1 -> 1
        in 2..3 -> 2
        in 4..5 -> 3
        in 6..ICAL_LOWEST -> Todo.PRIORITY_MAX
        else -> 0
    }

    /** Epoch ms plus whether it is a date-only (all-day) value. */
    private fun parseDateTime(
        value: String,
        params: Map<String, String>,
        zone: TimeZone,
    ): Pair<Long, Boolean>? = runCatching {
        if (params["VALUE"].equals("DATE", true) || value.length == BASIC_DATE_LEN) {
            DueDates.startOfDay(parseBasicDate(value), zone) to true
        } else {
            val date = parseBasicDate(value.take(BASIC_DATE_LEN))
            val t = value.drop(BASIC_DATE_LEN + 1)
            val time = LocalTime(t.take(2).toInt(), t.substring(2, 4).toInt(), t.substring(4, 6).toInt())
            val ldt = LocalDateTime(date, time)
            val tz = when {
                value.endsWith("Z") -> TimeZone.UTC
                params["TZID"] != null -> runCatching { TimeZone.of(params.getValue("TZID")) }.getOrDefault(zone)
                else -> zone
            }
            ldt.toInstant(tz).toEpochMilliseconds() to false
        }
    }.getOrNull()

    private fun parseProperty(line: String): Triple<String, Map<String, String>, String>? {
        val colon = line.indexOf(':').takeIf { it > 0 } ?: return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { kv ->
            kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] }
        }.toMap()
        return Triple(head[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        text.replace("\r\n", "\n").split('\n').forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) out[out.lastIndex] += line.drop(1)
            else if (line.isNotEmpty()) out += line
        }
        return out
    }

    private fun StringBuilder.line(content: String) {
        var rest = content
        var first = true
        while (rest.isNotEmpty()) {
            val limit = if (first) FOLD_AT else FOLD_AT - 1
            val cut = safeCut(rest, limit)
            if (!first) append(' ')
            append(rest, 0, cut).append("\r\n")
            rest = rest.substring(cut)
            first = false
        }
    }

    /** Longest prefix of at most [limit] UTF-8 octets that does not split a character. */
    private fun safeCut(s: String, limit: Int): Int {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > limit) break
            bytes += size
            i += Character.charCount(cp)
        }
        return i.coerceAtLeast(1)
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
    private fun unescape(s: String) = s
        .replace("\\n", "\n")
        .replace("\\N", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")

    private fun utc(ms: Long): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC)
        return basicDate(t.date) + "T" + t.hour.pad() + t.minute.pad() + t.second.pad() + "Z"
    }

    private fun basicDate(d: LocalDate) = d.toString().replace("-", "")
    private fun localDate(ms: Long, zone: TimeZone) = basicDate(DueDates.localDate(ms, zone))
    private fun parseBasicDate(s: String) = LocalDate.parse("${s.take(4)}-${s.substring(4, 6)}-${s.substring(6, 8)}")
    private fun Int.pad() = toString().padStart(2, '0')
    private const val BASIC_DATE_LEN = 8
}
