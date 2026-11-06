package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Span/paragraph locating helpers: char splitting, coalescing, locating.
 * Slice-1 coverage for library/ui (odf package).
 */
class OdfTextHelpersSlice1Test {

    @Test
    fun `spans split to one char each and coalesce back`() {
        val spans = listOf(OdfSpan("he", bold = true), OdfSpan("llo"))
        val chars = spansToChars(spans)
        assertEquals(5, chars.size)
        assertTrue(chars.take(2).all { it.bold })
        assertTrue(chars.drop(2).none { it.bold })
        val back = charsToSpans(chars)
        assertEquals(spans, back)
    }

    @Test
    fun `empty chars coalesce to one empty span`() {
        assertEquals(listOf(OdfSpan(text = "")), charsToSpans(emptyList()))
    }

    @Test
    fun `leading chars drop with styling preserved`() {
        val spans = listOf(OdfSpan("he", bold = true), OdfSpan("llo"))
        val out = removeLeadingChars(spans, 2)
        assertEquals("llo", out.joinToString("") { it.text })
        // Dropping everything yields a single empty span.
        assertEquals(listOf(OdfSpan(text = "")), removeLeadingChars(spans, 99))
    }

    @Test
    fun `run locate walks paragraphs and separators`() {
        // "one" (3) + sep + "two" (3): offset 4 is the second char of "two".
        assertEquals(0 to 2, runLocate(listOf(3, 3), 2))
        assertEquals(1 to 1, runLocate(listOf(3, 3), 5))
        // Past the end clamps to the last paragraph's end.
        assertEquals(1 to 3, runLocate(listOf(3, 3), 99))
    }

    @Test
    fun `paragraph starts accumulate lengths plus separators`() {
        assertEquals(0, paragraphStartGlobal(listOf(3, 3), 0))
        assertEquals(4, paragraphStartGlobal(listOf(3, 3), 1))
        assertEquals(listOf(3, 3), paraLens(listOf(OdfParagraph(listOf(OdfSpan("one"))), OdfParagraph(listOf(OdfSpan("two"))))))
    }

    @Test
    fun `run paras reject out-of-range and non-paragraph runs`() {
        val doc = OdfDocument.TextDocument(
            title = "t",
            content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("a")))),
                OdfContentBlock.PageBreak,
            ),
        )
        assertTrue(doc.runParas(0, 0)!!.size == 1)
        assertEquals(null, doc.runParas(0, 1))
        assertEquals(null, doc.runParas(-1, 0))
        assertEquals(null, doc.runParas(1, 0))
    }
}
