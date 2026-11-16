package com.vayunmathur.library.ui.odf

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W4: converter deep branches — rich RTF, MathML→LaTeX through the LaTeX
 * exporter, HTML parser tag coverage, Markdown edge forms, and the
 * continuous-editor transformed-text builder.
 */
class W4ConvertersDeepTest {

    private fun textOf(doc: OdfDocument.TextDocument): String =
        doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .joinToString("\n") { it.paragraph.spans.joinToString("") { s -> s.text } }

    // ---- RTF ----

    @Test
    fun `rtf with tables colors unicode and escapes`() {
        val rtf = """{\rtf1\ansi\ansicpg1252\deff0
{\fonttbl{\f0 Arial;}{\f1 Courier New;}}
{\colortbl;\red255\green0\blue0;\red0\green0\blue255;}
\pard\plain\f0\fs24\b Bold start\b0  plain \cf1 red\cf2\ blue\cf0
\par Second paragraph with unicode caf\u233? and hex \'e9 and \{braces\}
\par\pard{\*\pn\pnlvlblt\pnf1} \bullet item one\par item two
\trowd\cellx2000\cellx4000\intbl cellA\cell cellB\cell\row
\pard after table}"""
        val doc = RtfOdfConverter.rtfToOdf(rtf, "RtfDoc")
        val text = textOf(doc)
        assertTrue(text.contains("Bold start"), text)
        assertTrue(text.contains("Second paragraph"), text)
        assertTrue(text.contains("cellA") || text.contains("cellB") || doc.content.any { it is OdfContentBlock.Table }, text)
        val spans = doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .flatMap { it.paragraph.spans }
        assertTrue(spans.any { it.bold }, spans.toString())
    }

    @Test
    fun `rtf malformed input still yields a document`() {
        val doc = RtfOdfConverter.rtfToOdf("{\\rtf1 incomplete \\b bold no close", "Bad")
        assertTrue(textOf(doc).contains("bold"), textOf(doc))
        assertEquals("", textOf(RtfOdfConverter.rtfToOdf("", "Empty")).trim())
    }

    // ---- LaTeX / MathML ----

    @Test
    fun `latex export renders rich math and document structure`() {
        val doc = OdfDocument.TextDocument(
            title = "Tex",
            content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(
                    listOf(OdfSpan("Intro")), style = ParagraphStyle.HEADING1)),
                OdfContentBlock.Formula(
                    "<math><mfrac><mi>a</mi><mi>b</mi></mfrac><mo>+</mo>" +
                        "<msqrt><mi>x</mi></msqrt><mo>+</mo>" +
                        "<msup><mi>e</mi><mi>i</mi></msup><mo>+</mo>" +
                        "<munderover><mo>∑</mo><mn>1</mn><mi>n</mi></munderover></math>"),
                OdfContentBlock.Table(OdfTable(
                    rows = listOf(OdfTableRow(listOf(
                        OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("c1"))))),
                        OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("c2"))))),
                    ))),
                )),
                OdfContentBlock.Paragraph(OdfParagraph(
                    listOf(OdfSpan("item")), style = ParagraphStyle.LIST_ITEM,
                    listType = ListType.NUMBERED)),
            ),
        )
        val tex = LatexExporter.export(doc)
        assertTrue(tex.contains("\\frac"), tex.take(1500))
        assertTrue(tex.contains("\\sqrt"), tex.take(1500))
        assertTrue(tex.contains("c1"), tex.take(1500))
        assertTrue(tex.contains("Intro"), tex.take(1500))
    }

    // ---- HTML import ----

    @Test
    fun `html parser handles tables lists quotes and code`() {
        val html = """<html><body>
            <h1>Title</h1><p>para with <b>bold</b> <i>ital</i> <a href="https://x.test">link</a> <code>code()</code></p>
            <blockquote>quoted text</blockquote>
            <ul><li>first</li><li>second</li></ul>
            <ol><li>one</li><li>two</li></ol>
            <pre>pre  formatted</pre>
            <table><tr><th>H1</th><th>H2</th></tr><tr><td>a</td><td>b</td></tr></table>
            <hr><p>after rule</p>
            </body></html>"""
        val doc = HtmlOdfConverter.htmlToOdf(html, "HtmlDoc")
        val text = textOf(doc)
        for (marker in listOf("Title", "bold", "quoted text", "first", "one", "after rule")) {
            assertTrue(text.contains(marker), marker + " missing in " + text.take(600))
        }
        assertTrue(doc.content.any { it is OdfContentBlock.Table }, doc.content.toString())
        val spans = doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .flatMap { it.paragraph.spans }
        assertTrue(spans.any { it.href == "https://x.test" }, spans.toString())
    }

    // ---- Markdown ----

    @Test
    fun `markdown edge forms convert both ways`() {
        val md = """
            # Heading
            > a quote
            > second quote line
            ```kotlin
            val x = 1
            ```
            - [x] done task
            - [ ] open task
            1. first
            2. second
            ---
            Text with ~~strike~~ and `code` and [label](https://y.test)
        """.trimIndent()
        val doc = MarkdownOdfConverter.markdownToOdf(md, "Md")
        val text = textOf(doc)
        assertTrue(text.contains("Heading"), text)
        assertTrue(text.contains("done task"), text)
        assertTrue(text.contains("val x = 1"), text)
        val back = MarkdownOdfConverter.odfToMarkdown(doc)
        assertTrue(back.contains("# Heading") || back.contains("Heading"), back.take(600))
        assertTrue(back.contains("[x]") || back.contains("done task"), back.take(800))
    }

    // ---- buildDocTransformed ----

    @Test
    fun `transformed text inserts prefixes and zero width boundaries`() {
        val paras = listOf(
            OdfParagraph(listOf(OdfSpan("one"))),
            OdfParagraph(listOf(OdfSpan("two")), alignment = androidx.compose.ui.text.style.TextAlign.Center),
            OdfParagraph(listOf(OdfSpan(""))),
            OdfParagraph(listOf(OdfSpan("three"))),
        )
        val text = paras.joinToString("\n") { it.spans.joinToString("") { s -> s.text } }
        val lens = paras.map { it.spans.sumOf { s -> s.text.length } }
        val transformed = buildDocTransformed(
            text, paras, lens, listOf("1. ", "2. ", "", ""), Color.Gray, 1f)
        val out = transformed.text.text
        assertTrue(out.contains("1. one"), out)
        assertTrue(out.contains("2. two"), out)
        // Offset mapping round-trips a caret inside the second paragraph.
        val original = transformed.offsetMapping.transformedToOriginal(out.indexOf("two") + 1)
        assertTrue(original in 4..6, "original=$original out=$out")
        assertNotNull(transformed.offsetMapping)
    }
}
