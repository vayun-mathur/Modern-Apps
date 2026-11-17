package com.vayunmathur.calendar.util
import com.vayunmathur.calendar.domain.RecurrenceParams
import com.vayunmathur.calendar.domain.RRule

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [RecurrenceParams.fromRRule] must map every [RRule] shape back to the dialog model:
 * the frequency word, the interval, the weekly days, the monthly kind, the end
 * condition, and the advanced BY lists plus WKST.
 */
class RecurrenceParamsTest {

    @Test
    fun `null has no params`() {
        assertNull(RecurrenceParams.fromRRule(null))
    }

    @Test
    fun `daily maps to days with its count`() {
        val params = RecurrenceParams.fromRRule(
            RRule.EveryXDays(3, RRule.EndCondition.Count(4)),
        )
        assertEquals("days", params?.freq)
        assertEquals(3, params?.interval)
        assertEquals(RRule.EndCondition.Count(4), params?.endCondition)
    }

    @Test
    fun `weekly keeps the selected days`() {
        val days = listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
        val params = RecurrenceParams.fromRRule(
            RRule.EveryXWeeks(2, days, RRule.EndCondition.Never),
        )
        assertEquals("weeks", params?.freq)
        assertEquals(2, params?.interval)
        assertEquals(days, params?.daysOfWeek)
    }

    @Test
    fun `monthly keeps the monthly kind`() {
        val params = RecurrenceParams.fromRRule(
            RRule.EveryXMonths(1, 2, RRule.EndCondition.Never),
        )
        assertEquals("months", params?.freq)
        assertEquals(2, params?.monthlyType)
    }

    @Test
    fun `yearly keeps its until`() {
        val until = RRule.EndCondition.Until(LocalDate(2026, 12, 31))
        val params = RecurrenceParams.fromRRule(RRule.EveryXYears(5, until))
        assertEquals("years", params?.freq)
        assertEquals(5, params?.interval)
        assertEquals(until, params?.endCondition)
    }

    @Test
    fun `advanced BY lists and WKST survive`() {
        val rule = RRule.EveryXDays(
            days = 1,
            endCondition = RRule.EndCondition.Never,
            byMonthDay = listOf(1, -1),
            byMonth = listOf(6),
            bySetPos = listOf(1),
            byYearDay = listOf(100),
            byWeekNo = listOf(26),
            wkst = DayOfWeek.SUNDAY,
        )
        val params = RecurrenceParams.fromRRule(rule)
        assertEquals(listOf(1, -1), params?.byMonthDay)
        assertEquals(listOf(6), params?.byMonth)
        assertEquals(listOf(1), params?.bySetPos)
        assertEquals(listOf(100), params?.byYearDay)
        assertEquals(listOf(26), params?.byWeekNo)
        assertEquals(DayOfWeek.SUNDAY, params?.wkst)
    }
}
