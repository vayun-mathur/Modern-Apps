package com.vayunmathur.calendar.domain
import com.vayunmathur.library.ui.DateString
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable


private fun DayOfWeek.toIcal(): String = this.name.take(2)

// RFC 5545 two-letter day codes (MO, TU, ...) mapped back to DayOfWeek.
private val dayOfWeekByIcal: Map<String, DayOfWeek> = DayOfWeek.entries.associateBy { it.toIcal() }

/** Upper bound for COUNT so a malicious/huge value can't blow up provider expansion. */
const val MAX_RRULE_COUNT = 1000L

// Helper to format LocalDate to an inclusive end-of-day UTC datetime. 23:59:59 local keeps the
// whole date inside the recurrence; the old 23:59 flat truncated anything after 23:59:00.
private fun LocalDate.toIcalString(timeZone: TimeZone): String {
    val datetime = atTime(23, 59, 59).toInstant(timeZone).toLocalDateTime(TimeZone.UTC)
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
    is RRule.EndCondition.Until -> ", Until ${DateString.dateWeekday(date)}"
}

@Serializable
/** An RFC 5545 recurrence rule parsed from an RRULE string; each subclass carries its frequency interval. */
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
    protected abstract fun toStringImpl(): String
    final override fun toString(): String {
        return toStringImpl() + endCondition.toStringSuffix()
    }

    /** Parsing of RRULE strings into recurrence rules. */
    companion object {
        fun parse(content: String, timeZone: TimeZone): RRule? {
            if (content.isBlank()) return null

            // Handles both "RRULE:FREQ=..." and just "FREQ=..."
            val parts = splitParts(content.removePrefix("RRULE:").trim()) ?: return null
            val freq = parts["FREQ"] ?: return null
            val interval = (parts["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val endCondition = parseEndCondition(parts, timeZone) ?: return null
            val common = parseCommonFields(parts)

            return when (freq) {
                "DAILY" -> EveryXDays(interval, endCondition, common.byMonthDay, common.byMonth,
                    common.bySetPos, common.byYearDay, common.byWeekNo, common.wkst)
                "WEEKLY" -> parseWeekly(parts, interval, endCondition, common)
                "MONTHLY" -> parseMonthly(parts, interval, endCondition, common)
                "YEARLY" -> parseYearly(parts, interval, endCondition, common)
                else -> null // Unsupported frequency (e.g., HOURLY)
            }
        }

        /** Splits "K=V;K=V..." into a map, or null when a part is malformed. */
        private fun splitParts(cleanContent: String): Map<String, String>? {
            val parts = mutableMapOf<String, String>()
            for (part in cleanContent.split(";")) {
                val split = part.split("=")
                if (split.size != 2) return null // Malformed part
                parts[split[0].uppercase()] = split[1].uppercase()
            }
            return parts
        }

        /** COUNT/UNTIL end condition; null only when UNTIL is unparseable. */
        private fun parseEndCondition(parts: Map<String, String>, timeZone: TimeZone): EndCondition? {
            if (parts.containsKey("COUNT")) {
                val count = (parts["COUNT"]?.toLongOrNull() ?: 1L).coerceIn(1L, MAX_RRULE_COUNT)
                return EndCondition.Count(count)
            }
            if (parts.containsKey("UNTIL")) {
                return parseIcalUntil(parts["UNTIL"]!!, timeZone)?.let { EndCondition.Until(it) }
            }
            return EndCondition.Never
        }

        /** RFC 5545 properties shared by every frequency. */
        private fun parseCommonFields(parts: Map<String, String>): CommonFields {
            return CommonFields(
                byMonthDay = parts["BYMONTHDAY"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                byMonth = parts["BYMONTH"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                bySetPos = parts["BYSETPOS"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                byYearDay = parts["BYYEARDAY"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                byWeekNo = parts["BYWEEKNO"]?.split(",")?.mapNotNull { it.toIntOrNull() },
                wkst = parts["WKST"]?.let { dayOfWeekByIcal[it.take(2)] },
            )
        }

        private fun parseWeekly(
            parts: Map<String, String>,
            interval: Int,
            endCondition: EndCondition,
            common: CommonFields,
        ): EveryXWeeks {
            val byDay = parts["BYDAY"]
            val days = byDay?.split(",")?.mapNotNull { dayOfWeekByIcal[it.take(2)] } ?: emptyList()
            return EveryXWeeks(interval, days, endCondition, common.byMonthDay, common.byMonth,
                common.bySetPos, common.byYearDay, common.byWeekNo, common.wkst)
        }

        private fun parseMonthly(
            parts: Map<String, String>,
            interval: Int,
            endCondition: EndCondition,
            common: CommonFields,
        ): EveryXMonths {
            val byDayRaw = parts["BYDAY"]
            // type 2 = last weekday (negative prefix, e.g. -1MO), type 1 = nth weekday
            // (positive prefix, e.g. 2TU), type 0 = by month day.
            val type = when {
                byDayRaw == null -> 0
                byDayRaw.contains("-") -> 2
                byDayRaw.any { it.isDigit() } -> 1
                else -> 0
            }
            // Preserve BYDAY days so type-0 round-trips; type 1/2 keep
            // type-derived emission in asString (generic byDay suppressed there).
            val parsedDays = byDayRaw?.split(",")?.mapNotNull { dayOfWeekByIcal[it.takeLast(2)] }
            // Preserve the ordinal BYDAY list verbatim (e.g. 2TU,4TU or
            // 1MO,-1FR): deriving a single occurrence from the start date
            // in asString would silently drop every other occurrence.
            val byDayTokens = byDayRaw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            val ordinalByDay = byDayTokens?.takeIf { tokens ->
                tokens.any { it.dropLast(2).toIntOrNull() != null }
            }
            return EveryXMonths(interval, type, endCondition, common.byMonthDay, common.byMonth,
                common.bySetPos, common.byYearDay, common.byWeekNo, common.wkst, parsedDays,
                ordinalByDay)
        }

        private fun parseYearly(
            parts: Map<String, String>,
            interval: Int,
            endCondition: EndCondition,
            common: CommonFields,
        ): EveryXYears {
            val byDayTokens = parts["BYDAY"]?.split(",")
            val byDayDows = byDayTokens?.mapNotNull { dayOfWeekByIcal[it.takeLast(2)] }
            // Preserve numeric prefixes (e.g. 20MO, -1FR) for round-trip;
            // plain day codes round-trip via byDay as before.
            val byDayRaw = byDayTokens?.takeIf { tokens ->
                tokens.any { it.any(Char::isDigit) || it.startsWith("-") }
            }
            return EveryXYears(interval, endCondition, common.byMonthDay, common.byMonth,
                common.bySetPos, common.byYearDay, common.byWeekNo, common.wkst, byDayDows, byDayRaw)
        }

        private data class CommonFields(
            val byMonthDay: List<Int>?,
            val byMonth: List<Int>?,
            val bySetPos: List<Int>?,
            val byYearDay: List<Int>?,
            val byWeekNo: List<Int>?,
            val wkst: DayOfWeek?,
        )
    }

    @Serializable
    /** When a recurrence stops: never, after a number of occurrences, or on a date. */
    sealed interface EndCondition {
        @Serializable
        /** The recurrence repeats without an end. */
        object Never : EndCondition
        @Serializable
        /** The recurrence stops after the given number of occurrences. */
        data class Count(val count: Long) : EndCondition
        @Serializable
        /** The recurrence stops after the given date. */
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
    /** A yearly recurrence repeating at the given year interval. */
    data class EveryXYears(
        val years: Int,
        override val endCondition: EndCondition,
        override val byMonthDay: List<Int>? = null,
        override val byMonth: List<Int>? = null,
        override val bySetPos: List<Int>? = null,
        override val byYearDay: List<Int>? = null,
        override val byWeekNo: List<Int>? = null,
        override val wkst: DayOfWeek? = null,
        override val byDay: List<DayOfWeek>? = null,
        val byDayRaw: List<String>? = null
    ) : RRule() {
        override fun asString(firstDay: LocalDate, timeZone: TimeZone): String {
            val base = "FREQ=YEARLY;INTERVAL=$years"
            // Prefer raw BYDAY (with numeric prefixes) when present; suppress the
            // generic byDay emission to avoid a duplicated BYDAY key.
            if (!byDayRaw.isNullOrEmpty()) {
                val withRaw = "$base;BYDAY=${byDayRaw.joinToString(",")}"
                return copy(byDay = null).buildRRuleString(withRaw, timeZone)
            }
            return buildRRuleString(base, timeZone)
        }
        override fun toStringImpl(): String = if (years == 1) "Yearly" else "Every $years years"
    }

    @Serializable
    /** A monthly recurrence at the given month interval, positioned by month day or weekday within the month. */
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
        override val byDay: List<DayOfWeek>? = null,
        /** Ordinal BYDAY tokens exactly as parsed (e.g. ["2TU", "4TU"]); null for rules built in-app. */
        val byDayRaw: List<String>? = null
    ) : RRule() {
        override fun asString(firstDay: LocalDate, timeZone: TimeZone): String {
            val base = "FREQ=MONTHLY;INTERVAL=$months"
            // A parsed ordinal BYDAY list round-trips verbatim: it may name
            // several occurrences (or a mix like 1MO,-1FR) that no single
            // start-date-derived token could represent.
            if (!byDayRaw.isNullOrEmpty()) {
                val withRaw = "$base;BYDAY=${byDayRaw.joinToString(",")}"
                return copy(byDay = null).buildRRuleString(withRaw, timeZone)
            }
            val byDayPart = when (typeE) {
                1 -> {
                    val dayOfWeek = firstDay.dayOfWeek.toIcal()
                    val weekIndex = (firstDay.day - 1) / 7 + 1
                    ";BYDAY=$weekIndex$dayOfWeek"
                }
                2 -> ";BYDAY=-1${firstDay.dayOfWeek.toIcal()}"
                else -> ""
            }
            // typeE 1/2 already carry BYDAY in byDayPart; drop generic byDay so the
            // key isn't emitted twice. typeE 0 passes parsed BYDAY through.
            if (typeE != 0) return copy(byDay = null).buildRRuleString(base + byDayPart, timeZone)
            return buildRRuleString(base + byDayPart, timeZone)
        }
        override fun toStringImpl(): String = if (months == 1) "Monthly" else "Every $months months"
    }

    @Serializable
    /** A weekly recurrence repeating at the given week interval on the given days of the week. */
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
            // Empty selection omits BYDAY entirely; never emit `BYDAY=` with no value.
            val base = if (daysOfWeek.isEmpty()) "FREQ=WEEKLY;INTERVAL=$weeks"
                else "FREQ=WEEKLY;INTERVAL=$weeks;BYDAY=${daysOfWeek.sorted().joinToString(",") { it.toIcal() }}"
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
    /** A daily recurrence repeating at the given day interval. */
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
