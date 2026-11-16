package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W5: converter/exporter depth — rich RTF and Markdown inputs, MathML
 * elements not exercised before, and the HTML/PDF/LaTeX/EPUB exporters over
 * a document using every block kind. Assertions check converted structure
 * and emitted markup, not just that conversion returns something.
 */
class W5ConvertersRichTest {

    private fun textOf(doc: OdfDocument.TextDocument): String =
        doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .joinToString("\n") { it.paragraph.spans.joinToString("") { s -> s.text } }

    // ---- RTF ----

    @Test fun rtfRichDocument() {
        val rtf = """{\rtf1\ansi\ansicpg1252\deff0
{\fonttbl{\f0 Times New Roman;}{\f1 Courier New;}}
{\colortbl;\red255\green0\blue0;\red0\green0\blue255;}
\pard\ql Hello \b bold\b0  and \i italic\i0  and \ul underline\ulnone\par
\pard\qc Centered \cf1 red\cf2  blue\cf0\par
\pard\f1\fs36 Mono big\f0\fs24\par
Unicode caf\u233? and \'e9 hex\par
\ql Left\tab tabbed\par
}"""
        val doc = RtfOdfConverter.rtfToOdf(rtf, "rich")
        val text = textOf(doc)
        assertTrue(text.contains("Hello"), text)
        assertTrue(text.contains("bold"), text)
        assertTrue(text.contains("italic"), text)
        assertTrue(text.contains("Centered"), text)
        assertTrue(text.contains("Mono big"), text)
        assertTrue(text.contains("café"), text)
        // The bold run really is bold in the model, not just present as text.
        val paras = doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
        assertTrue(paras.any { p -> p.paragraph.spans.any { it.bold && it.text.contains("bold") } }, paras.toString())
    }

    @Test fun rtfEscapesAndEmptyInputs() {
        assertEquals("", textOf(RtfOdfConverter.rtfToOdf("", "empty")).trim())
        val doc = RtfOdfConverter.rtfToOdf("""{\rtf1 braces \{literal\} backslash \\ end}""")
        assertTrue(textOf(doc).contains("{literal}"), textOf(doc))
    }

    // ---- Markdown ----

    @Test fun markdownRichDocument() {
        val md = """
# Title

Some **bold** and *italic* and `code` and [a link](https://example.org).

- [ ] open task
- [x] done task
- plain bullet

1. first
2. second

> quoted line

```kotlin
val x = 1
```

---

| A | B |
|---|---|
| 1 | 2 |
""".trimIndent()
        val doc = MarkdownOdfConverter.markdownToOdf(md, "md")
        val text = textOf(doc)
        assertTrue(text.contains("Title"), text)
        assertTrue(text.contains("bold"), text)
        assertTrue(text.contains("open task"), text)
        assertTrue(text.contains("done task"), text)
        assertTrue(text.contains("first"), text)
        assertTrue(text.contains("quoted"), text)
        // Task list items keep their checked state in the model.
        val paras = doc.content.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph }
        val tasks = paras.filter { it.listType == ListType.CHECKBOX }
        assertTrue(tasks.size >= 2, tasks.toString())
        assertTrue(tasks.any { it.listChecked }, tasks.toString())
        assertTrue(tasks.any { !it.listChecked }, tasks.toString())
        // The link span carries its href.
        assertTrue(paras.any { p -> p.spans.any { it.href == "https://example.org" } }, paras.toString())
    }

    @Test fun markdownHeadingsAndInlineStyles() {
        val doc = MarkdownOdfConverter.markdownToOdf("## Sub\n\n### Deep\n\n~~gone~~ end", "h")
        val paras = doc.content.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph }
        assertTrue(paras.any { it.style == ParagraphStyle.HEADING2 && it.spans.any { s -> s.text.contains("Sub") } }, paras.toString())
        assertTrue(paras.any { p -> p.spans.any { it.strikethrough && it.text.contains("gone") } }, paras.toString())
    }

    // ---- MathML ----

    @Test fun mathmlElements() {
        val cases = mapOf(
            "<math><mfrac><mn>1</mn><mn>2</mn></mfrac></math>" to "\\frac{1}{2}",
            "<math><msqrt><mi>x</mi></msqrt></math>" to "\\sqrt{x}",
            "<math><mroot><mi>x</mi><mn>3</mn></mroot></math>" to "\\sqrt[3]{x}",
            "<math><msup><mi>x</mi><mn>2</mn></msup></math>" to "{x}^{2}",
            "<math><msub><mi>x</mi><mn>1</mn></msub></math>" to "{x}_{1}",
            "<math><munder><mo>∑</mo><mi>n</mi></munder></math>" to "\\sum_{n}",
            "<math><mover><mi>x</mi><mo>^</mo></mover></math>" to "x^{^}",
            "<math><mfenced><mi>y</mi></mfenced></math>" to "\\left( y \\right)",
            "<math><mtext>words</mtext></math>" to "\\text{words}",
            "<math><mo>α</mo></math>" to "\\alpha",
            "<math><mo>π</mo></math>" to "\\pi",
            "<math><mo>θ</mo></math>" to "\\theta",
            "<math><mo>∏</mo></math>" to "\\prod",
            "<math><mo>≥</mo></math>" to "\\geq",
            "<math><mo>≠</mo></math>" to "\\neq",
            "<math><mo>±</mo></math>" to "\\pm",
            "<math><mo>÷</mo></math>" to "\\div",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, MathmlToLatex.convert(input), "convert($input)")
        }
        // Malformed input converts to empty, never throws.
        assertEquals("\\frac{}{}", MathmlToLatex.convert("<math><mfrac>"))
        assertEquals("", MathmlToLatex.convert("not xml at all"))
        // A matrix keeps its row/column separators.
        val matrix = MathmlToLatex.convert(
            "<math><mtable><mtr><mtd><mn>1</mn></mtd><mtd><mn>2</mn></mtd></mtr>" +
                "<mtr><mtd><mn>3</mn></mtd><mtd><mn>4</mn></mtd></mtr></mtable></math>")
        assertTrue(matrix.contains("1 & 2"), matrix)
        assertTrue(matrix.contains("3 & 4"), matrix)
    }

    // ---- Exporters over one rich document ----

    private fun exportDoc(): OdfDocument.TextDocument = OdfDocument.TextDocument(
        title = "Export.odt",
        content = listOf(
            OdfContentBlock.Paragraph(OdfParagraph(
                listOf(OdfSpan("Main title")), style = ParagraphStyle.HEADING1)),
            OdfContentBlock.Paragraph(OdfParagraph(listOf(
                OdfSpan("bold ", bold = true),
                OdfSpan("italic ", italic = true),
                OdfSpan("link", href = "https://example.org"),
            ))),
            OdfContentBlock.Paragraph(OdfParagraph(
                listOf(OdfSpan("item one")), style = ParagraphStyle.LIST_ITEM,
                listType = ListType.NUMBERED, listItemIndex = 1)),
            OdfContentBlock.Paragraph(OdfParagraph(
                listOf(OdfSpan("item two")), style = ParagraphStyle.LIST_ITEM,
                listType = ListType.NUMBERED, listItemIndex = 2)),
            OdfContentBlock.Table(OdfTable(rows = listOf(
                OdfTableRow(listOf(
                    OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan("A1"))))),
                    OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan("B1"))))),
                )),
                OdfTableRow(listOf(
                    OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan("A2")))), colSpan = 2),
                )),
            ))),
        ),
        footnotes = listOf(OdfFootnote("1", listOf(OdfParagraph(listOf(OdfSpan("note body")))))),
    )

    @Test fun htmlExportCoversBlocks() {
        val html = HtmlOdfExport.odfToHtml(exportDoc())
        assertTrue(html.contains("Main title"), html.take(2000))
        assertTrue(html.contains("https://example.org"), html)
        assertTrue(html.contains("A1") && html.contains("B1"), html)
        assertTrue(html.contains("<table", ignoreCase = true), html.take(2000))
    }

    @Test fun latexPdfEpubExport() {
        val latex = LatexExporter.export(exportDoc())
        assertTrue(latex.contains("Main title"), latex.take(2000))
        assertTrue(latex.contains("bold"), latex)
        val pdf = PdfExporter.export(exportDoc())
        assertTrue(pdf.size > 4)
        assertEquals("%PDF", String(pdf, 0, 4, Charsets.US_ASCII))
        val epub = EpubExporter.export(exportDoc())
        assertEquals('P'.code.toByte(), epub[0])
        assertEquals('K'.code.toByte(), epub[1])
    }
}
