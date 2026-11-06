package com.vayunmathur.maps.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Postal-address normalisation for the contacts shortcut.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 */
class ContactLookupNormalizeSlice2Test {

    @Test
    fun `a single-line address passes through`() {
        assertEquals("1 Ferry Building, San Francisco", "1 Ferry Building, San Francisco".normalizeAddress())
    }

    @Test
    fun `newlines become commas`() {
        assertEquals(
            "1 Ferry Building, San Francisco, CA 94111",
            "1 Ferry Building\nSan Francisco, CA 94111".normalizeAddress(),
        )
    }

    @Test
    fun `blank lines are dropped`() {
        assertEquals(
            "1 Ferry Building, San Francisco",
            "1 Ferry Building\n\nSan Francisco\n".normalizeAddress(),
        )
    }

    @Test
    fun `runs of whitespace collapse`() {
        assertEquals(
            "1 Ferry Building, San Francisco",
            "  1  Ferry\tBuilding  \n   San  Francisco  ".normalizeAddress(),
        )
    }

    @Test
    fun `an all-blank address collapses to empty`() {
        assertEquals("", "\n   \n\t\n".normalizeAddress())
    }
}
