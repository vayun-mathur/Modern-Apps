package com.vayunmathur.todo.notifications

import com.vayunmathur.todo.domain.DueDates
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class DigestSchedulerTest {
    private val zone = TimeZone.of("America/New_York")
    private fun at(d: Int, h: Int, m: Int = 0) = DueDates.at(LocalDate(2026, 3, d), LocalTime(h, m), zone)

    @Test
    fun `fires later today when the time has not passed`() {
        assertEquals(at(4, 8), DigestScheduler.nextTrigger(8 * 60, now = at(4, 7, 30), zone = zone))
    }

    @Test
    fun `rolls to tomorrow once the time has passed`() {
        assertEquals(at(5, 8), DigestScheduler.nextTrigger(8 * 60, now = at(4, 8), zone = zone))
    }

    @Test
    fun `keeps wall-clock time across the DST change`() {
        // DST starts 2026-03-08 in New York.
        assertEquals(at(8, 8, 15), DigestScheduler.nextTrigger(8 * 60 + 15, now = at(7, 9), zone = zone))
    }
}
