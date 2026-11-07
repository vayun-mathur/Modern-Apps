package com.vayunmathur.appstore.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Detail-page number formatting: SI sizes and rounded install counts.
 * Pure functions, so plain JVM tests pin them.
 */
class AppMetaFormatTest {

    @Test
    fun `sizes use si units`() {
        assertEquals("", formatSize(0L))
        assertEquals("", formatSize(-5L))
        assertEquals("512 B", formatSize(512L))
        assertEquals("12 kB", formatSize(12_000L))
        assertEquals("1.5 MB", formatSize(1_500_000L))
        assertEquals("2.50 GB", formatSize(2_500_000_000L))
    }

    @Test
    fun `install counts round down with a plus`() {
        assertEquals("", formatCount(0L))
        assertEquals("42", formatCount(42L))
        assertEquals("50K+", formatCount(50_000L))
        assertEquals("1.2M+", formatCount(1_200_000L))
        assertEquals("5.0B+", formatCount(5_000_000_000L))
    }
}
