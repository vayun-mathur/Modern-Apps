package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RTF conversion details: list markers, colors, sizes, escapes, unicode and
 * the importer's control words. Slice-1 coverage for library/ui (odf package).
 */
class RtfDetailsSlice1Test {

    private fun para(text: String) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text))))

    private fun docOf(vararg blocks: OdfContentBlock) =
        OdfDocument.TextDocument(title = "t", content = blocks.toList())

    private fun texts(doc: OdfDocument.TextDocument): List<String> =
        doc.content.map {
            (it as OdfContentBlock.Paragraph).paragraph.spans.joinToString("") { s -> s.text }
        }

    @Test
    fun `export covers all run decorations`() {
        val rtf = RtfOdfConverter.odfToRtf(
            docOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(
                            OdfSpan("i", italic = true),
                            OdfSpan("u", underline = true),
                            OdfSpan("s", strikethrough = true),
                            OdfSpan("sup", superscript = true),
                            OdfSpan("sub", subscript = true),
                            OdfSpan("big", fontSize = 18f),
                            OdfSpan("hl", backgroundColor = 0xFFFFFF00),
                        )
                    )
                )
            )
        )
        assertTrue(rtf.contains("\\i"), rtf)
        assertTrue(rtf.contains("\\ul"), rtf)
        assertTrue(rtf.contains("\\strike"), rtf)
        assertTrue(rtf.contains("\\super"), rtf)
        assertTrue(rtf.contains("\\sub"), rtf)
        assertTrue(rtf.contains("\\fs36"), rtf)
        assertTrue(rtf.contains("\\highlight"), rtf)
    }

    @Test
    fun `export escapes specials and non-ascii`() {
        val rtf = RtfOdfConverter.odfToRtf(docOf(para("a\\b{c}dée")))
        assertTrue(rtf.contains("\\\\"), rtf)
        assertTrue(rtf.contains("\\{"), rtf)
        assertTrue(rtf.contains("\\}"), rtf)
        // é (U+00E9) escapes as a unicode control.
        assertTrue(rtf.contains("\\u233?"), rtf)
    }

    @Test
    fun `export emits list markers per type`() {
        fun item(text: String, type: ListType, checked: Boolean = false, index: Int = 0) =
            OdfContentBlock.Paragraph(
                OdfParagraph(
                    listOf(OdfSpan(text)),
                    style = ParagraphStyle.LIST_ITEM,
                    listLevel = 1,
                    listType = type,
                    listChecked = checked,
                    listItemIndex = index,
                )
            )
        val rtf = RtfOdfConverter.odfToRtf(
            docOf(
                item("b", ListType.BULLET),
                item("n", ListType.NUMBERED, index = 3),
                item("c", ListType.CHECKBOX, checked = true),
            )
        )
        assertTrue(rtf.contains("\\bullet"), rtf)
        assertTrue(rtf.contains("3. "), rtf)
        // ☑ (U+2611) escapes as unicode.
        assertTrue(rtf.contains("\\u9745"), rtf)
    }

    @Test
    fun `export aligns right and justified`() {
        val rtf = RtfOdfConverter.odfToRtf(
            docOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(OdfSpan("r")),
                        alignment = androidx.compose.ui.text.style.TextAlign.Right,
                    )
                ),
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(OdfSpan("j")),
                        alignment = androidx.compose.ui.text.style.TextAlign.Justify,
                    )
                ),
            )
        )
        assertTrue(rtf.contains("\\qr"), rtf)
        assertTrue(rtf.contains("\\qj"), rtf)
    }

    @Test
    fun `import reads colors sizes and alignment`() {
        val doc = RtfOdfConverter.rtfToOdf(
            "{\\rtf1{\\colortbl;\\red255\\green0\\blue0;}\\qc\\cf1\\fs24 Red\\par}"
        )
        val first = (doc.content[0] as OdfContentBlock.Paragraph).paragraph
        assertTrue(first.spans.any { it.text.contains("Red") && it.color == 0xFFFF0000L }, first.spans.toString())
        assertTrue(first.spans.any { it.fontSize == 12f }, first.spans.toString())
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, first.alignment)
    }

    @Test
    fun `import reads underline strike super and sub`() {
        val doc = RtfOdfConverter.rtfToOdf("{\\rtf1{\\ul u}{\\strike s}{\\super x}{\\sub y}\\par}")
        val spans = (doc.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.any { it.underline && it.text == "u" }, spans.toString())
        assertTrue(spans.any { it.strikethrough && it.text == "s" }, spans.toString())
        assertTrue(spans.any { it.superscript && it.text == "x" }, spans.toString())
        assertTrue(spans.any { it.subscript && it.text == "y" }, spans.toString())
    }

    @Test
    fun `import skips font tables and decodes hex and unicode`() {
        val doc = RtfOdfConverter.rtfToOdf("{\\rtf1{\\fonttbl{\\f0 Arial;}}caf\\'e9 \\u233?\\par}")
        assertTrue(texts(doc).any { it.contains("café") }, texts(doc).toString())
    }

    @Test
    fun `import handles line tab nbsp and right alignment`() {
        val doc = RtfOdfConverter.rtfToOdf("{\\rtf1\\qr a\\line b\\tab c\\~d\\par}")
        val first = (doc.content[0] as OdfContentBlock.Paragraph).paragraph
        assertEquals(androidx.compose.ui.text.style.TextAlign.End, first.alignment)
        val text = first.spans.joinToString("") { it.text }
        assertTrue(text.contains("\n"), text.map { it.code }.toString())
        assertTrue(text.contains("\t"), text.map { it.code }.toString())
        assertTrue(text.contains(" "), text.map { it.code }.toString())
    }

    @Test
    fun `a styled document round-trips through rtf`() {
        val doc = docOf(
            OdfContentBlock.Paragraph(
                OdfParagraph(
                    listOf(OdfSpan("b", bold = true), OdfSpan("i", italic = true)),
                    alignment = androidx.compose.ui.text.style.TextAlign.Center,
                )
            ),
        )
        val back = RtfOdfConverter.rtfToOdf(RtfOdfConverter.odfToRtf(doc))
        val first = (back.content[0] as OdfContentBlock.Paragraph).paragraph
        assertTrue(first.spans.any { it.bold }, first.spans.toString())
        assertTrue(first.spans.any { it.italic }, first.spans.toString())
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, first.alignment)
    }
}
