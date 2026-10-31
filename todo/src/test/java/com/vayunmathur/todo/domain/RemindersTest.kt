package com.vayunmathur.todo.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class RemindersTest {
    private val zone = TimeZone.of("Europe/Berlin")
    private fun at(d: Int, h: Int, m: Int = 0) = DueDates.at(LocalDate(2026, 3, d), LocalTime(h, m), zone)

    @Test
    fun `timed tasks remind relative to the due instant`() {
        val due = at(10, 15)
        assertEquals(due, Reminders.at(due, false, Reminders.Offset.AT_DUE, zone))
        assertEquals(at(10, 14, 50), Reminders.at(due, false, Reminders.Offset.MINUTES_10, zone))
        assertEquals(at(9, 15), Reminders.at(due, false, Reminders.Offset.DAY_1, zone))
    }

    @Test
    fun `all-day tasks remind at nine on the day, or the day before`() {
        val due = DueDates.startOfDay(LocalDate(2026, 3, 10), zone)
        assertEquals(at(10, 9), Reminders.at(due, true, Reminders.Offset.AT_DUE, zone))
        assertEquals(at(10, 8), Reminders.at(due, true, Reminders.Offset.HOUR_1, zone))
        assertEquals(at(9, 9), Reminders.at(due, true, Reminders.Offset.DAY_1, zone))
    }
}
