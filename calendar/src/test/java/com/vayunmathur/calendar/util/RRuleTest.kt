package com.vayunmathur.calendar.util

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.*
import org.junit.Test

class RRuleTest {

    private val timeZone = TimeZone.of("America/Los_Angeles")

    @Test
    fun readableStringUsesReadableFormNotDataClass() {
        val yearly = RRule.parse("FREQ=YEARLY;INTERVAL=1", timeZone)!!
        assertEquals("Yearly", yearly.toString())

        val everyThreeYears = RRule.parse("FREQ=YEARLY;INTERVAL=3", timeZone)!!
        assertEquals("Every 3 years", everyThreeYears.toString())

        val monthly = RRule.parse("FREQ=MONTHLY;INTERVAL=1", timeZone)!!
        assertEquals("Monthly", monthly.toString())

        val daily = RRule.parse("FREQ=DAILY;INTERVAL=1", timeZone)!!
        assertEquals("Daily", daily.toString())

        val weekly = RRule.parse("FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,WE", timeZone)!!
        assertEquals("Weekly on Mon, Wed", weekly.toString())
    }

    @Test
    fun readableStringIncludesEndCondition() {
        val counted = RRule.parse("FREQ=DAILY;INTERVAL=2;COUNT=5", timeZone)!!
        assertEquals("Every 2 days, 5 times", counted.toString())
    }

    @Test
    fun testParseDailyWithInterval() {
        val rrule = RRule.parse("FREQ=DAILY;INTERVAL=2", timeZone)
        assertNotNull(rrule)
        assertTrue(rrule is RRule.EveryXDays)
        val daily = rrule as RRule.EveryXDays
        assertEquals(2, daily.days)
        assertTrue(daily.endCondition is RRule.EndCondition.Never)
    }

    @Test
    fun testParseWeeklyWithByDay() {
        val rrule = RRule.parse("FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,WE,FR", timeZone)
        assertNotNull(rrule)
        assertTrue(rrule is RRule.EveryXWeeks)
        val weekly = rrule as RRule.EveryXWeeks
        assertEquals(1, weekly.weeks)
        assertEquals(3, weekly.daysOfWeek.size)
        assertTrue(weekly.daysOfWeek.contains(DayOfWeek.MONDAY))
        assertTrue(weekly.daysOfWeek.contains(DayOfWeek.WEDNESDAY))
        assertTrue(weekly.daysOfWeek.contains(DayOfWeek.FRIDAY))
    }

    @Test
    fun testParseMonthlyWithByMonthDay() {
        val rrule = RRule.parse("FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=1,15,-1", timeZone)
        assertNotNull(rrule)
        assertTrue(rrule is RRule.EveryXMonths)
        val monthly = rrule as RRule.EveryXMonths
        assertNotNull(monthly.byMonthDay)
        assertEquals(3, monthly.byMonthDay!!.size)
        assertTrue(monthly.byMonthDay!!.contains(1))
        assertTrue(monthly.byMonthDay!!.contains(15))
        assertTrue(monthly.byMonthDay!!.contains(-1))
    }

    @Test
    fun testParseYearlyWithByMonth() {
        val rrule = RRule.parse("FREQ=YEARLY;INTERVAL=1;BYMONTH=1,6,12", timeZone)
        assertNotNull(rrule)
        assertTrue(rrule is RRule.EveryXYears)
        val yearly = rrule as RRule.EveryXYears
        assertNotNull(yearly.byMonth)
        assertEquals(3, yearly.byMonth!!.size)
        assertTrue(yearly.byMonth!!.contains(1))
        assertTrue(yearly.byMonth!!.contains(6))
        assertTrue(yearly.byMonth!!.contains(12))
    }

    @Test
    fun testParseWithBySetPos() {
        val rrule = RRule.parse("FREQ=MONTHLY;INTERVAL=1;BYSETPOS=1,-1", timeZone)
        assertNotNull(rrule)
        assertTrue(rrule is RRule.EveryXMonths)
        val monthly = rrule as RRule.EveryXMonths
        assertNotNull(monthly.bySetPos)
        assertEquals(2, monthly.bySetPos!!.size)
        assertTrue(monthly.bySetPos!!.contains(1))
        assertTrue(monthly.bySetPos!!.contains(-1))
    }

    @Test
    fun testParseWithByYearDay() {
        val rrule = RRule.parse("FREQ=YEARLY;INTERVAL=1;BYYEARDAY=1,100,365", timeZone)
        assertNotNull(rrule)
        assertTrue(rrule is RRule.EveryXYears)
        val yearly = rrule as RRule.EveryXYears
        assertNotNull(yearly.byYearDay)
        assertEquals(3, yearly.byYearDay!!.size)
        assertTrue(yearly.byYearDay!!.contains(1))
        assertTrue(yearly.byYearDay!!.contains(100))
        assertTrue(yearly.byYearDay!!.contains(365))
    }

    @Test
    fun testParseWithByWeekNo() {
        val rrule = RRule.parse("FREQ=YEARLY;INTERVAL=1;BYWEEKNO=1,26,52", timeZone)
        assertNotNull(rrule)
        assertTrue(rrule is RRule.EveryXYears)
        val yearly = rrule as RRule.EveryXYears
        assertNotNull(yearly.byWeekNo)
        assertEquals(3, yearly.byWeekNo!!.size)
        assertTrue(yearly.byWeekNo!!.contains(1))
        assertTrue(yearly.byWeekNo!!.contains(26))
        assertTrue(yearly.byWeekNo!!.contains(52))
    }

    @Test
    fun testParseWithWkst() {
        val rrule = RRule.parse("FREQ=WEEKLY;INTERVAL=1;WKST=SU", timeZone)
        assertNotNull(rrule)
        assertTrue(rrule is RRule.EveryXWeeks)
        val weekly = rrule as RRule.EveryXWeeks
        assertNotNull(weekly.wkst)
        assertEquals(DayOfWeek.SUNDAY, weekly.wkst)
    }

    @Test
    fun testParseWithCount() {
        val rrule = RRule.parse("FREQ=DAILY;INTERVAL=1;COUNT=10", timeZone)
        assertNotNull(rrule)
        assertTrue(rrule is RRule.EveryXDays)
        val daily = rrule as RRule.EveryXDays
        assertTrue(daily.endCondition is RRule.EndCondition.Count)
        val count = daily.endCondition as RRule.EndCondition.Count
        assertEquals(10L, count.count)
    }

    @Test
    fun testParseWithUntil() {
        val rrule = RRule.parse("FREQ=DAILY;INTERVAL=1;UNTIL=20251231T235959Z", timeZone)
        assertNotNull(rrule)
        assertTrue(rrule is RRule.EveryXDays)
        val daily = rrule as RRule.EveryXDays
        assertTrue(daily.endCondition is RRule.EndCondition.Until)
        val until = daily.endCondition as RRule.EndCondition.Until
        assertEquals(LocalDate(2025, 12, 31), until.date)
    }

    @Test
    fun testAsStringDailyWithByMonthDay() {
        val rrule = RRule.EveryXDays(
            days = 1,
            endCondition = RRule.EndCondition.Never,
            byMonthDay = listOf(1, 15, -1)
        )
        val firstDay = LocalDate(2025, 1, 1)
        val result = rrule.asString(firstDay, timeZone)
        assertTrue(result.contains("FREQ=DAILY"))
        assertTrue(result.contains("BYMONTHDAY=1,15,-1"))
    }

    @Test
    fun testAsStringWeeklyWithWkst() {
        val rrule = RRule.EveryXWeeks(
            weeks = 1,
            daysOfWeek = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            endCondition = RRule.EndCondition.Never,
            wkst = DayOfWeek.SUNDAY
        )
        val firstDay = LocalDate(2025, 1, 1)
        val result = rrule.asString(firstDay, timeZone)
        assertTrue(result.contains("FREQ=WEEKLY"))
        assertTrue(result.contains("BYDAY=MO,WE"))
        assertTrue(result.contains("WKST=SU"))
    }

    @Test
    fun testAsStringMonthlyWithMultipleProperties() {
        val rrule = RRule.EveryXMonths(
            months = 2,
            typeE = 0,
            endCondition = RRule.EndCondition.Count(5),
            byMonthDay = listOf(1, 15),
            bySetPos = listOf(1, -1),
            byMonth = listOf(1, 6, 12)
        )
        val firstDay = LocalDate(2025, 1, 1)
        val result = rrule.asString(firstDay, timeZone)
        assertTrue(result.contains("FREQ=MONTHLY"))
        assertTrue(result.contains("INTERVAL=2"))
        assertTrue(result.contains("BYMONTHDAY=1,15"))
        assertTrue(result.contains("BYSETPOS=1,-1"))
        assertTrue(result.contains("BYMONTH=1,6,12"))
        assertTrue(result.contains("COUNT=5"))
    }

    @Test
    fun testAsStringYearlyWithAllProperties() {
        val rrule = RRule.EveryXYears(
            years = 1,
            endCondition = RRule.EndCondition.Never,
            byMonthDay = listOf(1),
            byMonth = listOf(1, 7),
            bySetPos = listOf(1),
            byYearDay = listOf(1, 365),
            byWeekNo = listOf(1, 52),
            wkst = DayOfWeek.MONDAY
        )
        val firstDay = LocalDate(2025, 1, 1)
        val result = rrule.asString(firstDay, timeZone)
        assertTrue(result.contains("FREQ=YEARLY"))
        assertTrue(result.contains("BYMONTHDAY=1"))
        assertTrue(result.contains("BYMONTH=1,7"))
        assertTrue(result.contains("BYSETPOS=1"))
        assertTrue(result.contains("BYYEARDAY=1,365"))
        assertTrue(result.contains("BYWEEKNO=1,52"))
        assertTrue(result.contains("WKST=MO"))
    }

    @Test
    fun testParseComplexRRule() {
        val rruleStr = "FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=1,15;BYSETPOS=1,-1;COUNT=10"
        val rrule = RRule.parse(rruleStr, timeZone)
        assertNotNull(rrule)
        assertTrue(rrule is RRule.EveryXMonths)
        val monthly = rrule as RRule.EveryXMonths
        assertEquals(2, monthly.months)
        assertNotNull(monthly.byMonthDay)
        assertEquals(2, monthly.byMonthDay!!.size)
        assertNotNull(monthly.bySetPos)
        assertEquals(2, monthly.bySetPos!!.size)
        assertTrue(monthly.endCondition is RRule.EndCondition.Count)
    }

    @Test
    fun testParseMonthlyNthWeekday() {
        val rrule = RRule.parse("FREQ=MONTHLY;INTERVAL=1;BYDAY=2TU", timeZone)
        assertTrue(rrule is RRule.EveryXMonths)
        assertEquals(1, (rrule as RRule.EveryXMonths).typeE)
    }

    @Test
    fun testParseMonthlyLastWeekday() {
        val rrule = RRule.parse("FREQ=MONTHLY;INTERVAL=1;BYDAY=-1MO", timeZone)
        assertTrue(rrule is RRule.EveryXMonths)
        assertEquals(2, (rrule as RRule.EveryXMonths).typeE)
    }

    @Test
    fun testAsStringMonthlyLastWeekdayDerivesFromStartDate() {
        // 2025-01-06 is a Monday.
        val result = RRule.EveryXMonths(1, 2, RRule.EndCondition.Never)
            .asString(LocalDate(2025, 1, 6), timeZone)
        assertTrue(result.contains("FREQ=MONTHLY"))
        assertTrue(result.contains("BYDAY=-1MO"))
    }

    @Test
    fun testYearlyWeekOfYearRoundTrip() {
        val original = RRule.EveryXYears(
            years = 1,
            endCondition = RRule.EndCondition.Never,
            byWeekNo = listOf(30),
            byDay = listOf(DayOfWeek.MONDAY)
        )
        val result = original.asString(LocalDate(2026, 7, 20), timeZone)
        assertTrue(result.contains("BYWEEKNO=30"))
        assertTrue(result.contains("BYDAY=MO"))

        val parsed = RRule.parse(result, timeZone)
        assertTrue(parsed is RRule.EveryXYears)
        val yearly = parsed as RRule.EveryXYears
        assertEquals(listOf(30), yearly.byWeekNo)
        assertEquals(listOf(DayOfWeek.MONDAY), yearly.byDay)
    }

    @Test
    fun testRoundTripParsing() {
        val original = RRule.EveryXWeeks(
            weeks = 2,
            daysOfWeek = listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY),
            endCondition = RRule.EndCondition.Until(LocalDate(2025, 12, 31)),
            byMonthDay = listOf(1, 15),
            wkst = DayOfWeek.MONDAY
        )
        val firstDay = LocalDate(2025, 1, 1)
        val rruleStr = original.asString(firstDay, timeZone)
        val parsed = RRule.parse(rruleStr, timeZone)
        assertNotNull(parsed)
        assertTrue(parsed is RRule.EveryXWeeks)
        val weekly = parsed as RRule.EveryXWeeks
        assertEquals(2, weekly.weeks)
        assertEquals(2, weekly.daysOfWeek.size)
        assertNotNull(weekly.byMonthDay)
        assertEquals(2, weekly.byMonthDay!!.size)
        assertNotNull(weekly.wkst)
        assertEquals(DayOfWeek.MONDAY, weekly.wkst)
    }
}
