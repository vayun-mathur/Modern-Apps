package com.vayunmathur.library.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Slice 2/3 (library/ui): [darkColorScheme] — the app-theme builder over
 * Material's dark baseline. Every override lands; everything else stays on
 * the baseline, so a theme that names five colours does not silently reset
 * the other eight.
 */
class Slice2ColorSchemeTest {

    @Test
    fun `the default is materials dark baseline`() {
        val ours = darkColorScheme()
        val baseline = androidx.compose.material3.darkColorScheme()
        assertEquals(baseline.primary, ours.primary)
        assertEquals(baseline.surface, ours.surface)
        assertEquals(baseline.error, ours.error)
        assertEquals(baseline.onPrimary, ours.onPrimary)
    }

    @Test
    fun `overrides land and the rest stays put`() {
        val scheme = darkColorScheme(
            primary = Color(0xFF123456),
            error = Color(0xFF654321),
        )
        assertEquals(Color(0xFF123456), scheme.primary)
        assertEquals(Color(0xFF654321), scheme.error)
        val baseline = androidx.compose.material3.darkColorScheme()
        assertEquals(baseline.secondary, scheme.secondary)
        assertEquals(baseline.surface, scheme.surface)
        assertEquals(baseline.onSurface, scheme.onSurface)
        assertEquals(baseline.primaryContainer, scheme.primaryContainer)
    }

    @Test
    fun `two builds with different overrides differ only there`() {
        val a = darkColorScheme(primary = Color.Red)
        val b = darkColorScheme(primary = Color.Blue)
        assertNotEquals(a.primary, b.primary)
        assertEquals(a.secondary, b.secondary)
        assertEquals(a.error, b.error)
    }
}
