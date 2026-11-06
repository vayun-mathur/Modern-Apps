package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Paragraph link operations: finding and setting links inside a run.
 * Slice-1 coverage for library/ui (odf package).
 */
class ParagraphLinksSlice1Test {

    private fun docOf(vararg blocks: OdfContentBlock) =
        OdfDocument.TextDocument(title = "t", content = blocks.toList())

    private fun linkPara() = OdfContentBlock.Paragraph(
        OdfParagraph(
            listOf(
                OdfSpan("see "),
                OdfSpan("docs", href = "https://x.test"),
                OdfSpan(" now"),
            )
        )
    )

    @Test
    fun `linkAt finds the link covering the caret`() {
        val d = docOf(linkPara())
        val link = d.linkAt(0, 0, 5)
        assertNotNull(link)
        assertEquals("https://x.test", link.url)
        assertEquals("docs", link.text)
    }

    @Test
    fun `linkAt at the boundary still finds the link`() {
        val d = docOf(linkPara())
        // Caret right after "docs" (offset 8) still resolves via the previous char.
        assertNotNull(d.linkAt(0, 0, 8))
        assertNull(d.linkAt(0, 0, 1))
    }

    @Test
    fun `linkAt misses outside any run`() {
        val d = docOf(linkPara())
        assertNull(d.linkAt(5, 5, 0))
    }

    @Test
    fun `setLinkInRun wraps the range in a link`() {
        val d = docOf(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("hello world"))))
        )
        val out = d.setLinkInRun(0, 0, 6, 11, "world", "https://x.test")!!
        val spans = (out.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.any { it.href == "https://x.test" && it.text.contains("world") }, spans.toString())
    }

    @Test
    fun `setLinkInRun replaces an existing link extent`() {
        val d = docOf(linkPara())
        val before = d.linkAt(0, 0, 5)!!
        val out = d.setLinkInRun(0, 0, before.gStart, before.gEnd, "manual", "https://y.test")!!
        val spans = (out.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.any { it.href == "https://y.test" }, spans.toString())
        assertTrue(spans.none { it.href == "https://x.test" }, spans.toString())
    }

    @Test
    fun `setLinkInRun rejects cross-paragraph ranges`() {
        val d = docOf(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("one")))),
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("two")))),
        )
        assertNull(d.setLinkInRun(0, 1, 0, 6, "x", "https://x.test"))
    }

    @Test
    fun `setLinkInRun misses outside any run`() {
        val d = docOf(linkPara())
        assertNull(d.setLinkInRun(5, 5, 0, 1, "x", "https://x.test"))
    }

    @Test
    fun `link round-trips through the editor state`() {
        val state = OdfTextEditorState(docOf(linkPara()))
        val link = state.linkAt(0, 0, 5)
        assertNotNull(link)
        assertEquals("docs", link.text)
        state.setLink(0, 0, 0, 3, "see", "https://z.test")
        val after = state.linkAt(0, 0, 1)
        assertNotNull(after)
        assertEquals("https://z.test", after.url)
    }
}
