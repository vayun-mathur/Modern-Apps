package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTML <-> ODF conversion: headings, spans, lists, tables and round-trips.
 * Slice-1 coverage for library/ui (odf package + Html converters).
 */
class HtmlOdfConverterSlice1Test {

    private fun para(text: String) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text))))

    private fun docOf(vararg blocks: OdfContentBlock) =
        OdfDocument.TextDocument(title = "t", content = blocks.toList())

    @Test
    fun `export emits a full html shell`() {
        val html = HtmlOdfConverter.odfToHtml(docOf(para("hi")))
        assertTrue(html.contains("<title>t</title>"), html)
        assertTrue(html.contains("<p>hi</p>"), html)
    }

    @Test
    fun `headings map to h-tags`() {
        val html = HtmlOdfConverter.odfToHtml(
            docOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("H")), ParagraphStyle.HEADING1)),
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("S")), ParagraphStyle.HEADING2)),
            )
        )
        assertTrue(html.contains("<h1>H</h1>"), html)
        assertTrue(html.contains("<h2>S</h2>"), html)
    }

    @Test
    fun `bold and italic spans map to tags`() {
        val html = HtmlOdfConverter.odfToHtml(
            docOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(listOf(OdfSpan("b", bold = true), OdfSpan("i", italic = true)))
                )
            )
        )
        assertTrue(html.contains("<strong>b</strong>"), html)
        assertTrue(html.contains("<em>i</em>"), html)
    }

    @Test
    fun `list items group into a list`() {
        fun item(text: String) = OdfContentBlock.Paragraph(
            OdfParagraph(
                listOf(OdfSpan(text)),
                style = ParagraphStyle.LIST_ITEM,
                listLevel = 1,
            )
        )
        val html = HtmlOdfConverter.odfToHtml(docOf(item("a"), item("b")))
        assertEquals(1, Regex("<ul>").findAll(html).count())
        assertEquals(2, Regex("<li>").findAll(html).count())
    }

    @Test
    fun `import reads headings and paragraphs`() {
        val doc = HtmlOdfConverter.htmlToOdf("<h1>Title</h1><p>Hello <b>bold</b></p>", "t")
        assertTrue(doc.content.size >= 2)
        val first = (doc.content[0] as OdfContentBlock.Paragraph).paragraph
        assertEquals(ParagraphStyle.HEADING1, first.style)
        assertEquals("Title", first.spans.joinToString("") { it.text })
    }

    @Test
    fun `import of bold text keeps the span style`() {
        val doc = HtmlOdfConverter.htmlToOdf("<p>Hello <b>bold</b></p>")
        val spans = (doc.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.any { it.bold && it.text.contains("bold") })
    }

    @Test
    fun `empty import yields one empty paragraph`() {
        val doc = HtmlOdfConverter.htmlToOdf("")
        assertEquals(1, doc.content.size)
    }

    @Test
    fun `a heading round-trips through html`() {
        val doc = docOf(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("Title")), ParagraphStyle.HEADING1)),
            para("Body text"),
        )
        val back = HtmlOdfConverter.htmlToOdf(HtmlOdfConverter.odfToHtml(doc))
        assertEquals(
            ParagraphStyle.HEADING1,
            (back.content[0] as OdfContentBlock.Paragraph).paragraph.style,
        )
        assertTrue(back.content.any {
            (it as? OdfContentBlock.Paragraph)?.paragraph?.spans?.joinToString("") { s -> s.text } == "Body text"
        })
    }
}
