package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RTF <-> ODF conversion: run formatting, alignment, lists and import tolerance.
 * Slice-1 coverage for library/ui (odf package).
 */
class RtfOdfConverterSlice1Test {

    private fun para(text: String) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text))))

    private fun docOf(vararg blocks: OdfContentBlock) =
        OdfDocument.TextDocument(title = "t", content = blocks.toList())

    @Test
    fun `export emits an rtf shell with a font table`() {
        val rtf = RtfOdfConverter.odfToRtf(docOf(para("hi")))
        assertTrue(rtf.startsWith("{\\rtf1"), rtf)
        assertTrue(rtf.contains("{\\fonttbl"), rtf)
        assertTrue(rtf.contains("hi"), rtf)
    }

    @Test
    fun `bold spans emit bold controls`() {
        val rtf = RtfOdfConverter.odfToRtf(
            docOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("b", bold = true))))
            )
        )
        assertTrue(rtf.contains("\\b"), rtf)
    }

    @Test
    fun `centered paragraphs emit the alignment control`() {
        val rtf = RtfOdfConverter.odfToRtf(
            docOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(OdfSpan("c")),
                        alignment = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                )
            )
        )
        assertTrue(rtf.contains("\\qc"), rtf)
    }

    @Test
    fun `a coloured span extends the colour table`() {
        val rtf = RtfOdfConverter.odfToRtf(
            docOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("r", color = 0xFF0000))))
            )
        )
        assertTrue(rtf.contains("\\red255\\green0\\blue0;"), rtf)
    }

    @Test
    fun `import reads plain paragraphs`() {
        val doc = RtfOdfConverter.rtfToOdf("{\\rtf1\\ansi Hello\\par World\\par}")
        val texts = doc.content.map {
            (it as OdfContentBlock.Paragraph).paragraph.spans.joinToString("") { s -> s.text }
        }
        assertTrue(texts.any { it.contains("Hello") }, texts.toString())
        assertTrue(texts.any { it.contains("World") }, texts.toString())
    }

    @Test
    fun `import keeps bold runs`() {
        val doc = RtfOdfConverter.rtfToOdf("{\\rtf1\\ansi {\\b bold}\\par}")
        val spans = (doc.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.any { it.bold && it.text.contains("bold") }, spans.toString())
    }

    @Test
    fun `a heading round-trips through rtf`() {
        val doc = docOf(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("Title")), ParagraphStyle.HEADING1)),
        )
        val back = RtfOdfConverter.rtfToOdf(RtfOdfConverter.odfToRtf(doc))
        assertTrue(back.content.any {
            (it as? OdfContentBlock.Paragraph)?.paragraph?.spans?.joinToString("") { s -> s.text } == "Title"
        })
    }
}
