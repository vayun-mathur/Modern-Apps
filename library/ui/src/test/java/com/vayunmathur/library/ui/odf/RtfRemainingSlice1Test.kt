package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RTF conversion remainder: non-paragraph blocks, literal escapes, extra
 * control words and skipped destination groups. Slice-1 coverage for
 * library/ui (odf package).
 */
class RtfRemainingSlice1Test {

    private fun para(text: String) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text))))

    private fun docOf(vararg blocks: OdfContentBlock) =
        OdfDocument.TextDocument(title = "t", content = blocks.toList())

    private fun texts(doc: OdfDocument.TextDocument): List<String> =
        doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .map { it.paragraph.spans.joinToString("") { s -> s.text } }

    @Test
    fun `export covers tables toc entries and formulas`() {
        val rtf = RtfOdfConverter.odfToRtf(
            docOf(
                OdfContentBlock.Table(
                    OdfTable(
                        rows = listOf(
                            OdfTableRow(
                                listOf(
                                    OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("cell")))))
                                )
                            )
                        )
                    )
                ),
                OdfContentBlock.TableOfContents(
                    "C",
                    listOf(OdfParagraph(listOf(OdfSpan("entry")))),
                ),
                OdfContentBlock.Formula("<math>e</math>"),
            )
        )
        assertTrue(rtf.contains("cell"), rtf)
        assertTrue(rtf.contains("entry"), rtf)
        assertTrue(rtf.contains("math"), rtf)
    }

    @Test
    fun `export escapes literal braces and backslash`() {
        val rtf = RtfOdfConverter.odfToRtf(docOf(para("a{b}c\\d")))
        assertTrue(rtf.contains("\\{"), rtf)
        assertTrue(rtf.contains("\\}"), rtf)
        assertTrue(rtf.contains("\\\\"), rtf)
        // And they come back through the importer.
        val back = RtfOdfConverter.rtfToOdf(rtf)
        assertTrue(texts(back).any { it.contains("a{b}c\\d") }, texts(back).toString())
    }

    @Test
    fun `export escapes newlines and tabs in runs`() {
        val rtf = RtfOdfConverter.odfToRtf(docOf(para("a\nb\tc")))
        assertTrue(rtf.contains("\\line"), rtf)
        assertTrue(rtf.contains("\\tab"), rtf)
    }

    @Test
    fun `import reads ql and pard resets alignment`() {
        val doc = RtfOdfConverter.rtfToOdf("{\\rtf1\\ql left\\par\\qc center\\par\\pard plain\\par}")
        val paras = doc.content.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph }
        assertEquals(androidx.compose.ui.text.style.TextAlign.Start, paras[0].alignment)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, paras[1].alignment)
    }

    @Test
    fun `import handles ulnone and nosupersub`() {
        val doc = RtfOdfConverter.rtfToOdf("{\\rtf1{\\ul u}\\ulnone plain{\\super x}\\nosupersub y\\par}")
        val spans = (doc.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.any { it.underline && it.text == "u" }, spans.toString())
        assertTrue(spans.any { it.superscript && it.text == "x" }, spans.toString())
        assertTrue(
            spans.any { it.text.contains("plain") && !it.underline },
            spans.toString(),
        )
        assertTrue(
            spans.any { it.text.contains("y") && !it.superscript && !it.subscript },
            spans.toString(),
        )
    }

    @Test
    fun `import skips destination groups`() {
        val doc = RtfOdfConverter.rtfToOdf(
            "{\\rtf1{\\pict skipped-bytes}kept{\\*\\generator hidden}tail\\par}"
        )
        assertTrue(texts(doc).any { it.contains("kept") }, texts(doc).toString())
        assertTrue(texts(doc).any { it.contains("tail") }, texts(doc).toString())
        assertTrue(texts(doc).none { it.contains("skipped") }, texts(doc).toString())
        assertTrue(texts(doc).none { it.contains("hidden") }, texts(doc).toString())
    }

    @Test
    fun `import reads sect as a paragraph break`() {
        val doc = RtfOdfConverter.rtfToOdf("{\\rtf1 one\\sect two\\par}")
        assertTrue(texts(doc).any { it.contains("one") }, texts(doc).toString())
        assertTrue(texts(doc).any { it.contains("two") }, texts(doc).toString())
    }

    @Test
    fun `an unchecked checkbox exports the empty box`() {
        val rtf = RtfOdfConverter.odfToRtf(
            docOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(OdfSpan("t")),
                        style = ParagraphStyle.LIST_ITEM,
                        listType = ListType.CHECKBOX,
                        listChecked = false,
                    )
                )
            )
        )
        assertTrue(rtf.contains("\\u9744"), rtf)
    }

    @Test
    fun `a header row marker does not break export`() {
        val rtf = RtfOdfConverter.odfToRtf(
            docOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(OdfSpan("H")),
                        style = ParagraphStyle.HEADING2,
                    )
                ),
            )
        )
        assertTrue(rtf.contains("H"), rtf)
        assertTrue(rtf.contains("\\fs32"), rtf)
    }
}
