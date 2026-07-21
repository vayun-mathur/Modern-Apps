package com.vayunmathur.calendar.util
import com.vayunmathur.calendar.ui.dateFormat
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable


private fun DayOfWeek.toIcal(): String = this.name.take(2)

// RFC 5545 two-letter day codes (MO, TU, ...) mapped back to DayOfWeek.
private val dayOfWeekByIcal: Map<String, DayOfWeek> = DayOfWeek.entries.associateBy { it.toIcal() }

// Helper to format LocalDate to YYYYMMDD
private fun LocalDate.toIcalString(timeZone: TimeZone): String {
    val datetime = atTime(23, 59).toInstant(timeZone).toLocalDateTime(TimeZone.UTC)
    return datetime.format(LocalDateTime.Format {
        year()
        monthNumber()
        day()
        chars("T")
        hour()
        minute()
        second()
        chars("Z")
    })
}

private fun RRule.EndCondition.toRRuleSuffix(timeZone: TimeZone): String = when (this) {
    is RRule.EndCondition.Never -> ""
    is RRule.EndCondition.Count -> ";COUNT=$count"
    is RRule.EndCondition.Until -> ";UNTIL=${date.toIcalString(timeZone)}"
}

private fun RRule.EndCondition.toStringSuffix(): String = when (this) {
    is RRule.EndCondition.Never -> ""
    is RRule.EndCondition.Count -> ", $count times"
    is RRule.EndCondition.Until -> ", Until ${date.format(dateFormat)}"
}

@Serializable
sealed class RRule {
    abstract val endCondition: EndCondition
    abstract val byMonthDay: List<Int>?
    abstract val byMonth: List<Int>?
    abstract val bySetPos: List<Int>?
    abstract val byYearDay: List<Int>?
    abstract val byWeekNo: List<Int>?
    abstract val wkst: DayOfWeek?
    abstract val byDay: List<DayOfWeek>?
    abstract fun asString(firstDay: LocalDate, timeZone: TimeZone): String
    final override fun toString(): String {
        return toStringImpl() + endCondition.toStringSuffix()
    }
    protected abstract fun toStringImpl(): String

    companion object {
        fun parse(content: String, timeZone: TimeZone): RRule? {
            if (content.isBlank()) return null

            // 1. Clean the string and split into parts
            // Handles both "RRULE:FREQ=..." and just "FREQ=..."
            val cleanContent = content.removePrefix("RRULE:").trim()
            val parts = cleanContent.split(";").associate {
                val split = it.split("=")
                if (split.size != 2) return null // Malformed part
                split[0].uppercase() to split[1].uppercase()
            }

            // 2. Extract common fields
            val freq = parts["FREQ"] ?: return null
            val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1

            val endCondition = when {
                parts.containsKey("COUNT") ->
                    EndCondition.Count(parts["COUNT"]?.toLongOrNull() ?: 1L)
                parts.containsKey("UNTIL") ->
                    parseIcalUntil(parts["UNTIL"]!!, timeZone)?.let { EndCondition.Until(it) } ?: return null
                else -> EndCondition.Never
            }

            // Extract RFC 5545 properties
            val byMonthDay = parts["BYMONTHDAY"]?.split(",")?.mapNotNull { it.toIntOrNull() }
            val byMonth = parts["BYMONTH"]?.split(",")?.mapNotNull { it.toIntOrNull() }
            val bySetPos = parts["BYSETPOS"]?.split(",")?.mapNotNull { it.toIntOrNull() }
            val byYearDay = parts["BYYEARDAY"]?.split(",")?.mapNotNull { it.toIntOrNull() }
            val byWeekNo = parts["BYWEEKNO"]?.split(",")?.mapNotNull { it.toIntOrNull() }
            val wkst = parts["WKST"]?.let { dayOfWeekByIcal[it.take(2)] }

            // 3. Dispatch to specific classes based on FREQ
            return when (freq) {
                "DAILY" -> EveryXDays(interval, endCondition, byMonthDay, byMonth, bySetPos, byYearDay, byWeekNo, wkst)

                "WEEKLY" -> {
                    val byDay = parts["BYDAY"]
                    val days = byDay?.split(",")?.mapNotNull { dayOfWeekByIcal[it.take(2)] } ?: emptyList()
                    EveryXWeeks(interval, days, endCondition, byMonthDay, byMonth, bySetPos, byYearDay, byWeekNo, wkst)
                }

                "MONTHLY" -> {
                    val byDayRaw = parts["BYDAY"]
                    // type 2 = last weekday (negative prefix, e.g. -1MO), type 1 = nth weekday
                    // (positive prefix, e.g. 2TU), type 0 = by month day.
                    val type = when {
                        byDayRaw == null -> 0
                        byDayRaw.contains("-") -> 2
                        byDayRaw.any { it.isDigit() } -> 1
                        else -> 0
                    }
                    EveryXMonths(interval, type, endCondition, byMonthDay, byMonth, bySetPos, byYearDay, byWeekNo, wkst)
                }

                "YEARLY" -> {
                    val byDayDows = parts["BYDAY"]?.split(",")?.mapNotNull { dayOfWeekByIcal[it.takeLast(2)] }
                    EveryXYears(interval, endCondition, byMonthDay, byMonth, bySetPos, byYearDay, byWeekNo, wkst, byDayDows)
                }

                else -> null // Unsupported frequency (e.g., HOURLY)
            }
        }
    }

    @Serializable
    sealed interface EndCondition {
        @Serializable
        object Never : EndCondition
        @Serializable
        data class Count(val count: Long) : EndCondition
        @Serializable
        data class Until(val date: LocalDate) : EndCondition
    }

    protected fun buildRRuleString(
        base: String,
        timeZone: TimeZone
    ): String {
        val parts = mutableListOf(base)
        
        byMonthDay?.takeIf { it.isNotEmpty() }?.let {
            parts.add("BYMONTHDAY=${it.joinToString(",")}")
        }
        byMonth?.takeIf { it.isNotEmpty() }?.let {
            parts.add("BYMONTH=${it.joinToString(",")}")
        }
        bySetPos?.takeIf { it.isNotEmpty() }?.let {
            parts.add("BYSETPOS=${it.joinToString(",")}")
        }
        byYearDay?.takeIf { it.isNotEmpty() }?.let {
            parts.add("BYYEARDAY=${it.joinToString(",")}")
        }
        byWeekNo?.takeIf { it.isNotEmpty() }?.let {
            parts.add("BYWEEKNO=${it.joinToString(",")}")
        }
        byDay?.takeIf { it.isNotEmpty() }?.let {
            parts.add("BYDAY=${it.sorted().joinToString(",") { d -> d.toIcal() }}")
        }
        wkst?.let {
            parts.add("WKST=${it.toIcal()}")
        }
        parts.add(endCondition.toRRuleSuffix(timeZone).removePrefix(";"))
        
        return parts.filter { it.isNotEmpty() }.joinToString(";")
    }

    @Serializable
    data class EveryXYears(
        val years: Int,
        override val endCondition: EndCondition,
        override val byMonthDay: List<Int>? = null,
        override val byMonth: List<Int>? = null,
        override val bySetPos: List<Int>? = null,
        override val byYearDay: List<Int>? = null,
        override val byWeekNo: List<Int>? = null,
        override val wkst: DayOfWeek? = null,
        override val byDay: List<DayOfWeek>? = null
    ) : RRule() {
        override fun asString(firstDay: LocalDate, timeZone: TimeZone): String {
            val base = "FREQ=YEARLY;INTERVAL=$years"
            return buildRRuleString(base, timeZone)
        }
        override fun toStringImpl(): String = if (years == 1) "Yearly" else "Every $years years"
    }

    @Serializable
    data class EveryXMonths(
        val months: Int,
        val typeE: Int,
        override val endCondition: EndCondition,
        override val byMonthDay: List<Int>? = null,
        override val byMonth: List<Int>? = null,
        override val bySetPos: List<Int>? = null,
        override val byYearDay: List<Int>? = null,
        override val byWeekNo: List<Int>? = null,
        override val wkst: DayOfWeek? = null,
        override val byDay: List<DayOfWeek>? = null
    ) : RRule() {
        override fun asString(firstDay: LocalDate, timeZone: TimeZone): String {
            val base = "FREQ=MONTHLY;INTERVAL=$months"
            val byDayPart = when (typeE) {
                1 -> {
                    val dayOfWeek = firstDay.dayOfWeek.toIcal()
                    val weekIndex = (firstDay.day - 1) / 7 + 1
                    ";BYDAY=$weekIndex$dayOfWeek"
                }
                2 -> ";BYDAY=-1${firstDay.dayOfWeek.toIcal()}"
                else -> ""
            }
            return buildRRuleString(base + byDayPart, timeZone)
        }
        override fun toStringImpl(): String = if (months == 1) "Monthly" else "Every $months months"
    }

    @Serializable
    data class EveryXWeeks(
        val weeks: Int,
        val daysOfWeek: List<DayOfWeek>,
        override val endCondition: EndCondition,
        override val byMonthDay: List<Int>? = null,
        override val byMonth: List<Int>? = null,
        override val bySetPos: List<Int>? = null,
        override val byYearDay: List<Int>? = null,
        override val byWeekNo: List<Int>? = null,
        override val wkst: DayOfWeek? = null,
        override val byDay: List<DayOfWeek>? = null
    ) : RRule() {
        override fun asString(firstDay: LocalDate, timeZone: TimeZone): String {
            val days = daysOfWeek.sorted().joinToString(",") { it.toIcal() }
            val base = "FREQ=WEEKLY;INTERVAL=$weeks;BYDAY=$days"
            return buildRRuleString(base, timeZone)
        }
        override fun toStringImpl(): String {
            val prefix = if (weeks == 1) "Weekly" else "Every $weeks weeks"
            if (daysOfWeek.isEmpty()) return prefix
            val days = daysOfWeek.sorted().joinToString(", ") {
                it.name.take(3).lowercase().replaceFirstChar { c -> c.titlecase() }
            }
            return "$prefix on $days"
        }
    }

    @Serializable
    data class EveryXDays(
        val days: Int,
        override val endCondition: EndCondition,
        override val byMonthDay: List<Int>? = null,
        override val byMonth: List<Int>? = null,
        override val bySetPos: List<Int>? = null,
        override val byYearDay: List<Int>? = null,
        override val byWeekNo: List<Int>? = null,
        override val wkst: DayOfWeek? = null,
        override val byDay: List<DayOfWeek>? = null
    ) : RRule() {
        override fun asString(firstDay: LocalDate, timeZone: TimeZone): String {
            val base = "FREQ=DAILY;INTERVAL=$days"
            return buildRRuleString(base, timeZone)
        }
        override fun toStringImpl(): String = if (days == 1) "Daily" else "Every $days days"
    }
}