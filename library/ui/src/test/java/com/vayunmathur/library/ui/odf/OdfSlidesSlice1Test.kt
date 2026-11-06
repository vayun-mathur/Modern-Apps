package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slide geometry: bounds reads and repositioning across element kinds.
 * Slice-1 coverage for library/ui (odf package).
 */
class OdfSlidesSlice1Test {

    private fun para() = OdfParagraph(listOf(OdfSpan("t")))

    @Test
    fun `a frame reports its own box`() {
        val el = OdfSlideElement.Frame(OdfFrame(10f, 20f, 100f, 50f, listOf(para())))
        assertTrue(el.bounds().contentEquals(floatArrayOf(10f, 20f, 100f, 50f)))
    }

    @Test
    fun `a rect reports its own box`() {
        val el = OdfSlideElement.Shape(OdfShape.Rect(5f, 6f, 7f, 8f))
        assertTrue(el.bounds().contentEquals(floatArrayOf(5f, 6f, 7f, 8f)))
    }

    @Test
    fun `a line bounds at its min corner`() {
        val el = OdfSlideElement.Shape(OdfShape.Line(x = 30f, y = 40f, x2 = 10f, y2 = 20f, width = 0f, height = 0f))
        assertTrue(el.bounds().contentEquals(floatArrayOf(10f, 20f, 20f, 20f)))
    }

    @Test
    fun `repositioning moves frames and rects`() {
        val frame = setElementBounds(
            OdfSlideElement.Frame(OdfFrame(0f, 0f, 10f, 10f, listOf(para()))),
            1f, 2f, 30f, 40f,
        ) as OdfSlideElement.Frame
        assertEquals(1f, frame.frame.x)
        assertEquals(40f, frame.frame.height)
        val rect = setElementBounds(
            OdfSlideElement.Shape(OdfShape.Rect(0f, 0f, 10f, 10f)),
            1f, 2f, 30f, 40f,
        ) as OdfSlideElement.Shape
        assertTrue(rect.bounds().contentEquals(floatArrayOf(1f, 2f, 30f, 40f)))
    }
}
