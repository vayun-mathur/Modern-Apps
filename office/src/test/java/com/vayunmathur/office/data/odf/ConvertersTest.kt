package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.EpubExporter
import com.vayunmathur.library.ui.odf.HtmlOdfConverter
import com.vayunmathur.library.ui.odf.LatexExporter
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSerializer
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.library.ui.odf.PdfExporter
import com.vayunmathur.library.ui.odf.RtfOdfConverter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the document-format converters (HTML/RTF round-trip, EPUB/LaTeX/PDF export) and the
 * ODF list-style export fix.
 */
class ConvertersTest {

    private fun textDoc() = OdfDocument.TextDocument("Doc", listOf(
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("Title")), style = ParagraphStyle.HEADING1)),
        OdfContentBlock.Paragraph(OdfParagraph(listOf(
            OdfSpan("hello "),
            OdfSpan("bold", bold = true),
            OdfSpan(" and "),
            OdfSpan("red", color = 0xFFFF0000)))),
        OdfContentBlock.Paragraph(OdfParagraph(
            listOf(OdfSpan("first")),
            style = ParagraphStyle.LIST_ITEM,
            listType = ListType.NUMBERED,
            listItemIndex = 1)),
        OdfContentBlock.Paragraph(OdfParagraph(
            listOf(OdfSpan("second")),
            style = ParagraphStyle.LIST_ITEM,
            listType = ListType.NUMBERED,
            listItemIndex = 2)),
        OdfContentBlock.Table(OdfTable(rows = listOf(
            OdfTableRow(listOf(
                OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("A"))))),
                OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("B")))))))
        )))
    ))

    @Test fun htmlRoundTrip() {
        val html = HtmlOdfConverter.odfToHtml(textDoc())
        assertTrue(html.contains("<h1"))
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<ol>"))
        val back = HtmlOdfConverter.htmlToOdf(html, "Doc")
        val paras = back.content.filterIsInstance<OdfContentBlock.Paragraph>()
        assertEquals(ParagraphStyle.HEADING1, paras[0].paragraph.style)
        assertTrue(paras.any { it.paragraph.spans.any { s -> s.text == "bold" && s.bold } })
        assertTrue(paras.any { it.paragraph.style == ParagraphStyle.LIST_ITEM && it.paragraph.listType == ListType.NUMBERED })
        assertTrue(back.content.any { it is OdfContentBlock.Table })
    }

    @Test fun rtfRoundTrip() {
        val rtf = RtfOdfConverter.odfToRtf(textDoc())
        assertTrue(rtf.startsWith("{\\rtf1"))
        assertTrue(rtf.contains("\\b"))
        val back = RtfOdfConverter.rtfToOdf(rtf, "Doc")
        val allSpans = back.content.filterIsInstance<OdfContentBlock.Paragraph>().flatMap { it.paragraph.spans }
        assertTrue(allSpans.any { it.text.contains("bold") && it.bold })
        assertTrue(allSpans.any { it.text.contains("red") && it.color == 0xFFFF0000L })
    }

    @Test fun epubExportIsZip() {
        val bytes = EpubExporter.export(textDoc())
        assertTrue(bytes.size > 100)
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("application/epub+zip"))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("content.opf"))
    }

    @Test fun latexExportWithMath() {
        val doc = OdfDocument.TextDocument("D", listOf(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("Heading")), style = ParagraphStyle.HEADING1)),
            OdfContentBlock.Formula("<math xmlns=\"http://www.w3.org/1998/Math/MathML\"><mfrac><mrow><mi>x</mi></mrow><mrow><mn>2</mn></mrow></mfrac></math>")
        ))
        val tex = LatexExporter.export(doc)
        assertTrue(tex.contains("\\documentclass"))
        assertTrue(tex.contains("\\section{Heading}"))
        assertTrue(tex.contains("\\frac{x}{2}"))
    }

    @Test fun pdfExportIsValid() {
        val bytes = PdfExporter.export(textDoc())
        val s = String(bytes, Charsets.ISO_8859_1)
        assertTrue(s.startsWith("%PDF-1."))
        assertTrue(s.contains("/Type /Catalog"))
        assertTrue(s.contains("endobj"))
        assertTrue(s.contains("%%EOF"))
        assertTrue(s.contains("(Title)"))
    }

    @Test fun odfListStyleExported() {
        val xml = OdfSerializer.serialize(textDoc())
        assertTrue(xml.contains("<text:list-style"))
        assertTrue(xml.contains("text:list-level-style-number"))
        assertTrue(xml.contains("style:num-format=\"1\""))
        assertTrue(xml.contains("text:style-name=\"L1\""))
    }
}
