package com.vayunmathur.travel.pure

import com.vayunmathur.travel.domain.nextRange
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DateRangeSelectionTest {
    private val d1 = LocalDate.of(2026, 11, 30)
    private val d2 = LocalDate.of(2026, 12, 7)

    @Test
    fun firstTapStartsRange_secondTapEndsIt() {
        assertEquals(d1 to null, nextRange(null, null, d1, allowSameDay = true))
        assertEquals(d1 to d2, nextRange(d1, null, d2, allowSameDay = true))
    }

    @Test
    fun tapAfterCompleteRangeStartsOver() {
        assertEquals(d2 to null, nextRange(d1, d2, d2, allowSameDay = true))
    }

    @Test
    fun tapBeforeStartRestartsThere() {
        assertEquals(d1 to null, nextRange(d2, null, d1, allowSameDay = true))
    }

    @Test
    fun sameDayDependsOnAllowSameDay() {
        assertEquals(d1 to d1, nextRange(d1, null, d1, allowSameDay = true))
        assertEquals(d1 to null, nextRange(d1, null, d1, allowSameDay = false))
    }
}
