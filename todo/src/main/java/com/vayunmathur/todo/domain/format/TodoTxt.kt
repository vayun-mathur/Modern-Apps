package com.vayunmathur.todo.domain.format

import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.Recurrence
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * todo.txt (https://github.com/todotxt/todo.txt): `x done-date created-date (A) title
 * +Project @context due:YYYY-MM-DD t:YYYY-MM-DD rec:[+]Nd|w|m|y`.
 *
 * Lists map to `+Project` (spaces as `_`), tags to `@context`, priorities 1–4 to (A)–(D).
 * `rec:+1w` repeats from the due date and `rec:1w` from completion, as in SimpleTask. Notes
 * and times of day have no todo.txt form and are dropped on export.
 */
object TodoTxt {
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val PRIORITY = Regex("^\\(([A-D])\\)$")
    private val REC = Regex("^(\\+?)(\\d+)([dwmy])$")
    private const val DAYS_PER_WEEK = 7

    fun export(source: ExportSource, zone: TimeZone): String =
        source.todos.filter { !it.deleted && it.parentSyncId == null }.joinToString("\n") { line(it, source, zone) } + "\n"

    private fun line(todo: Todo, source: ExportSource, zone: TimeZone): String {
        val parts = mutableListOf<String>()
        if (todo.done) {
            parts += "x"
            todo.completedAt?.let { parts += date(it, zone) }
        } else if (todo.priority in 1..Todo.PRIORITY_MAX) {
            parts += "(" + ('A' + todo.priority - 1) + ")"
        }
        parts += date(todo.createdAt, zone)
        parts += todo.title.replace('\n', ' ')
        source.listName(todo)?.let { parts += "+" + it.replace(' ', '_') }
        todo.tags.forEach { parts += "@" + it.replace(' ', '_') }
        todo.dueAt?.let { parts += "due:" + date(it, zone) }
        todo.startAt?.let { parts += "t:" + date(it, zone) }
        rec(todo.recurrence)?.let { parts += "rec:$it" }
        if (todo.done && todo.priority in 1..Todo.PRIORITY_MAX) parts += "pri:" + ('A' + todo.priority - 1)
        return parts.joinToString(" ")
    }

    private fun date(ms: Long, zone: TimeZone) = DueDates.localDate(ms, zone).toString()

    private fun rec(raw: String?): String? = when (val r = Recurrence.parse(raw)) {
        null -> null
        is Recurrence.Daily -> "+${r.interval}d"
        is Recurrence.Weekly -> "+${r.interval}w"
        is Recurrence.Monthly -> "+${r.interval}m"
        is Recurrence.Yearly -> "+${r.interval}y"
        is Recurrence.AfterCompletion ->
            if (r.interval % DAYS_PER_WEEK == 0) "${r.interval / DAYS_PER_WEEK}w" else "${r.interval}d"
    }

    fun parse(text: String, zone: TimeZone): List<ImportedTask> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { parseLine(it, zone) }.toList()

    private fun parseLine(line: String, zone: TimeZone): ImportedTask {
        val words = line.split(Regex("\\s+")).toMutableList()
        var done = false
        var completedAt: Long? = null
        var priority = 0
        if (words.firstOrNull() == "x") {
            done = true
            words.removeAt(0)
            if (words.firstOrNull()?.matches(DATE) == true) completedAt = toMs(words.removeAt(0), zone)
        }
        PRIORITY.find(words.firstOrNull().orEmpty())?.let {
            priority = it.groupValues[1][0] - 'A' + 1
            words.removeAt(0)
        }
        val createdAt = if (words.firstOrNull()?.matches(DATE) == true) toMs(words.removeAt(0), zone) else null
        return parseBody(words, zone).copy(done = done, completedAt = completedAt, createdAt = createdAt).let {
            if (priority != 0) it.copy(priority = priority) else it
        }
    }

    private fun parseBody(words: List<String>, zone: TimeZone): ImportedTask {
        val title = mutableListOf<String>()
        val tags = mutableListOf<String>()
        var list: String? = null
        var due: Long? = null
        var start: Long? = null
        var recurrence: String? = null
        var priority = 0
        for (w in words) {
            val value = w.substringAfter(':', "")
            when {
                w.startsWith("+") && w.length > 1 && list == null -> list = w.drop(1).replace('_', ' ')
                w.startsWith("@") && w.length > 1 -> tags += w.drop(1)
                w.startsWith("due:") && value.matches(DATE) -> due = toMs(value, zone)
                w.startsWith("t:") && value.matches(DATE) -> start = toMs(value, zone)
                w.startsWith("rec:") -> recurrence = parseRec(value)
                w.startsWith("pri:") && value.length == 1 && value[0] in 'A'..'D' -> priority = value[0] - 'A' + 1
                else -> title += w
            }
        }
        return ImportedTask(
            title = title.joinToString(" "),
            dueAt = due,
            priority = priority,
            listName = list,
            tags = tags,
            recurrence = recurrence,
            startAt = start,
        )
    }

    private fun parseRec(value: String): String? {
        val (strict, n, unit) = REC.find(value)?.destructured ?: return null
        val count = n.toInt().coerceAtLeast(1)
        val r = when {
            strict.isEmpty() && unit == "d" -> Recurrence.AfterCompletion(count)
            strict.isEmpty() && unit == "w" -> Recurrence.AfterCompletion(count * DAYS_PER_WEEK)
            unit == "d" -> Recurrence.Daily(count)
            unit == "w" -> Recurrence.Weekly(count)
            unit == "m" -> Recurrence.Monthly(count)
            else -> Recurrence.Yearly(count)
        }
        return r.serialize()
    }

    private fun toMs(date: String, zone: TimeZone) = DueDates.startOfDay(LocalDate.parse(date), zone)
}
