package com.vayunmathur.todo.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/**
 * Parses quick-add text such as "Pay rent every 1st #home !1 tomorrow 9am @Bills".
 *
 * Recognised pieces are removed from the title and reported as [Token]s (with their ranges
 * in the input) so the UI can highlight them. Matching is English-only and deliberately
 * conservative: a bare number is never a time, so titles like "Buy 2 apples" survive.
 */
object QuickAddParser {

    enum class Kind { DATE, TIME, RECURRENCE, TAG, PRIORITY, LIST }

    data class Token(val kind: Kind, val range: IntRange, val text: String)

    data class Result(
        val title: String,
        val dueAt: Long? = null,
        val dueAllDay: Boolean = true,
        val recurrence: Recurrence? = null,
        val tags: List<String> = emptyList(),
        val priority: Int = 0,
        val listSyncId: String? = null,
        val tokens: List<Token> = emptyList(),
    )

    /** A list the `@name` syntax can target. */
    data class ListName(val syncId: String, val name: String)

    internal class Word(val raw: String, val range: IntRange) {
        val text: String = raw.lowercase().trimEnd(',', '.', ';')
    }

    internal class State(val input: String, val words: List<Word>, val today: LocalDate, val nowTime: LocalTime) {
        val used = BooleanArray(words.size)
        val tokens = mutableListOf<Token>()
        var date: LocalDate? = null
        var time: LocalTime? = null
        var recurrence: Recurrence? = null
        val tags = mutableListOf<String>()
        var priority = 0
        var listSyncId: String? = null

        fun free(i: Int) = i in words.indices && !used[i]
        fun text(i: Int) = if (free(i)) words[i].text else null

        fun consume(kind: Kind, from: Int, count: Int) {
            for (i in from until from + count) used[i] = true
            val range = words[from].range.first..words[from + count - 1].range.last
            tokens += Token(kind, range, input.substring(range))
        }
    }

    fun parse(input: String, now: Long, zone: TimeZone, lists: List<ListName> = emptyList()): Result {
        val words = Regex("\\S+").findAll(input).map { Word(it.value, it.range) }.toList()
        val state = State(input, words, DueDates.localDate(now, zone), DueDates.localTime(now, zone))
        parseMarkers(state, lists)
        RecurrenceWords.parse(state)
        DateWords.parse(state)
        TimeWords.parse(state)

        val title = words.filterIndexed { i, _ -> !state.used[i] }.joinToString(" ") { it.raw }
        var date = state.date
        val time = state.time
        val rec = state.recurrence
        if (date == null && rec != null) date = RecurrenceWords.firstOccurrence(rec, state.today)
        if (date == null && time != null) {
            date = if (time > state.nowTime) state.today else state.today.plus(1, DateTimeUnit.DAY)
        }
        val dueAt = date?.let { d -> if (time != null) DueDates.at(d, time, zone) else DueDates.startOfDay(d, zone) }
        return Result(
            title = title,
            dueAt = dueAt,
            dueAllDay = time == null,
            recurrence = rec,
            tags = state.tags.distinctBy { it.lowercase() },
            priority = state.priority,
            listSyncId = state.listSyncId,
            tokens = state.tokens.sortedBy { it.range.first },
        )
    }

    private val TAG = Regex("^#([\\p{L}\\p{N}_-]+)$")
    private val PRIORITY = Regex("^!([1-4])$")

    private fun parseMarkers(state: State, lists: List<ListName>) {
        val byLength = lists.sortedByDescending { it.name.length }
        for (i in state.words.indices) {
            if (!state.free(i)) continue
            val raw = state.words[i].raw.trimEnd(',', '.', ';')
            val tag = TAG.find(raw)
            val priority = PRIORITY.find(raw)
            when {
                tag != null -> {
                    state.tags += tag.groupValues[1]
                    state.consume(Kind.TAG, i, 1)
                }
                priority != null -> {
                    state.priority = priority.groupValues[1].toInt()
                    state.consume(Kind.PRIORITY, i, 1)
                }
                raw.startsWith("@") && state.listSyncId == null -> matchList(state, i, byLength)
            }
        }
    }

    /** `@Name`, where the name may span several words. Longest list name wins. */
    private fun matchList(state: State, start: Int, lists: List<ListName>) {
        val rest = state.input.substring(state.words[start].range.first + 1)
        val match = lists.firstOrNull { list ->
            rest.startsWith(list.name, ignoreCase = true) &&
                (rest.length == list.name.length || !rest[list.name.length].isLetterOrDigit())
        } ?: return
        val spanEnd = state.words[start].range.first + match.name.length
        var count = 0
        while (start + count < state.words.size && state.words[start + count].range.first <= spanEnd) count++
        state.listSyncId = match.syncId
        state.consume(Kind.LIST, start, count)
    }

    internal val WEEKDAYS: Map<String, DayOfWeek> = buildMap {
        DayOfWeek.entries.forEach { day ->
            val full = day.name.lowercase()
            put(full, day)
            put(full.take(3), day)
        }
        put("tues", DayOfWeek.TUESDAY)
        put("weds", DayOfWeek.WEDNESDAY)
        put("thur", DayOfWeek.THURSDAY)
        put("thurs", DayOfWeek.THURSDAY)
    }

    internal val FULL_WEEKDAYS: Map<String, DayOfWeek> =
        DayOfWeek.entries.associateBy { it.name.lowercase() }

    internal fun nextOnOrAfter(from: LocalDate, day: DayOfWeek): LocalDate =
        from.plus((day.isoDayNumber - from.dayOfWeek.isoDayNumber + 7) % 7, DateTimeUnit.DAY)
}
