package com.vayunmathur.pdf.util

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafePdfModelsTest {
    @Test fun blendModeFromCode() {
        assertEquals(BlendMode.Normal, BlendMode.fromCode(0))
        assertEquals(BlendMode.Multiply, BlendMode.fromCode(1))
        assertEquals(BlendMode.Luminosity, BlendMode.fromCode(15))
        assertEquals(BlendMode.Normal, BlendMode.fromCode(99))
        assertEquals(BlendMode.Normal, BlendMode.fromCode(-1))
        assertEquals(16, BlendMode.entries.size)
        BlendMode.entries.forEach { assertEquals(it, BlendMode.fromCode(it.code)) }
    }

    @Test fun textDefaultAdvanceIsHalfSizePerGlyph() {
        val t = PdfPrimitive.Text(origin = Offset(10f, 20f), size = 12f, color = 0xFF000000.toInt(), text = "abcd")
        assertEquals(24f, t.advance) // 12 * 0.5 * 4
        assertEquals(0, t.renderMode); assertEquals(BlendMode.Normal, t.blend); assertEquals(1f, t.hScale)
        assertFalse(t.isBold); assertFalse(t.outline)
    }

    @Test fun dataClassesHoldFields() {
        val page = SafePdfPage(612f, 792f, listOf(PdfPrimitive.ClipPop, PdfPrimitive.GroupPop))
        assertEquals(612f, page.width); assertEquals(2, page.primitives.size)
        val a = SafeAnnotation(7L, 2, 1f, 2f, 3f, 4f, 0xFF00FF00.toInt(), "hi")
        assertEquals(7L, a.id); assertEquals("hi", a.contents)
        val f = SafeFormField(3L, 1, 0f, 0f, 10f, 10f, "name", "val", true)
        assertTrue(f.checked); assertEquals("val", f.value)
        assertEquals(SafeOutlineItem(1, 5, "Ch1"), SafeOutlineItem(1, 5, "Ch1"))
        assertEquals(5, SafeSearchMatch(5, 0f, 0f, 1f, 1f).page)
        assertEquals(-1, SafeLink(0f, 0f, 1f, 1f, -1, "").destPage)
    }

    @Test fun strokePathDefaultsAndArrayEqualityNote() {
        val s = PdfPrimitive.StrokePath(0, 2f, floatArrayOf(3f, 2f), 0f, listOf(Offset.Zero))
        assertEquals(0, s.cap); assertEquals(10f, s.miter)
        assertTrue(s.dash.contentEquals(floatArrayOf(3f, 2f)))
    }

    @Test fun pathOps() {
        assertEquals(PathOp.Move(1f, 2f), PathOp.Move(1f, 2f))
        assertEquals(PathOp.Close, PathOp.Close)
        val c = PathOp.Cubic(1f, 2f, 3f, 4f, 5f, 6f); assertEquals(6f, c.y3)
    }

    @Test fun safePdfImagesBudgets() {
        assertTrue(SafePdfImages.badDimensions(0, 10)); assertTrue(SafePdfImages.badDimensions(10, -1))
        assertTrue(SafePdfImages.badDimensions(20001, 10)); assertFalse(SafePdfImages.badDimensions(100, 100))
        assertTrue(SafePdfImages.imageTooLarge(SafePdfImages.MAX_IMAGE_DATA_BYTES + 1))
        assertFalse(SafePdfImages.imageTooLarge(1024))
        assertEquals(1, SafePdfImages.sampleSizeFor(100, 100))
        assertEquals(2, SafePdfImages.sampleSizeFor(8000, 8000)) // 64M -> /4 =16M fits
        assertTrue(SafePdfImages.sampleSizeFor(20000, 20000) >= 4)
    }

    @Test fun scanFilterEntries() {
        assertEquals(listOf(ScanFilter.NONE, ScanFilter.GRAYSCALE, ScanFilter.BW, ScanFilter.CONTRAST), ScanFilter.entries.toList())
    }
}
