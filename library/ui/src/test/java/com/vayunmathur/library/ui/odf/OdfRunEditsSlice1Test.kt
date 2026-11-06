package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Framework-free single-paragraph and run edits on text documents.
 * Slice-1 coverage for library/ui (odf package).
 */
class OdfRunEditsSlice1Test {

    private fun doc(vararg texts: String) = OdfDocument.TextDocument(
        title = "t",
        content = texts.map { OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(it)))) },
    )

    private fun textOf(d: OdfDocument.TextDocument, i: Int = 0): String =
        (d.content[i] as OdfContentBlock.Paragraph).paragraph.spans.joinToString("") { it.text }

    @Test
    fun `updating text preserves surrounding styling`() {
        val styled = OdfDocument.TextDocument(
            title = "t",
            content = listOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(listOf(OdfSpan("he", bold = true), OdfSpan("llo")))
                )
            ),
        )
        val out = styled.updateParagraphText(0, "hey")!!
        assertEquals("hey", textOf(out))
        // The kept prefix keeps its bold span.
        val spans = (out.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.first().bold)
    }

    @Test
    fun `identical text is a no-op`() {
        assertNull(doc("hello").updateParagraphText(0, "hello"))
    }

    @Test
    fun `a non-paragraph block is a no-op`() {
        val d = OdfDocument.TextDocument(
            title = "t",
            content = listOf(OdfContentBlock.PageBreak),
        )
        assertNull(d.updateParagraphText(0, "x"))
        assertNull(d.updateParagraphText(9, "x"))
    }

    @Test
    fun `span style applies to a char range`() {
        val out = doc("hello").applySpanStyleToRange(0, 1, 3) { it.copy(bold = true) }!!
        val spans = (out.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        // "h" untouched, "el" bolded, "lo" untouched.
        assertEquals(listOf("h", "el", "lo"), spans.map { it.text })
        assertEquals(listOf(false, true, false), spans.map { it.bold })
    }

    @Test
    fun `span style on an empty paragraph is a no-op`() {
        val d = OdfDocument.TextDocument(
            title = "t",
            content = listOf(OdfContentBlock.Paragraph(OdfParagraph(emptyList()))),
        )
        assertNull(d.applySpanStyleToRange(0, 0, 0) { it.copy(bold = true) })
    }

    @Test
    fun `range format queries read the chars`() {
        val d = doc("hello")
        assertTrue(d.rangeHasFormat(0, 0, 5) { !it.bold })
        assertTrue(!d.rangeHasFormat(0, 0, 5) { it.bold })
        assertTrue(!d.rangeHasFormat(9, 0, 5) { true })
    }

    @Test
    fun `a multi-paragraph run update joins the range`() {
        val d = doc("one", "two", "three")
        val out = d.updateParagraphRun(0, 1, "ONE TWO")!!
        assertEquals(2, out.content.size)
        assertEquals("ONE TWO", textOf(out, 0))
        assertEquals("three", textOf(out, 1))
    }

    @Test
    fun `an identical run update is a no-op`() {
        assertNull(doc("one", "two").updateParagraphRun(0, 1, "one\ntwo"))
    }

    @Test
    fun `a run update outside the content is a no-op`() {
        assertNull(doc("one").updateParagraphRun(0, 5, "x"))
        assertNull(doc("one").updateParagraphRun(2, 1, "x"))
    }

    @Test
    fun `run span style bolds a global range`() {
        val d = doc("one", "two")
        val out = d.applyRunSpanStyle(0, 1, 0, 3) { it.copy(bold = true) }!!
        assertEquals("one", textOf(out, 0))
        val spans = (out.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.all { it.bold })
        // The second paragraph is untouched.
        val spans2 = (out.content[1] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans2.none { it.bold })
    }

    @Test
    fun `run format queries span paragraphs`() {
        val d = doc("one", "two")
        assertTrue(d.runRangeHasFormat(0, 1, 0, 3) { !it.bold })
        assertTrue(!d.runRangeHasFormat(0, 1, 0, 3) { it.bold })
        assertTrue(!d.runRangeHasFormat(0, 5, 0, 3) { true })
    }

    @Test
    fun `run paragraph index resolves global offsets`() {
        val d = doc("one", "two")
        assertEquals(0, d.runParagraphIndexAt(0, 1, 1))
        assertEquals(1, d.runParagraphIndexAt(0, 1, 4))
        // Out of range: the run is absent, so the answer is the start itself.
        assertEquals(5, d.runParagraphIndexAt(5, 6, 0))
    }

    @Test
    fun `mutating run paragraphs transforms the covered ones`() {
        val d = doc("one", "two", "three")
        val out = d.mutateRunParagraphs(0, 2, 0, 7) { it.copy(alignment = androidx.compose.ui.text.style.TextAlign.Center) }!!
        val first = (out.content[0] as OdfContentBlock.Paragraph).paragraph
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, first.alignment)
        assertNull(d.mutateRunParagraphs(0, 9, 0, 1) { it })
    }

    @Test
    fun `inserting text in a run splices at the offset`() {
        val d = doc("one", "two")
        // Global offset 3 appends to the first paragraph (no newline spans it).
        val out = d.insertTextInRun(0, 1, 3, "!")!!
        assertEquals("one!", textOf(out, 0))
        assertEquals("two", textOf(out, 1))
        // A newline in the insert splits the run into more paragraphs.
        val split = d.insertTextInRun(0, 1, 3, "!\nmid")!!
        assertEquals("one!", textOf(split, 0))
        assertEquals("mid", textOf(split, 1))
        assertEquals("two", textOf(split, 2))
        assertNull(d.insertTextInRun(0, 9, 0, "x"))
    }

    @Test
    fun `clearing run formatting drops span styles`() {
        val d = OdfDocument.TextDocument(
            title = "t",
            content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("hi", bold = true, italic = true))))
            ),
        )
        val out = d.clearRunFormatting(0, 0, 0, 2)!!
        val spans = (out.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.all { !it.bold && !it.italic })
    }
}
