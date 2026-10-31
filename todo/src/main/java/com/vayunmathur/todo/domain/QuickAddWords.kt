package com.vayunmathur.todo.domain

import com.vayunmathur.todo.domain.QuickAddParser.Kind
import com.vayunmathur.todo.domain.QuickAddParser.State
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

/** "every …", "daily", … */
internal object RecurrenceWords {
    private val UNITS = mapOf(
        "day" to DateTimeUnit.DAY, "days" to DateTimeUnit.DAY,
        "week" to DateTimeUnit.WEEK, "weeks" to DateTimeUnit.WEEK,
        "month" to DateTimeUnit.MONTH, "months" to DateTimeUnit.MONTH,
        "year" to DateTimeUnit.YEAR, "years" to DateTimeUnit.YEAR,
    )
    private val STANDALONE = mapOf(
        "daily" to Recurrence.Daily(),
        "weekly" to Recurrence.Weekly(),
        "monthly" to Recurrence.Monthly(),
        "yearly" to Recurrence.Yearly(),
        "annually" to Recurrence.Yearly(),
    )
    private val WORKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val ORDINAL = Regex("^(\\d{1,2})(st|nd|rd|th)$")

    fun parse(state: State) {
        for (i in state.words.indices) {
            if (state.recurrence != null) return
            val t = state.text(i) ?: continue
            STANDALONE[t]?.let {
                state.recurrence = it
                state.consume(Kind.RECURRENCE, i, 1)
            }
            if (t == "every" || t == "every!") parseEvery(state, i, afterCompletion = t.endsWith("!"))
        }
    }

    private fun parseEvery(state: State, i: Int, afterCompletion: Boolean) {
        val next = state.text(i + 1) ?: return
        val simple = when (next) {
            "day" -> unitRecurrence(DateTimeUnit.DAY, 1, afterCompletion)
            "week" -> unitRecurrence(DateTimeUnit.WEEK, 1, afterCompletion)
            "month" -> Recurrence.Monthly()
            "year" -> Recurrence.Yearly()
            "weekday" -> Recurrence.Weekly(1, WORKDAYS)
            "weekend" -> Recurrence.Weekly(1, setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
            else -> null
        }
        if (simple != null) return set(state, simple, i, 2)
        val unitAfter = state.text(i + 2)?.let { UNITS[it] }
        val count = if (next == "other") 2 else next.toIntOrNull()
        if (count != null && count > 0 && unitAfter != null) {
            return set(state, unitRecurrence(unitAfter, count, afterCompletion), i, 3)
        }
        ordinalDay(next)?.let { day ->
            val words = if (state.text(i + 2) == "day") 3 else 2
            return set(state, Recurrence.Monthly(1, day), i, words)
        }
        weekdayRun(state, i + 1)?.let { (days, words) -> set(state, Recurrence.Weekly(1, days), i, 1 + words) }
    }

    private fun set(state: State, recurrence: Recurrence, from: Int, count: Int) {
        state.recurrence = recurrence
        state.consume(Kind.RECURRENCE, from, count)
    }

    private fun unitRecurrence(unit: DateTimeUnit, n: Int, afterCompletion: Boolean): Recurrence = when (unit) {
        DateTimeUnit.DAY -> if (afterCompletion) Recurrence.AfterCompletion(n) else Recurrence.Daily(n)
        DateTimeUnit.WEEK -> if (afterCompletion) Recurrence.AfterCompletion(n * DAYS_PER_WEEK) else Recurrence.Weekly(n)
        DateTimeUnit.MONTH -> Recurrence.Monthly(n)
        else -> Recurrence.Yearly(n)
    }

    private fun ordinalDay(word: String): Int? {
        if (word == "last") return Recurrence.LAST_DAY
        val n = ORDINAL.find(word)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return n.takeIf { it in 1..MAX_DAY }
    }

    /** "mon, wed and fri" starting at [start]: the days and how many words they span. */
    private fun weekdayRun(state: State, start: Int): Pair<Set<DayOfWeek>, Int>? {
        val days = mutableSetOf<DayOfWeek>()
        var j = start
        var lastDay = start - 1
        while (true) {
            val w = state.text(j) ?: break
            val day = QuickAddParser.WEEKDAYS[w] ?: QuickAddParser.WEEKDAYS[w.removeSuffix("s")]
            when {
                day != null -> {
                    days += day
                    lastDay = j
                }
                w != "and" -> break
            }
            j++
        }
        return if (days.isEmpty()) null else days to (lastDay - start + 1)
    }

    fun firstOccurrence(recurrence: Recurrence, today: LocalDate): LocalDate = when (recurrence) {
        is Recurrence.Weekly ->
            recurrence.days.minOfOrNull { QuickAddParser.nextOnOrAfter(today, it) } ?: today
        is Recurrence.Monthly -> {
            val day = recurrence.dayOfMonth
            if (day == null) today else monthDay(today, day).let { if (it >= today) it else monthDay(firstOfNextMonth(today), day) }
        }
        else -> today
    }

    private fun firstOfNextMonth(d: LocalDate) = LocalDate(d.year, d.month.number, 1).plus(1, DateTimeUnit.MONTH)

    private fun monthDay(inMonth: LocalDate, day: Int): LocalDate {
        val first = LocalDate(inMonth.year, inMonth.month.number, 1)
        val last = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
        return LocalDate(first.year, first.month.number, if (day == Recurrence.LAST_DAY) last else day.coerceAtMost(last))
    }

    private const val DAYS_PER_WEEK = 7
    private const val MAX_DAY = 31
}

/** "tomorrow", "next friday", "in 3 days", "jan 5", "2026-03-05", "3/5", … */
internal object DateWords {
    private val MONTHS: Map<String, Month> = buildMap {
        Month.entries.forEach { m ->
            val full = m.name.lowercase()
            put(full, m)
            put(full.take(3), m)
        }
        put("sept", Month.SEPTEMBER)
    }
    private val DAY_NUM = Regex("^(\\d{1,2})(st|nd|rd|th)?$")
    private val ISO = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")
    private val SLASH = Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?$")
    private val YEAR = Regex("^\\d{4}$")
    private val UNIT = mapOf(
        "day" to DateTimeUnit.DAY, "days" to DateTimeUnit.DAY,
        "week" to DateTimeUnit.WEEK, "weeks" to DateTimeUnit.WEEK,
        "month" to DateTimeUnit.MONTH, "months" to DateTimeUnit.MONTH,
        "year" to DateTimeUnit.YEAR, "years" to DateTimeUnit.YEAR,
    )
    private val TONIGHT = LocalTime(20, 0)

    fun parse(state: State) {
        for (i in state.words.indices) {
            if (state.date != null) return
            val t = state.text(i) ?: continue
            val lead = if (t == "on" || t == "due") 1 else 0
            val (date, count) = match(state, i + lead, led = lead == 1) ?: continue
            state.date = date
            if (state.text(i + lead) == "tonight" && state.time == null) state.time = TONIGHT
            state.consume(Kind.DATE, i, lead + count)
        }
    }

    /** Weekday abbreviations ("sat", "sun") are ordinary words unless led by "on"/"due". */
    private fun match(state: State, i: Int, led: Boolean): Pair<LocalDate, Int>? {
        val t = state.text(i) ?: return null
        val today = state.today
        val weekdays = if (led) QuickAddParser.WEEKDAYS else QuickAddParser.FULL_WEEKDAYS
        return when (t) {
            "today", "tonight" -> today to 1
            "tomorrow", "tmr", "tmrw" -> today.plus(1, DateTimeUnit.DAY) to 1
            "weekend" -> QuickAddParser.nextOnOrAfter(today, DayOfWeek.SATURDAY) to 1
            "next" -> next(state, i)
            "this" -> this_(state, i)
            "in" -> relative(state, i)
            else -> weekdays[t]?.let { QuickAddParser.nextOnOrAfter(today, it) to 1 }
                ?: absolute(state, i)
        }
    }

    private fun next(state: State, i: Int): Pair<LocalDate, Int>? {
        val w = state.text(i + 1) ?: return null
        val today = state.today
        val nextMonday = today.plus(DAYS_PER_WEEK - today.dayOfWeek.isoDayNumber + 1, DateTimeUnit.DAY)
        val date = when (w) {
            "week" -> nextMonday
            "month" -> LocalDate(today.year, today.month.number, 1).plus(1, DateTimeUnit.MONTH)
            "year" -> LocalDate(today.year + 1, 1, 1)
            else -> QuickAddParser.WEEKDAYS[w]?.let { nextMonday.plus(it.isoDayNumber - 1, DateTimeUnit.DAY) }
        } ?: return null
        return date to 2
    }

    @Suppress("FunctionName")
    private fun this_(state: State, i: Int): Pair<LocalDate, Int>? {
        val w = state.text(i + 1) ?: return null
        val day = if (w == "weekend") DayOfWeek.SATURDAY else QuickAddParser.WEEKDAYS[w] ?: return null
        return QuickAddParser.nextOnOrAfter(state.today, day) to 2
    }

    private fun relative(state: State, i: Int): Pair<LocalDate, Int>? {
        val n = when (val w = state.text(i + 1)) {
            "a", "an", "one" -> 1
            else -> w?.toIntOrNull()
        } ?: return null
        val unit = state.text(i + 2)?.let { UNIT[it] } ?: return null
        return state.today.plus(n, unit) to 3
    }

    private fun absolute(state: State, i: Int): Pair<LocalDate, Int>? {
        val t = state.text(i) ?: return null
        ISO.find(t)?.destructured?.let { (y, m, d) -> return date(y.toInt(), m.toInt(), d.toInt())?.to(1) }
        SLASH.find(t)?.groupValues?.let { g ->
            val year = g[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < CENTURY) it + 2000 else it }
            return dated(state, g[1].toInt(), g[2].toInt(), year)?.to(1)
        }
        MONTHS[t]?.let { month ->
            val day = state.text(i + 1)?.let { DAY_NUM.find(it) }?.groupValues?.get(1)?.toInt() ?: return null
            val year = state.text(i + 2)?.takeIf { YEAR.matches(it) }?.toInt()
            return dated(state, month.number, day, year)?.to(if (year != null) 3 else 2)
        }
        val day = DAY_NUM.find(t)?.groupValues?.get(1)?.toInt() ?: return null
        val month = state.text(i + 1)?.let { MONTHS[it] } ?: return null
        val year = state.text(i + 2)?.takeIf { YEAR.matches(it) }?.toInt()
        return dated(state, month.number, day, year)?.to(if (year != null) 3 else 2)
    }

    /** Without a year, a date already past this year means next year. */
    private fun dated(state: State, month: Int, day: Int, year: Int?): LocalDate? {
        if (year != null) return date(year, month, day)
        val thisYear = date(state.today.year, month, day) ?: return null
        return if (thisYear < state.today) date(state.today.year + 1, month, day) else thisYear
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate(y, m, d) }.getOrNull()

    private const val DAYS_PER_WEEK = 7
    private const val CENTURY = 100
}

/** "at 5pm", "17:30", "noon", "9 am", … A bare number only counts after "at". */
internal object TimeWords {
    private val CLOCK = Regex("^(\\d{1,2})(?::(\\d{2}))?(am|pm|a|p)?$")
    private val NAMED = mapOf("noon" to LocalTime(12, 0), "midnight" to LocalTime(0, 0))
    private const val HALF_DAY = 12
    private const val LAST_PM_GUESS = 7

    fun parse(state: State) {
        for (i in state.words.indices) {
            if (state.time != null) return
            val t = state.text(i) ?: continue
            val atLead = if (t == "at" || t == "@") 1 else 0
            val (time, count) = match(state, i + atLead, allowBare = atLead == 1) ?: continue
            state.time = time
            state.consume(Kind.TIME, i, atLead + count)
        }
    }

    private fun match(state: State, i: Int, allowBare: Boolean): Pair<LocalTime, Int>? {
        val t = state.text(i) ?: return null
        NAMED[t]?.let { return it to 1 }
        val m = CLOCK.find(t) ?: return null
        val (h, min, suffix) = m.destructured
        val nextSuffix = state.text(i + 1)?.takeIf { suffix.isEmpty() && (it == "am" || it == "pm") }
        val meridiem = suffix.ifEmpty { nextSuffix.orEmpty() }
        if (meridiem.isEmpty() && min.isEmpty() && !allowBare) return null
        // "at 5" means the afternoon; nobody schedules a task for 5am by default.
        val assumed = if (meridiem.isEmpty() && min.isEmpty() && h.toInt() in 1..LAST_PM_GUESS) "p" else meridiem
        val time = toTime(h.toInt(), min.ifEmpty { "0" }.toInt(), assumed) ?: return null
        return time to (if (nextSuffix != null) 2 else 1)
    }

    private fun toTime(hour: Int, minute: Int, meridiem: String): LocalTime? {
        val h = when {
            meridiem.startsWith("p") -> if (hour == HALF_DAY) HALF_DAY else hour + HALF_DAY
            meridiem.startsWith("a") -> if (hour == HALF_DAY) 0 else hour
            else -> hour
        }
        if (meridiem.isNotEmpty() && hour !in 1..HALF_DAY) return null
        return runCatching { LocalTime(h, minute) }.getOrNull()
    }
}
