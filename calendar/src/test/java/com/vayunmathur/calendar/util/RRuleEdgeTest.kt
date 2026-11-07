package com.vayunmathur.calendar.util

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rejects and clamps in [RRule.parse] plus emission edges in [RRule.asString].
 *
 * The happy-path shapes live in RRuleTest; everything here is input the parser must
 * refuse (blank, missing FREQ, unknown frequency, malformed pairs, bad UNTIL) or
 * normalise (non-positive INTERVAL, out-of-range COUNT).
 */
class RRuleEdgeTest {

    private val zone = TimeZone.UTC

    @Test
    fun `blank input has no rule`() {
        assertNull(RRule.parse("", zone))
        assertNull(RRule.parse("   ", zone))
    }

    @Test
    fun `a rule without FREQ is refused`() {
        assertNull(RRule.parse("INTERVAL=2;COUNT=5", zone))
    }

    @Test
    fun `an unsupported frequency is refused`() {
        assertNull(RRule.parse("FREQ=HOURLY;INTERVAL=1", zone))
    }

    @Test
    fun `a malformed pair poisons the whole rule`() {
        assertNull(RRule.parse("FREQ=DAILY;INTERVAL", zone))
    }

    @Test
    fun `a bad UNTIL refuses the rule instead of guessing`() {
        assertNull(RRule.parse("FREQ=DAILY;UNTIL=not-a-date", zone))
    }

    @Test
    fun `a non-positive INTERVAL falls back to one`() {
        val zero = RRule.parse("FREQ=DAILY;INTERVAL=0", zone) as RRule.EveryXDays
        assertEquals(1, zero.days)
        val negative = RRule.parse("FREQ=WEEKLY;INTERVAL=-3;BYDAY=MO", zone)
        assertEquals(1, (negative as RRule.EveryXWeeks).weeks)
        val garbage = RRule.parse("FREQ=DAILY;INTERVAL=often", zone) as RRule.EveryXDays
        assertEquals(1, garbage.days)
    }

    @Test
    fun `COUNT is clamped into range`() {
        val low = RRule.parse("FREQ=DAILY;COUNT=0", zone) as RRule.EveryXDays
        assertEquals(RRule.EndCondition.Count(1), low.endCondition)
        val high = RRule.parse("FREQ=DAILY;COUNT=5000", zone) as RRule.EveryXDays
        assertEquals(RRule.EndCondition.Count(MAX_RRULE_COUNT), high.endCondition)
        val garbage = RRule.parse("FREQ=DAILY;COUNT=many", zone) as RRule.EveryXDays
        assertEquals(RRule.EndCondition.Count(1), garbage.endCondition)
    }

    @Test
    fun `COUNT wins when both end conditions are present`() {
        val rule = RRule.parse("FREQ=DAILY;COUNT=3;UNTIL=20260315", zone) as RRule.EveryXDays
        assertEquals(RRule.EndCondition.Count(3), rule.endCondition)
    }

    @Test
    fun `a floating UNTIL keeps the rule and its written day`() {
        // RFC 5545 permits a floating UNTIL with a floating DTSTART; exporters
        // emit e.g. UNTIL=20260315T235959 with no Z/offset. This used to fail
        // BasicIsoInstantFormat and poison the whole rule into a one-off event.
        val rule = RRule.parse("FREQ=DAILY;UNTIL=20260315T235959", zone) as RRule.EveryXDays
        assertEquals(RRule.EndCondition.Until(LocalDate(2026, 3, 15)), rule.endCondition)
        val zoned = RRule.parse(
            "FREQ=WEEKLY;UNTIL=20260315T120000", TimeZone.of("America/New_York"),
        ) as RRule.EveryXWeeks
        assertEquals(RRule.EndCondition.Until(LocalDate(2026, 3, 15)), zoned.endCondition)
    }

    @Test
    fun `a date-only UNTIL parses to that day`() {
        val rule = RRule.parse("FREQ=DAILY;UNTIL=20260315", zone) as RRule.EveryXDays
        assertEquals(RRule.EndCondition.Until(LocalDate(2026, 3, 15)), rule.endCondition)
    }

    @Test
    fun `the RRULE prefix form parses like the bare form`() {
        val rule = RRule.parse("RRULE:FREQ=DAILY;INTERVAL=2", zone) as RRule.EveryXDays
        assertEquals(2, rule.days)
    }

    @Test
    fun `a weekly rule with no days omits BYDAY`() {
        val text = RRule.EveryXWeeks(1, emptyList(), RRule.EndCondition.Never)
            .asString(LocalDate(2026, 7, 20), zone)
        assertEquals("FREQ=WEEKLY;INTERVAL=1", text)
    }

    @Test
    fun `a monthly rule with plain day names stays type zero`() {
        val rule = RRule.parse("FREQ=MONTHLY;BYDAY=MO,WE", zone)
        assertTrue(rule is RRule.EveryXMonths)
        assertEquals(0, rule.typeE)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), rule.byDay)
    }
}
