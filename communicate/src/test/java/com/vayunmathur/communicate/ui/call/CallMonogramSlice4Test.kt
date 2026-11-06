package com.vayunmathur.communicate.ui.call

import com.vayunmathur.communicate.ui.initialsFor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Slice-4 coverage for the monogram helper used by the call header.
 *
 * Pure JVM: pins the two-initials contract so a name-format regression is
 * caught without rendering.
 */
class CallMonogramSlice4Test {

    @Test
    fun `takes the first letters of the first two words`() {
        assertEquals("PL", initialsFor("Pat Lee"))
        assertEquals("P", initialsFor("Pat"))
        assertEquals("AB", initialsFor("a b c"))
    }

    @Test
    fun `splits on phone-number separators`() {
        assertEquals("15", initialsFor("+1 555"))
        assertEquals("AB", initialsFor("a-b_c"))
    }

    @Test
    fun `blank names fall back to a placeholder`() {
        assertEquals("?", initialsFor(""))
        assertEquals("?", initialsFor("   "))
    }
}
