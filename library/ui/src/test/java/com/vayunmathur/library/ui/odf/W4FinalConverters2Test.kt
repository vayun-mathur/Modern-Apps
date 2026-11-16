package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W4 (conversions, round 2): RTF span-level formatting (fonts, colours,
 * super/subscript, tables), the full MathML→LaTeX node set through the
 * exporter, run-edit edge cases (insert-at-start template, trailing
 * newline, multi-span styles), and PDF export of a styled document.
 */
class W4FinalConverters2Test {

    private fun paras(doc: OdfDocument.TextDocument) =
        doc.content.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph }

    private fun allSpans(doc: OdfDocument.TextDocument) = paras(doc).flatMap { it.spans }

    // ---- RTF span formatting ----

    @Test
    fun `rtf fonts colors and character formatting map to spans`() {
        val rtf = """{\rtf1\ansi{\fonttbl{\f0 Times;}{\f1\fmodern Courier;}}
{\colortbl;\red10\green20\blue30;\red200\green0\blue0;}
\pard\f0\fs36 Big \f1\fs20 small\par
\pard\cf1 dark \cf2 bright \b bold\b0 \i ital\i0 \ul under\ulnone \strike struck\strike0\par
\pard base\super sup\nosupersub normal\sub sub\par
\pard tab\tab after line\line break}"""
        val doc = RtfOdfConverter.rtfToOdf(rtf, "Fmt")
        val spans = allSpans(doc)
        assertTrue(spans.any { it.bold }, spans.toString())
        assertTrue(spans.any { it.italic }, spans.toString())
        assertTrue(spans.any { it.underline }, spans.toString())
        assertTrue(spans.any { it.strikethrough }, spans.toString())
        assertTrue(spans.any { it.superscript }, spans.toString())
        assertTrue(spans.any { it.subscript }, spans.toString())
        assertTrue(spans.any { it.fontSize != null }, spans.toString())
        assertTrue(spans.any { it.color != null }, spans.toString())
    }

    @Test
    fun `rtf table rows become a table block`() {
        val rtf = """{\rtf1\ansi
\trowd\trgaph100\cellx2000\cellx4000
\pard\intbl A1\cell A2\cell\row
\trowd\trgaph100\cellx2000\cellx4000
\pard\intbl B1\cell B2\cell\row
\pard outside}"""
        val doc = RtfOdfConverter.rtfToOdf(rtf, "Tbl")
        // Row/cell markers parse in order and the trailing paragraph survives.
        val text = paras(doc).joinToString("") { it.spans.joinToString("") { s -> s.text } }
        assertTrue(text.contains("A1"), text)
        assertTrue(text.contains("B2"), text)
        assertTrue(text.contains("outside"), text)
    }

    // ---- MathML -> LaTeX ----

    @Test
    fun `latex renders every math node kind`() {
        val mathml = "<math>" +
            "<msubsup><mi>a</mi><mn>1</mn><mn>2</mn></msubsup>" +
            "<mroot><mi>x</mi><mn>3</mn></mroot>" +
            "<mfenced open=\"[\" close=\"]\" separators=\",\"><mi>v</mi></mfenced>" +
            "<munder><mo>lim</mo><mi>n</mi></munder>" +
            "<mover><mi>x</mi><mo>^</mo></mover>" +
            "<mtable><mtr><mtd><mn>1</mn></mtd><mtd><mn>2</mn></mtd></mtr></mtable>" +
            "<mmultiscripts><mi>P</mi><mn>1</mn><mn>2</mn></mmultiscripts>" +
            "<mstyle displaystyle=\"true\"><mi>s</mi></mstyle>" +
            "</math>"
        val doc = OdfDocument.TextDocument(
            "M", listOf(OdfContentBlock.Formula(mathml)))
        val tex = LatexExporter.export(doc)
        for (marker in listOf("a", "x", "v", "lim", "P", "s")) {
            assertTrue(tex.contains(marker), marker + " missing: " + tex.take(800))
        }
        assertTrue(tex.contains("\\["), tex.take(400))
    }

    // ---- run-edit edges ----

    private fun docOf(vararg paras: OdfParagraph): OdfDocument.TextDocument =
        OdfDocument.TextDocument("T", paras.map { OdfContentBlock.Paragraph(it) })

    private fun texts(doc: OdfDocument.TextDocument): List<String> =
        paras(doc).map { it.spans.joinToString("") { s -> s.text } }

    @Test
    fun `run update inserting at the very start uses following style`() {
        val doc = docOf(
            OdfParagraph(listOf(OdfSpan("bold start", bold = true))),
            OdfParagraph(listOf(OdfSpan("second"))),
        )
        val out = doc.updateParagraphRun(0, 1, "Xbold start\nsecond")
        assertNotNull(out)
        assertEquals(listOf("Xbold start", "second"), texts(out))
        // The inserted char adopts the following span's formatting.
        assertTrue(paras(out)[0].spans.first().bold)
    }

    @Test
    fun `run update with trailing newline adds an empty paragraph`() {
        val doc = docOf(OdfParagraph(listOf(OdfSpan("line"))))
        val out = doc.updateParagraphRun(0, 0, "line\n")
        assertNotNull(out)
        assertEquals(listOf("line", ""), texts(out))
    }

    @Test
    fun `run update across a non paragraph block is a no-op`() {
        val doc = OdfDocument.TextDocument(
            "T",
            listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("a")))),
                OdfContentBlock.PageBreak,
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("b")))),
            ))
        assertNull(doc.updateParagraphRun(0, 2, "x"))
        assertNull(doc.updateParagraphText(1, "x"))
        assertNull(doc.applyRunSpanStyle(0, 2, 0, 1) { it.copy(bold = true) })
    }

    @Test
    fun `paragraph text update deletes and appends`() {
        val doc = docOf(OdfParagraph(listOf(OdfSpan("keep tail"))))
        assertEquals(listOf("tail"), texts(assertNotNull(doc.updateParagraphText(0, "tail"))))
        assertEquals(listOf("keep tail!"), texts(assertNotNull(doc.updateParagraphText(0, "keep tail!"))))
        assertEquals(listOf(""), texts(assertNotNull(doc.updateParagraphText(0, ""))))
        // Out-of-range indexes are no-ops.
        assertNull(doc.updateParagraphText(3, "x"))
        assertNull(doc.updateParagraphText(-1, "x"))
    }

    // ---- PDF export ----

    @Test
    fun `pdf export produces a document for styled text`() {
        val doc = OdfDocument.TextDocument(
            "PdfDoc",
            listOf(
                OdfContentBlock.Paragraph(OdfParagraph(
                    listOf(OdfSpan("Heading")), style = ParagraphStyle.HEADING1)),
                OdfContentBlock.Paragraph(OdfParagraph(listOf(
                    OdfSpan("normal "), OdfSpan("bold", bold = true), OdfSpan(" link", href = "https://x.test")))),
                OdfContentBlock.Paragraph(OdfParagraph(
                    listOf(OdfSpan("item one")), style = ParagraphStyle.LIST_ITEM,
                    listType = ListType.BULLET)),
                OdfContentBlock.Paragraph(OdfParagraph(
                    listOf(OdfSpan("item two")), style = ParagraphStyle.LIST_ITEM,
                    listType = ListType.NUMBERED, listItemIndex = 2)),
                OdfContentBlock.Table(OdfTable(rows = listOf(
                    OdfTableRow(listOf(
                        OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("pdfcell"))))),
                    )),
                ))),
            ),
        )
        val bytes = PdfExporter.export(doc)
        assertTrue(bytes.size > 100, "pdf too small: ${bytes.size}")
        assertEquals("%PDF", bytes.decodeToString(0, 4))
    }
}
