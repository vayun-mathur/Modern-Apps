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
                t.dueAt?.takeIf { !t.dueAllDay }?.let { DueDates.localTime(it, zone).toString().take(HHMM) }.orEmpty(),
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

    private fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field

    fun parse(text: String, zone: TimeZone): List<ImportedTask> {
        val rows = readRows(text)
        val header = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return emptyList()
        fun col(row: List<String>, name: String) = header.indexOf(name.lowercase()).takeIf { it >= 0 }?.let { row.getOrNull(it) }.orEmpty()
        return rows.drop(1).filter { row -> row.any { it.isNotBlank() } }.map { row ->
            val dueDate = col(row, "Due date").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val dueTime = col(row, "Due time").takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ImportedTask(
                title = col(row, "Title"),
                notes = col(row, "Notes"),
                done = col(row, "Done").lowercase() in setOf("yes", "true", "1", "x"),
                dueAt = dueDate?.let { d -> if (dueTime != null) DueDates.at(d, dueTime, zone) else DueDates.startOfDay(d, zone) },
                dueAllDay = dueTime == null,
                priority = col(row, "Priority").toIntOrNull()?.takeIf { it in 1..Todo.PRIORITY_MAX } ?: 0,
                listName = col(row, "List").ifBlank { null },
                section = col(row, "Section").ifBlank { null },
                tags = col(row, "Tags").split(' ', ',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() },
                recurrence = col(row, "Repeat").ifBlank { null },
                startAt = date(col(row, "Start date"), zone),
                createdAt = date(col(row, "Created"), zone),
                completedAt = date(col(row, "Completed"), zone),
                ref = col(row, "Id").ifBlank { null },
                parentRef = col(row, "Parent id").ifBlank { null },
            )
        }
    }

    private fun date(raw: String, zone: TimeZone): Long? =
        raw.takeIf { it.isNotBlank() }?.let { runCatching { DueDates.startOfDay(LocalDate.parse(it), zone) }.getOrNull() }

    /** Splits CSV into rows of fields, honouring quoted commas, quotes and newlines. */
    internal fun readRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += field.toString(); field.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += field.toString(); field.clear()
                    rows += row; row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows
    }

    private const val HHMM = 5
}
