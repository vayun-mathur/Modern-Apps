package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LaTeX export details: tables, run formatting, lists, formula blocks and the
 * MathML-to-LaTeX converter. Slice-1 coverage for library/ui (odf package).
 */
class MathmlLatexSlice1Test {

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)), style = style))

    private fun docOf(vararg blocks: OdfContentBlock, title: String = "T") =
        OdfDocument.TextDocument(title = title, content = blocks.toList())

    @Test
    fun `a table becomes a tabular`() {
        val tex = LatexExporter.export(
            docOf(
                OdfContentBlock.Table(
                    OdfTable(
                        rows = listOf(
                            OdfTableRow(
                                listOf(
                                    OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("a"))))),
                                    OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("b"))))),
                                )
                            ),
                            OdfTableRow(
                                listOf(
                                    OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("c"))))),
                                    OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("d"))))),
                                )
                            ),
                        )
                    )
                )
            )
        )
        assertTrue(tex.contains("\\begin{tabular}"), tex.take(800))
        assertTrue(tex.contains("a & b"), tex)
        assertTrue(tex.contains("c & d"), tex)
        assertTrue(tex.contains("\\end{tabular}"), tex)
    }

    @Test
    fun `an empty table exports to nothing`() {
        val tex = LatexExporter.export(docOf(OdfContentBlock.Table(OdfTable(rows = emptyList()))))
        assertTrue(!tex.contains("\\begin{tabular}"), tex)
    }

    @Test
    fun `run formatting maps to latex commands`() {
        val tex = LatexExporter.export(
            docOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(
                            OdfSpan("b", bold = true),
                            OdfSpan("i", italic = true),
                            OdfSpan("u", underline = true),
                            OdfSpan("s", strikethrough = true),
                            OdfSpan("sup", superscript = true),
                            OdfSpan("sub", subscript = true),
                            OdfSpan("link", href = "https://x.test"),
                        )
                    )
                )
            )
        )
        assertTrue(tex.contains("\\textbf{b}"), tex)
        assertTrue(tex.contains("\\emph{i}"), tex)
        assertTrue(tex.contains("\\underline{u}"), tex)
        assertTrue(tex.contains("\\sout{s}"), tex)
        assertTrue(tex.contains("\\textsuperscript{sup}"), tex)
        assertTrue(tex.contains("\\textsubscript{sub}"), tex)
        assertTrue(tex.contains("\\href{https://x.test}{link}"), tex)
    }

    @Test
    fun `lists become itemize and enumerate`() {
        fun item(text: String, type: ListType) = OdfContentBlock.Paragraph(
            OdfParagraph(
                listOf(OdfSpan(text)),
                style = ParagraphStyle.LIST_ITEM,
                listLevel = 1,
                listType = type,
            )
        )
        val tex = LatexExporter.export(docOf(item("a", ListType.BULLET), item("b", ListType.BULLET)))
        assertTrue(tex.contains("\\begin{itemize}"), tex)
        assertTrue(tex.contains("\\item a"), tex)
        assertTrue(tex.contains("\\end{itemize}"), tex)
        val tex2 = LatexExporter.export(docOf(item("one", ListType.NUMBERED)))
        assertTrue(tex2.contains("\\begin{enumerate}"), tex2)
        assertTrue(tex2.contains("\\item one"), tex2)
        assertTrue(tex2.contains("\\end{enumerate}"), tex2)
    }

    @Test
    fun `headings map to sectioning commands`() {
        val tex = LatexExporter.export(
            docOf(
                para("H2", ParagraphStyle.HEADING2),
                para("H3", ParagraphStyle.HEADING3),
                para("H4", ParagraphStyle.HEADING4),
            )
        )
        assertTrue(tex.contains("\\subsection{H2}"), tex)
        assertTrue(tex.contains("\\subsubsection{H3}"), tex)
        assertTrue(tex.contains("\\paragraph{H4}"), tex)
    }

    @Test
    fun `a formula block becomes display math`() {
        val mathml = "<math><mrow><mi>x</mi><mo>+</mo><mn>1</mn></mrow></math>"
        val tex = LatexExporter.export(docOf(OdfContentBlock.Formula(mathml)))
        assertTrue(tex.contains("\\["), tex)
        assertTrue(tex.contains("x"), tex)
        assertTrue(tex.contains("\\]"), tex)
    }

    @Test
    fun `a table of contents becomes tableofcontents`() {
        val tex = LatexExporter.export(docOf(OdfContentBlock.TableOfContents("Contents", emptyList())))
        assertTrue(tex.contains("\\tableofcontents"), tex)
    }

    @Test
    fun `a page break becomes newpage`() {
        val tex = LatexExporter.export(docOf(para("a"), OdfContentBlock.PageBreak, para("b")))
        assertTrue(tex.contains("\\newpage"), tex)
    }

    @Test
    fun `fractions and roots convert`() {
        val mathml = "<math><mfrac><mn>1</mn><mn>2</mn></mfrac></math>"
        assertEquals("\\frac{1}{2}", MathmlToLatex.convert(mathml))
        val sqrt = "<math><msqrt><mi>x</mi></msqrt></math>"
        assertEquals("\\sqrt{x}", MathmlToLatex.convert(sqrt))
    }

    @Test
    fun `superscripts subscripts and operators convert`() {
        val sup = "<math><msup><mi>x</mi><mn>2</mn></msup></math>"
        assertEquals("{x}^{2}", MathmlToLatex.convert(sup))
        val sub = "<math><msub><mi>x</mi><mn>i</mn></msub></math>"
        assertEquals("{x}_{i}", MathmlToLatex.convert(sub))
        // ∑ (U+2211) maps to \sum.
        val sum = "<math><mo>∑</mo></math>"
        assertEquals("\\sum", MathmlToLatex.convert(sum))
        // ± (U+00B1) maps to \pm.
        val pm = "<math><mo>±</mo></math>"
        assertEquals("\\pm", MathmlToLatex.convert(pm))
    }

    @Test
    fun `fenced text matrices and roots convert`() {
        val fenced = "<math><mfenced><mi>a</mi></mfenced></math>"
        assertTrue(MathmlToLatex.convert(fenced).contains("\\left("), MathmlToLatex.convert(fenced))
        val mroot = "<math><mroot><mi>x</mi><mn>3</mn></mroot></math>"
        assertEquals("\\sqrt[3]{x}", MathmlToLatex.convert(mroot))
        val munder = "<math><munder><mo>∑</mo><mi>i</mi></munder></math>"
        assertEquals("\\sum_{i}", MathmlToLatex.convert(munder))
        val mover = "<math><mover><mi>x</mi><mo>→</mo></mover></math>"
        assertEquals("x^{\\to}", MathmlToLatex.convert(mover))
    }

    @Test
    fun `mtext wraps in text and unknown operators pass through`() {
        val t = "<math><mtext>hello</mtext></math>"
        assertEquals("\\text{hello}", MathmlToLatex.convert(t))
        val plain = "<math><mo>+</mo></math>"
        assertEquals("+", MathmlToLatex.convert(plain))
    }

    @Test
    fun `garbage mathml converts to empty`() {
        assertEquals("", MathmlToLatex.convert("not xml at all <<<"))
        assertEquals("", MathmlToLatex.convert(""))
    }

    @Test
    fun `greek letters map to commands`() {
        // α (U+03B1) maps to \alpha.
        val alpha = "<math><mi>α</mi></math>"
        assertEquals("α", MathmlToLatex.convert(alpha))
        val pi = "<math><mo>π</mo></math>"
        assertEquals("\\pi", MathmlToLatex.convert(pi))
    }
}
