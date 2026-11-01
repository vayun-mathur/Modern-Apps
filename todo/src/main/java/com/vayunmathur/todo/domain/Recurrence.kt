package com.vayunmathur.todo.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus

/**
 * How a task repeats. Stored as an RRULE-style string ([serialize]/[parse]) so the calendar
 * mirror can hand the fixed schedules to CalendarContract unchanged; the after-completion
 * variant is carried with a private `X-FROM=COMPLETION` part.
 */
sealed interface Recurrence {
    val interval: Int

    data class Daily(override val interval: Int = 1) : Recurrence

    /** [days] empty means "the due date's weekday". */
    data class Weekly(override val interval: Int = 1, val days: Set<DayOfWeek> = emptySet()) : Recurrence

    /** [dayOfMonth] null means "the due date's day", [LAST_DAY] the last day of the month. */
    data class Monthly(override val interval: Int = 1, val dayOfMonth: Int? = null) : Recurrence

    data class Yearly(override val interval: Int = 1) : Recurrence

    /** Due [interval] days after the task is completed, whenever that is. */
    data class AfterCompletion(override val interval: Int = 1) : Recurrence

    /**
     * First due date after this occurrence: strictly after both [due] and [today], so
     * completing an overdue task never lands it in the past or back on today.
     */
    fun nextDate(due: LocalDate, today: LocalDate): LocalDate {
        val base = if (due > today) due else today
        return when (this) {
            is Daily -> due.plus(stepsPast(due.daysUntil(base), interval) * interval, DateTimeUnit.DAY)
            is Weekly -> nextWeekly(due, base)
            is Monthly -> nextMonthly(due, base)
            is Yearly -> generateSequence(1) { it + 1 }
                .map { due.plus(it * interval, DateTimeUnit.YEAR) }
                .first { it > base }
            is AfterCompletion -> today.plus(interval, DateTimeUnit.DAY)
        }
    }

    /** The first day on or after [today] this schedule lands on; seeds a repeating task's due date. */
    fun firstOnOrAfter(today: LocalDate): LocalDate {
        val matches = when (this) {
            is Weekly -> days.isEmpty() || today.dayOfWeek in days
            is Monthly -> when (dayOfMonth) {
                null -> true
                LAST_DAY -> today.day == lengthOfMonth(LocalDate(today.year, today.month.number, 1))
                else -> today.day == dayOfMonth
            }
            else -> true
        }
        return if (matches) today else nextDate(today, today)
    }

    fun serialize(): String {
        val parts = mutableListOf<String>()
        parts += "FREQ=" + when (this) {
            is Daily, is AfterCompletion -> "DAILY"
            is Weekly -> "WEEKLY"
            is Monthly -> "MONTHLY"
            is Yearly -> "YEARLY"
        }
        if (interval != 1) parts += "INTERVAL=$interval"
        if (this is Weekly && days.isNotEmpty()) {
            parts += "BYDAY=" + days.sortedBy { it.isoDayNumber }.joinToString(",") { DAY_CODES.getValue(it) }
        }
        if (this is Monthly && dayOfMonth != null) parts += "BYMONTHDAY=$dayOfMonth"
        if (this is AfterCompletion) parts += "$X_FROM=COMPLETION"
        return parts.joinToString(";")
    }

    /** The plain RRULE for calendars, or null when the schedule depends on completion. */
    fun toRRule(): String? = if (this is AfterCompletion) null else serialize()

    companion object {
        const val LAST_DAY = -1
        private const val X_FROM = "X-FROM"
        private const val MAX_INTERVAL = 999
        private const val MAX_DAY = 31

        private val DAY_CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA",
            DayOfWeek.SUNDAY to "SU",
        )
        private val CODE_DAYS = DAY_CODES.entries.associate { (k, v) -> v to k }

        fun parse(raw: String?): Recurrence? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.removePrefix("RRULE:").split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).uppercase() to part.substring(i + 1)
            }.toMap()
            val interval = (parts["INTERVAL"]?.toIntOrNull() ?: 1).coerceIn(1, MAX_INTERVAL)
            return when (parts["FREQ"]?.uppercase()) {
                "DAILY" -> if (parts[X_FROM].equals("COMPLETION", true)) AfterCompletion(interval) else Daily(interval)
                "WEEKLY" -> Weekly(interval, parseDays(parts["BYDAY"]))
                "MONTHLY" -> Monthly(interval, parseMonthDay(parts["BYMONTHDAY"]))
                "YEARLY" -> Yearly(interval)
                else -> null
            }
        }

        private fun parseMonthDay(raw: String?): Int? =
            raw?.toIntOrNull()?.takeIf { it == LAST_DAY || it in 1..MAX_DAY }

        private fun parseDays(raw: String?): Set<DayOfWeek> =
            raw.orEmpty().split(',').mapNotNull { CODE_DAYS[it.trim().takeLast(2).uppercase()] }.toSet()
    }
}

/** Smallest k ≥ 1 with k·interval > elapsed. */
private fun stepsPast(elapsed: Int, interval: Int): Int = elapsed / interval + 1

private fun Recurrence.Weekly.nextWeekly(due: LocalDate, base: LocalDate): LocalDate {
    val wanted = days.ifEmpty { setOf(due.dayOfWeek) }
    val anchorWeek = due.minus(due.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    var candidate = base.plus(1, DateTimeUnit.DAY)
    while (true) {
        val weekStart = candidate.minus(candidate.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        val weeks = anchorWeek.daysUntil(weekStart) / DAYS_PER_WEEK
        if (candidate.dayOfWeek in wanted && weeks % interval == 0) return candidate
        candidate = candidate.plus(1, DateTimeUnit.DAY)
    }
}

private fun Recurrence.Monthly.nextMonthly(due: LocalDate, base: LocalDate): LocalDate {
    val wantedDay = dayOfMonth ?: due.day
    var k = 0
    while (true) {
        val month = LocalDate(due.year, due.month.number, 1).plus(k * interval, DateTimeUnit.MONTH)
        val last = lengthOfMonth(month)
        val day = if (wantedDay == Recurrence.LAST_DAY) last else wantedDay.coerceAtMost(last)
        val candidate = LocalDate(month.year, month.month.number, day)
        if (candidate > base) return candidate
        k++
    }
}

private fun lengthOfMonth(firstOfMonth: LocalDate): Int =
    firstOfMonth.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day

private const val DAYS_PER_WEEK = 7
