package com.vayunmathur.logviewer.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogKindLevelTest {

    @Test
    fun `logcat display trims and expands tabs, error report is verbatim`() {
        assertEquals("a b c", LogKind.Logcat.displayLine("  a\tb c  "))
        assertEquals("  a\tb  ", LogKind.ErrorReport.displayLine("  a\tb  "))
    }

    @Test
    fun `logcat copy trims but keeps tabs, error report copy is verbatim`() {
        assertEquals("a\tb", LogKind.Logcat.copyLine("  a\tb  "))
        assertEquals("  a\tb  ", LogKind.ErrorReport.copyLine("  a\tb  "))
    }

    @Test
    fun `kind presentation defaults differ`() {
        assertEquals(9f, LogKind.Logcat.initialFontSizeSp)
        assertTrue(LogKind.Logcat.scrollToBottom)
        assertEquals(12f, LogKind.ErrorReport.initialFontSizeSp)
        assertFalse(LogKind.ErrorReport.scrollToBottom)
    }

    @Test
    fun `levels carry priority letter and header value`() {
        assertEquals(
            listOf(2, 3, 4, 5, 6, 7),
            LogLevel.entries.map { it.priority },
        )
        assertEquals(listOf('V', 'D', 'I', 'W', 'E', 'A'), LogLevel.entries.map { it.letter })
        assertEquals("warn", LogLevel.Warn.headerValue)
        assertEquals("verbose", LogLevel.Verbose.headerValue)
    }

    @Test
    fun `ofPriority maps exact values and clamps out of range`() {
        assertEquals(LogLevel.Verbose, LogLevel.ofPriority(2))
        assertEquals(LogLevel.Error, LogLevel.ofPriority(6))
        assertEquals(LogLevel.Assert, LogLevel.ofPriority(7))
        assertEquals(LogLevel.Verbose, LogLevel.ofPriority(-100))
        assertEquals(LogLevel.Verbose, LogLevel.ofPriority(0))
        assertEquals(LogLevel.Assert, LogLevel.ofPriority(99))
    }
}
