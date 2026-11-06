package com.vayunmathur.library.ui.dialog

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Slice 2/3 (library/ui): [DateSelection] value semantics — the clearable
 * date dialog's result wrapper compares by its date.
 */
class Slice2DateSelectionTest {

    @Test
    fun `empty and dated selections differ`() {
        assertEquals(DateSelection(null), DateSelection(null))
        assertNotEquals(DateSelection(null), DateSelection(LocalDate(2026, 11, 6)))
        assertEquals(DateSelection(LocalDate(2026, 11, 6)), DateSelection(LocalDate(2026, 11, 6)))
    }

    @Test
    fun `the date is readable and copies`() {
        val selection = DateSelection(LocalDate(2026, 11, 6))
        assertEquals(LocalDate(2026, 11, 6), selection.date)
        assertEquals(DateSelection(null), selection.copy(date = null))
        assertEquals(selection.hashCode(), DateSelection(LocalDate(2026, 11, 6)).hashCode())
    }
}
