package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * HTML import details: inline styles, links, lists, tables, images, entities.
 * Slice-1 coverage for library/ui (odf package). Robolectric for Base64.
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class HtmlImportDetailsSlice1Test {

    @Test
    fun `inline styles map to span formatting`() {
        val doc = HtmlOdfConverter.htmlToOdf(
            "<p><span style=\"color:#ff0000;font-size:18pt;font-weight:bold\">big</span> " +
                "<span style=\"font-style:italic;text-decoration:underline\">iu</span> " +
                "<span style=\"text-decoration:line-through\">s</span></p>"
        )
        val spans = (doc.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        val big = spans.first { it.text.contains("big") }
        assertEquals(0xFFFF0000L, big.color)
        assertEquals(18f, big.fontSize)
        assertTrue(big.bold)
        val iu = spans.first { it.text.contains("iu") }
        assertTrue(iu.italic && iu.underline)
        assertTrue(spans.any { it.strikethrough && it.text.contains("s") }, spans.toString())
    }

    @Test
    fun `legacy color attributes map to named colors`() {
        val doc = HtmlOdfConverter.htmlToOdf("<p><font color=\"red\">r</font> <font color=\"#00ff00\">g</font></p>")
        val spans = (doc.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.any { it.color == 0xFFFF0000L }, spans.toString())
        assertTrue(spans.any { it.color == 0xFF00FF00L }, spans.toString())
    }

    @Test
    fun `links carry the href`() {
        val doc = HtmlOdfConverter.htmlToOdf("<p>see <a href=\"https://x.test\">docs</a></p>")
        val spans = (doc.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        val link = spans.first { it.text.contains("docs") }
        assertEquals("https://x.test", link.href)
        assertTrue(link.underline)
    }

    @Test
    fun `ordered lists number their items`() {
        val doc = HtmlOdfConverter.htmlToOdf("<ol><li>one</li><li>two</li></ol>")
        val items = doc.content.map { (it as OdfContentBlock.Paragraph).paragraph }
        assertEquals(2, items.size)
        assertTrue(items.all { it.style == ParagraphStyle.LIST_ITEM }, items.toString())
        assertTrue(items.all { it.listType == ListType.NUMBERED }, items.toString())
        assertEquals(1, items[0].listItemIndex)
        assertEquals(2, items[1].listItemIndex)
    }

    @Test
    fun `an ol start attribute offsets numbering`() {
        val doc = HtmlOdfConverter.htmlToOdf("<ol start=\"5\"><li>five</li></ol>")
        val item = (doc.content[0] as OdfContentBlock.Paragraph).paragraph
        assertEquals(5, item.listItemIndex)
    }

    @Test
    fun `blockquotes rules and line breaks import`() {
        val doc = HtmlOdfConverter.htmlToOdf("<blockquote>quoted</blockquote><hr><p>a<br>b</p>")
        val texts = doc.content.map {
            (it as OdfContentBlock.Paragraph).paragraph.spans.joinToString("") { s -> s.text }
        }
        assertTrue(texts.any { it.contains("quoted") }, texts.toString())
        assertTrue(texts.any { it.contains("a\nb") }, texts.toString())
        // The <hr> becomes an empty bordered paragraph.
        assertTrue(
            doc.content.any {
                val p = (it as OdfContentBlock.Paragraph).paragraph
                p.borderColor != null
            },
            texts.toString(),
        )
    }

    @Test
    fun `a table imports rows headers and spans`() {
        val doc = HtmlOdfConverter.htmlToOdf(
            "<table><tr><th>H</th></tr><tr><td colspan=\"2\">wide</td></tr></table>"
        )
        val table = (doc.content[0] as OdfContentBlock.Table).table
        assertEquals(2, table.rows.size)
        assertEquals(1, table.headerRowCount)
        assertEquals("H", table.rows[0].cells[0].paragraphs[0].spans[0].text)
        assertEquals(2, table.rows[1].cells[0].colSpan)
    }

    @Test
    fun `a data-url image imports its bytes`() {
        // "hi" in base64.
        val doc = HtmlOdfConverter.htmlToOdf(
            "<p>before</p><img src=\"data:image/png;base64,aGk=\" width=\"10\" height=\"20\" alt=\"pic\">"
        )
        val img = doc.content.filterIsInstance<OdfContentBlock.Image>().single()
        assertEquals("pic", img.image.altDesc)
        assertEquals(10f, img.image.width)
        assertEquals(20f, img.image.height)
        assertTrue(img.image.imageData.isNotEmpty())
    }

    @Test
    fun `remote images are skipped`() {
        val doc = HtmlOdfConverter.htmlToOdf("<p>t</p><img src=\"https://x.test/i.png\">")
        assertTrue(doc.content.none { it is OdfContentBlock.Image })
    }

    @Test
    fun `entities and alignment decode`() {
        val doc = HtmlOdfConverter.htmlToOdf("<p style=\"text-align:center\">a &amp; b &lt;c&gt;</p>")
        val p = (doc.content[0] as OdfContentBlock.Paragraph).paragraph
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, p.alignment)
        assertTrue(p.spans.joinToString("") { it.text }.contains("a & b <c>"), p.spans.toString())
    }

    @Test
    fun `headings five and six fold to heading four`() {
        val doc = HtmlOdfConverter.htmlToOdf("<h5>small</h5>")
        assertEquals(
            ParagraphStyle.HEADING4,
            (doc.content[0] as OdfContentBlock.Paragraph).paragraph.style,
        )
    }

    @Test
    fun `sub sup strike and del import`() {
        val doc = HtmlOdfConverter.htmlToOdf("<p><sup>2</sup><sub>3</sub><del>gone</del></p>")
        val spans = (doc.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.any { it.superscript && it.text == "2" }, spans.toString())
        assertTrue(spans.any { it.subscript && it.text == "3" }, spans.toString())
        assertTrue(spans.any { it.strikethrough && it.text == "gone" }, spans.toString())
    }

    @Test
    fun `a styled html document round-trips`() {
        val html = HtmlOdfConverter.odfToHtml(
            OdfDocument.TextDocument(
                title = "t",
                content = listOf(
                    OdfContentBlock.Paragraph(
                        OdfParagraph(
                            listOf(OdfSpan("B", bold = true)),
                            style = ParagraphStyle.HEADING1,
                        )
                    ),
                ),
            )
        )
        val back = HtmlOdfConverter.htmlToOdf(html)
        val first = (back.content[0] as OdfContentBlock.Paragraph).paragraph
        assertEquals(ParagraphStyle.HEADING1, first.style)
        assertTrue(first.spans.any { it.bold }, first.spans.toString())
    }
}
