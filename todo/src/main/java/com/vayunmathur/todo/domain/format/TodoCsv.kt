package com.vayunmathur.todo.domain.format

import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.DueDates
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** RFC 4180 CSV with a header row; dates ISO-8601 in the user's zone, times HH:MM. */
object TodoCsv {
    private val HEADER = listOf(
        "Title", "Notes", "Done", "Due date", "Due time", "Priority", "List", "Section", "Tags",
        "Repeat", "Start date", "Created", "Completed", "Id", "Parent id",
    )

    fun export(source: ExportSource, zone: TimeZone): String {
        val rows = source.todos.filter { !it.deleted }.map { t ->
            listOf(
                t.title,
                t.notes,
                if (t.done) "yes" else "no",
                t.dueAt?.let { DueDates.localDate(it, zone).toString() }.orEmpty(),
                t.dueAt?.takeIf { !t.dueAllDay }?.let { DueDates.localTime(it, zone).toString().take(HHMM) }
                    .orEmpty(),
                t.priority.takeIf { it > 0 }?.toString().orEmpty(),
                source.listName(t).orEmpty(),
                t.section.orEmpty(),
                t.tags.joinToString(" "),
                t.recurrence.orEmpty(),
                t.startAt?.let { DueDates.localDate(it, zone).toString() }.orEmpty(),
                DueDates.localDate(t.createdAt, zone).toString(),
                t.completedAt?.let { DueDates.localDate(it, zone).toString() }.orEmpty(),
                t.syncId,
                t.parentSyncId.orEmpty(),
            )
        }
        return (listOf(HEADER) + rows).joinToString("\r\n") { row -> row.joinToString(",") { quote(it) } } + "\r\n"
    }

    private val SPECIAL = setOf(',', '"', '\n', '\r')

    private fun quote(field: String): String =
        if (field.any { it in SPECIAL }) "\"" + field.replace("\"", "\"\"") + "\"" else field

    fun parse(text: String, zone: TimeZone): List<ImportedTask> {
        val rows = readRows(text)
        val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return emptyList()
        return rows.drop(1).filter { row -> row.any { it.isNotBlank() } }.map { row -> toTask(Row(header, row), zone) }
    }

    private class Row(private val header: List<String>, private val fields: List<String>) {
        operator fun get(name: String): String =
            header.indexOf(name.lowercase()).takeIf { it >= 0 }?.let { fields.getOrNull(it) }.orEmpty()
    }

    private fun toTask(row: Row, zone: TimeZone): ImportedTask {
        val dueDate = row["Due date"].parseOrNull { LocalDate.parse(it) }
        val dueTime = row["Due time"].parseOrNull { LocalTime.parse(it) }
        return ImportedTask(
            title = row["Title"],
            notes = row["Notes"],
            done = row["Done"].lowercase() in setOf("yes", "true", "1", "x"),
            dueAt = dueDate?.let { d -> dueTime?.let { DueDates.at(d, it, zone) } ?: DueDates.startOfDay(d, zone) },
            dueAllDay = dueTime == null,
            priority = row["Priority"].toIntOrNull()?.takeIf { it in 1..Todo.PRIORITY_MAX } ?: 0,
            listName = row["List"].ifBlank { null },
            section = row["Section"].ifBlank { null },
            tags = row["Tags"].split(' ', ',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() },
            recurrence = row["Repeat"].ifBlank { null },
            startAt = date(row["Start date"], zone),
            createdAt = date(row["Created"], zone),
            completedAt = date(row["Completed"], zone),
            ref = row["Id"].ifBlank { null },
            parentRef = row["Parent id"].ifBlank { null },
        )
    }

    private fun <T> String.parseOrNull(parse: (String) -> T): T? =
        takeIf { it.isNotBlank() }?.let { runCatching { parse(it) }.getOrNull() }

    private fun date(raw: String, zone: TimeZone): Long? =
        raw.parseOrNull { DueDates.startOfDay(LocalDate.parse(it), zone) }

    /** Splits CSV into rows of fields, honouring quoted commas, quotes and newlines. */
    internal fun readRows(text: String): List<List<String>> {
        val reader = RowReader()
        var i = 0
        while (i < text.length) i += reader.step(text[i], text.getOrNull(i + 1))
        return reader.finish()
    }

    private class RowReader {
        private val rows = mutableListOf<List<String>>()
        private var row = mutableListOf<String>()
        private val field = StringBuilder()
        private var quoted = false

        /** Consumes [c] (peeking at [next]) and returns how many characters were used. */
        fun step(c: Char, next: Char?): Int {
            if (quoted) return quotedStep(c, next)
            when (c) {
                '"' -> quoted = true
                ',' -> endField()
                '\n' -> endRow()
                '\r' -> {
                    endRow()
                    if (next == '\n') return 2
                }
                else -> field.append(c)
            }
            return 1
        }

        private fun quotedStep(c: Char, next: Char?): Int {
            if (c != '"') {
                field.append(c)
                return 1
            }
            if (next == '"') {
                field.append('"')
                return 2
            }
            quoted = false
            return 1
        }

        private fun endField() {
            row += field.toString()
            field.clear()
        }

        private fun endRow() {
            endField()
            rows += row
            row = mutableListOf()
        }

        fun finish(): List<List<String>> {
            if (field.isNotEmpty() || row.isNotEmpty()) endRow()
            return rows
        }
    }

    private const val HHMM = 5
}
