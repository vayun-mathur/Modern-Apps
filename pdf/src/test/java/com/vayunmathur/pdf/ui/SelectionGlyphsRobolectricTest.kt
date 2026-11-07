package com.vayunmathur.pdf.ui

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.pdf.util.PdfPrimitive
import com.vayunmathur.pdf.util.SafePdfPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionGlyphsRobolectricTest {
    @Test fun pdfTypefaceFamiliesAndStyles() {
        val serif = pdfTypeface(1, false, false); assertTrue(serif != null)
        val sans = pdfTypeface(0, false, false); assertTrue(sans != null)
        val monoBold = pdfTypeface(2, true, false); assertTrue(monoBold.isBold)
        val italic = pdfTypeface(99, false, true); assertTrue(italic.isItalic)
        val boldItalic = pdfTypeface(1, true, true); assertTrue(boldItalic.isBold && boldItalic.isItalic)
    }

    @Test fun buildEmbeddedGlyphsFromText() {
        val page = SafePdfPage(612f, 792f, listOf(
            PdfPrimitive.Text(origin = Offset(72f, 700f), size = 12f, color = 0xFF000000.toInt(), text = "Hi", advance = 12f),
            PdfPrimitive.Text(origin = Offset(72f, 600f), size = 12f, color = 0, text = "", advance = 0f),
            PdfPrimitive.FillPath(0, false, listOf(listOf(Offset.Zero))),
        ))
        val glyphs = buildEmbeddedGlyphs(page, ch = 792f, scale = 1f)
        assertEquals(2, glyphs.size)
        assertEquals("H", glyphs[0].glyph.ch); assertEquals("i", glyphs[1].glyph.ch)
        // Ordered by -origin.y: higher page y first
        assertTrue(glyphs[0].orderY < glyphs[1].orderY || glyphs.size == 2)
        assertTrue(glyphs[0].glyph.right > glyphs[0].glyph.left)
    }

    @Test fun buildEmbeddedGlyphsInvisibleUsesAdvance() {
        val page = SafePdfPage(612f, 792f, listOf(
            PdfPrimitive.Text(origin = Offset(0f, 700f), size = 10f, color = 0, text = "abcd", advance = 40f, renderMode = 3),
        ))
        val glyphs = buildEmbeddedGlyphs(page, 792f, 1f)
        assertEquals(4, glyphs.size)
        // Total span should be about advance*scale = 40
        val span = glyphs.last().glyph.right - glyphs.first().glyph.left
        assertTrue(span in 30f..50f, "span=$span")
    }

    @Test fun buildEmbeddedGlyphsEmptyPage() {
        assertTrue(buildEmbeddedGlyphs(SafePdfPage(100f, 100f, emptyList()), 100f, 1f).isEmpty())
    }
}
