package com.vayunmathur.calendar

import com.vayunmathur.calendar.ui.dateFormat
import com.vayunmathur.calendar.ui.dialog.capitalcase
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
    abstract fun asString(firstDay: LocalDate, timeZone: TimeZone): String
    override fun toString(): String {
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
                parts.containsKey("UNTIL") -> {
                    val untilStr = parts["UNTIL"]!!
                    // Standard iCal UNTIL is YYYYMMDD or YYYYMMDDTHHMMSSZ
                    try {
                        val year = untilStr.take(4).toInt()
                        val month = untilStr.substring(4, 6).toInt()
                        val day = untilStr.substring(6, 8).toInt()
                        if('T' in untilStr) {
                            val hour = untilStr.substring(9, 11).toInt()
                            val minute = untilStr.substring(11, 13).toInt()
                            val second = untilStr.substring(13, 15).toInt()
                            EndCondition.Until(LocalDate(year, month, day).atTime(hour, minute, second).toInstant(TimeZone.UTC).toLocalDateTime(timeZone).date)
                        } else {
                            EndCondition.Until(LocalDate(year, month, day))
                        }
                    } catch (_: Exception) {
                        return null
                    }
                }
                else -> EndCondition.Never
            }

            // 3. Dispatch to specific classes based on FREQ
            return when (freq) {
                "DAILY" -> EveryXDays(interval, endCondition)

                "WEEKLY" -> {
                    val byDay = parts["BYDAY"]
                    val days = byDay?.split(",")?.mapNotNull { dayStr ->
                        // Expecting values like MO, TU, etc.
                        DayOfWeek.entries.find { it.name.startsWith(dayStr.take(2)) }
                    } ?: emptyList()
                    EveryXWeeks(interval, days, endCondition)
                }

                "MONTHLY" -> {
                    val byDay = parts["BYDAY"]
                    // type 1 if BYDAY contains a numeric prefix (e.g., 2TU or 1MO)
                    val type = if (byDay != null && byDay.any { it.isDigit() }) 1 else 0
                    EveryXMonths(interval, type, endCondition)
                }

                "YEARLY" -> EveryXYears(interval, endCondition)

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

    @Serializable
    data class EveryXYears(val years: Int, override val endCondition: EndCondition) : RRule() {
        override fun asString(firstDay: LocalDate, timeZone: TimeZone): String = "FREQ=YEARLY;INTERVAL=$years${endCondition.toRRuleSuffix(timeZone)}"
        override fun toStringImpl(): String = "Every $years years"
    }

    @Serializable
    data class EveryXMonths(val months: Int, val typeE: Int, override val endCondition: EndCondition) : RRule() {
        override fun asString(firstDay: LocalDate, timeZone: TimeZone): String {
            val base = "FREQ=MONTHLY;INTERVAL=$months"
            val suffix = endCondition.toRRuleSuffix(timeZone)
            return if (typeE == 1) {
                val dayOfWeek = firstDay.dayOfWeek.toIcal()
                val weekIndex = (firstDay.day - 1) / 7 + 1
                "$base;BYDAY=$weekIndex$dayOfWeek$suffix"
            } else "$base$suffix"
        }
        override fun toStringImpl(): String = "Every $months months"
    }

    @Serializable
    data class EveryXWeeks(val weeks: Int, val daysOfWeek: List<DayOfWeek>, override val endCondition: EndCondition) : RRule() {
        override fun asString(firstDay: LocalDate, timeZone: TimeZone): String {
            val days = daysOfWeek.sorted().joinToString(",") { it.toIcal() }
            return "FREQ=WEEKLY;INTERVAL=$weeks;BYDAY=$days${endCondition.toRRuleSuffix(timeZone)}"
        }
        override fun toStringImpl(): String = "Every $weeks weeks on ${
            daysOfWeek.joinToString(", ") { it.name.take(3).capitalcase() }
        }"
    }

    @Serializable
    data class EveryXDays(val days: Int, override val endCondition: EndCondition) : RRule() {
        override fun asString(firstDay: LocalDate, timeZone: TimeZone): String = "FREQ=DAILY;INTERVAL=$days${endCondition.toRRuleSuffix(timeZone)}"
        override fun toStringImpl(): String = "Every $days days"
    }
}