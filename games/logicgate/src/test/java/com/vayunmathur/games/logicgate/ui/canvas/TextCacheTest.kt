package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TextCacheTest {
    private val density = Density(2f)
    private val measurer = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), density, LayoutDirection.Ltr)

    @Test
    fun aLabelIsMeasuredOnceAndReusedOnLaterFrames() {
        val cache = TextCache(measurer)
        val first = cache.measure(density, "NAND", 9f, FontWeight.SemiBold)
        repeat(10) { assertSame(first, cache.measure(density, "NAND", 9f, FontWeight.SemiBold)) }
        assertEquals(1, cache.size)
        assertNotSame(first, cache.measure(density, "NAND", 14f, FontWeight.SemiBold), "size is part of the key")
        assertNotSame(first, cache.measure(density, "AND", 9f, FontWeight.SemiBold), "text is part of the key")
        assertEquals(3, cache.size)
    }

    @Test
    fun theCacheStaysBounded() {
        val cache = TextCache(measurer)
        repeat(600) { cache.measure(density, "0x$it", 10f) }
        assertTrue(cache.size <= 512)
    }
}
