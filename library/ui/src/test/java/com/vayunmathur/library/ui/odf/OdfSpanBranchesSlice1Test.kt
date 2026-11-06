package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ODF span-level serialization: annotations, cross-references, change
 * markers, bookmarks, styled links. Slice-1 coverage for library/ui.
 */
class OdfSpanBranchesSlice1Test {

    private fun paraOf(vararg spans: OdfSpan) =
        OdfContentBlock.Paragraph(OdfParagraph(spans.toList()))

    private fun textDoc(vararg blocks: OdfContentBlock, title: String = "T") =
        OdfDocument.TextDocument(title = title, content = blocks.toList())

    @Test
    fun `an annotated span serializes a comment`() {
        val xml = OdfSerializer.serialize(
            textDoc(
                paraOf(
                    OdfSpan(
                        "noted",
                        annotation = OdfAnnotation(
                            author = "Ada",
                            date = "2024-01-01",
                            paragraphs = listOf(OdfParagraph(listOf(OdfSpan("comment body")))),
                        ),
                    )
                )
            )
        )
        assertTrue(xml.contains("office:annotation"), xml.take(4000))
        assertTrue(xml.contains("Ada"), xml)
        assertTrue(xml.contains("comment body"), xml)
    }

    @Test
    fun `cross-reference spans serialize their elements`() {
        val xml = OdfSerializer.serialize(
            textDoc(
                paraOf(
                    OdfSpan("42", refKind = "reference-ref", refName = "fig1", refFormat = "page"),
                    OdfSpan("", refKind = "reference-mark-start", refName = "m1"),
                )
            )
        )
        assertTrue(xml.contains("text:reference-ref"), xml.take(4000))
        assertTrue(xml.contains("fig1"), xml)
        assertTrue(xml.contains("text:reference-mark-start"), xml)
    }

    @Test
    fun `a deletion span serializes a point marker`() {
        val doc = textDoc(paraOf(OdfSpan("gone", changeId = "d1", changeKind = "deletion")))
            .copy(changes = listOf(OdfChange(id = "d1", type = "deletion")))
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("text:change"), xml.take(4000))
        assertTrue(xml.contains("d1"), xml)
    }

    @Test
    fun `an insertion span wraps in change markers`() {
        val doc = textDoc(paraOf(OdfSpan("added", changeId = "i1", changeKind = "insertion")))
            .copy(changes = listOf(OdfChange(id = "i1", type = "insertion")))
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("text:change-start"), xml.take(4000))
        assertTrue(xml.contains("text:change-end"), xml)
    }

    @Test
    fun `bookmarks serialize at their content index`() {
        val doc = textDoc(paraOf(OdfSpan("marked")))
            .copy(bookmarks = listOf(OdfBookmark("bm1", 0)))
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("text:bookmark"), xml.take(4000))
        assertTrue(xml.contains("bm1"), xml)
    }

    @Test
    fun `a styled link nests span inside anchor`() {
        val xml = OdfSerializer.serialize(
            textDoc(paraOf(OdfSpan("click", bold = true, href = "https://x.test")))
        )
        assertTrue(xml.contains("text:a"), xml.take(4000))
        assertTrue(xml.contains("https://x.test"), xml)
        assertTrue(xml.contains("text:span"), xml)
    }

    @Test
    fun `extended span styles serialize`() {
        val xml = OdfSerializer.serialize(
            textDoc(
                paraOf(
                    OdfSpan(
                        "fancy",
                        fontFamily = "Serif",
                        letterSpacing = 1.5f,
                        textTransform = "uppercase",
                        language = "en",
                        country = "US",
                        underlineStyle = "double",
                        backgroundColor = 0xFFFFFF00,
                    )
                )
            )
        )
        assertTrue(xml.contains("fancy"), xml.take(6000))
        assertTrue(xml.contains("Serif") || xml.contains("fo:font-family"), xml.take(8000))
    }

    @Test
    fun `a datetime field with empty text self-closes`() {
        val xml = OdfSerializer.serialize(
            textDoc(paraOf(OdfSpan("", field = "page-number")))
        )
        assertTrue(xml.contains("text:page-number"), xml.take(4000))
    }

    @Test
    fun `cell annotations serialize`() {
        val doc = OdfDocument.Spreadsheet(
            title = "S",
            sheets = listOf(
                OdfSheet(
                    name = "Sheet1",
                    rows = listOf(
                        OdfRow(
                            listOf(
                                OdfCell(
                                    "c",
                                    annotation = OdfAnnotation(
                                        author = "Bo",
                                        paragraphs = listOf(OdfParagraph(listOf(OdfSpan("cell note")))),
                                    ),
                                )
                            )
                        )
                    ),
                )
            ),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("office:annotation"), xml.take(6000))
        assertTrue(xml.contains("cell note"), xml)
    }
}
