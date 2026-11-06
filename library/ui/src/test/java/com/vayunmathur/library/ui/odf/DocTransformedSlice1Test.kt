package com.vayunmathur.library.ui.odf

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's transformed-text builder: prefixes, styles, separators and the
 * resulting offset mapping. Slice-1 coverage for library/ui (odf package).
 */
class DocTransformedSlice1Test {

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfParagraph(listOf(OdfSpan(text)), style = style)

    @Test
    fun `plain paragraphs join with newlines`() {
        val paras = listOf(para("abc"), para("de"))
        val text = "abc\nde"
        val t = buildDocTransformed(text, paras, listOf(3, 2), listOf("", ""), Color.Gray, 1f)
        assertEquals("abc\nde", t.text.toString())
        // Offsets map straight through with no prefixes.
        assertEquals(0, t.offsetMapping.originalToTransformed(0))
        assertEquals(3, t.offsetMapping.originalToTransformed(3))
        assertEquals(4, t.offsetMapping.originalToTransformed(4))
        assertEquals(4, t.offsetMapping.transformedToOriginal(4))
    }

    @Test
    fun `injected prefixes shift the text right`() {
        val paras = listOf(
            OdfParagraph(
                listOf(OdfSpan("item")),
                style = ParagraphStyle.LIST_ITEM,
                listLevel = 1,
            ),
            para("plain"),
        )
        val text = "item\nplain"
        val t = buildDocTransformed(text, paras, listOf(4, 5), listOf("•  ", ""), Color.Gray, 1f)
        val shown = t.text.toString()
        assertTrue(shown.startsWith("•  item"), shown)
        // Original offset 0 (start of "item") reads after the 3-char prefix.
        assertEquals(3, t.offsetMapping.originalToTransformed(0))
        // Inside-prefix transformed offsets clamp back to the paragraph start.
        assertEquals(0, t.offsetMapping.transformedToOriginal(1))
        assertEquals(0, t.offsetMapping.transformedToOriginal(0))
    }

    @Test
    fun `a layout change uses a zero-width separator`() {
        val paras = listOf(
            para("left"),
            OdfParagraph(
                listOf(OdfSpan("centered")),
                alignment = androidx.compose.ui.text.style.TextAlign.Center,
            ),
        )
        val text = "left\ncentered"
        val t = buildDocTransformed(text, paras, listOf(4, 8), listOf("", ""), Color.Gray, 1f)
        val shown = t.text.toString()
        // No blank line: the boundary is a zero-width space, not '\n'.
        assertTrue(!shown.contains("\n"), shown.map { it.code }.toString())
        assertTrue(shown.contains("​"), "missing ZWSP in ${shown.map { it.code }}")
        // Both paragraphs still addressable through the mapping.
        assertEquals(0, t.offsetMapping.originalToTransformed(0))
        assertEquals(shown.length, t.offsetMapping.originalToTransformed(text.length))
    }

    @Test
    fun `headings get a larger styled size`() {
        val paras = listOf(
            OdfParagraph(listOf(OdfSpan("Title")), style = ParagraphStyle.HEADING1),
            para("body"),
        )
        val text = "Title\nbody"
        val t = buildDocTransformed(text, paras, listOf(5, 4), listOf("", ""), Color.Gray, 1f)
        assertTrue(t.text.toString().contains("Title"))
        // The heading span style covers the title range.
        val headingStyles = t.text.spanStyles.filter { it.start == 0 && it.end == 5 }
        assertTrue(headingStyles.isNotEmpty(), t.text.spanStyles.toString())
    }

    @Test
    fun `run formatting survives the transform`() {
        val paras = listOf(
            OdfParagraph(listOf(OdfSpan("b", bold = true), OdfSpan("plain"))),
        )
        val text = "bplain"
        val t = buildDocTransformed(text, paras, listOf(6), listOf(""), Color.Gray, 1f)
        val bold = t.text.spanStyles.filter { it.start == 0 && it.end == 1 }
        assertTrue(bold.isNotEmpty(), t.text.spanStyles.toString())
        assertEquals(
            androidx.compose.ui.text.font.FontWeight.Bold,
            bold.first().item.fontWeight,
        )
    }

    @Test
    fun `an empty paragraph group still maps offsets`() {
        val paras = listOf(para(""), para("after"))
        val text = "\nafter"
        val t = buildDocTransformed(text, paras, listOf(0, 5), listOf("", ""), Color.Gray, 1f)
        assertTrue(t.text.toString().contains("after"))
        assertEquals(t.text.length, t.offsetMapping.originalToTransformed(text.length))
        assertEquals(0, t.offsetMapping.transformedToOriginal(0))
    }

    @Test
    fun `tracked-change spans get decorations and colors`() {
        val paras = listOf(
            OdfParagraph(
                listOf(
                    OdfSpan("ins", changeKind = "insertion"),
                    OdfSpan("del", changeKind = "deletion"),
                )
            ),
        )
        val text = "insdel"
        val t = buildDocTransformed(text, paras, listOf(6), listOf(""), Color.Gray, 1f)
        // Both change spans carry a style over their ranges.
        assertTrue(t.text.spanStyles.any { it.start == 0 && it.end == 3 }, t.text.spanStyles.toString())
        assertTrue(t.text.spanStyles.any { it.start == 3 && it.end == 6 }, t.text.spanStyles.toString())
    }
}
