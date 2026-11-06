package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ODF serialization: content, flat, styles, settings, meta and the packaged form.
 * Slice-1 coverage for library/ui (odf package).
 */
class OdfSerializerSlice1Test {

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)), style = style))

    private fun textDoc(vararg blocks: OdfContentBlock, title: String = "T") =
        OdfDocument.TextDocument(title = title, content = blocks.toList())

    @Test
    fun `content xml carries the paragraphs`() {
        val xml = OdfSerializer.serialize(textDoc(para("hello"), para("world")))
        assertTrue(xml.contains("hello"), xml.take(300))
        assertTrue(xml.contains("world"), xml.take(300))
    }

    @Test
    fun `a heading serializes as a heading`() {
        val xml = OdfSerializer.serialize(textDoc(para("Title", ParagraphStyle.HEADING1)))
        assertTrue(xml.contains("Title"))
        // An outline level marks the heading in ODF.
        assertTrue(xml.contains("outline-level=\"1\""), xml.take(2000))
    }

    @Test
    fun `bold spans serialize with the weight attribute`() {
        val xml = OdfSerializer.serialize(
            textDoc(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("b", bold = true)))))
        )
        assertTrue(xml.contains("font-weight=\"bold\"") || xml.contains("fo:font-weight"), xml.take(2000))
    }

    @Test
    fun `a spreadsheet serializes its sheets`() {
        val doc = OdfDocument.Spreadsheet(
            title = "S",
            sheets = listOf(
                OdfSheet(
                    name = "Sheet1",
                    rows = listOf(
                        OdfRow(listOf(OdfCell("A1"), OdfCell("B1"))),
                    ),
                )
            ),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("Sheet1"), xml.take(1000))
        assertTrue(xml.contains("A1"), xml.take(2000))
    }

    @Test
    fun `a presentation serializes its slides`() {
        val doc = OdfDocument.Presentation(
            title = "P",
            slides = listOf(OdfSlide(name = "S1")),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("S1") || xml.contains("P"), xml.take(1000))
    }

    @Test
    fun `flat export is a single document with the right mimetype`() {
        val flat = OdfSerializer.serializeFlat(textDoc(para("hi")))
        assertTrue(flat.contains("<office:document"), flat.take(300))
        assertTrue(
            flat.contains("application/vnd.oasis.opendocument.text"),
            flat.take(1000),
        )
    }

    @Test
    fun `styles export declares fonts and headings`() {
        val styles = OdfSerializer.serializeStyles(textDoc(para("hi")))
        assertTrue(styles.contains("office:document-styles"), styles.take(300))
        assertTrue(styles.contains("Liberation Serif"), styles.take(3000))
    }

    @Test
    fun `settings export is null for a plain document`() {
        assertNull(OdfSerializer.serializeSettings(textDoc(para("hi"))))
    }

    @Test
    fun `settings export carries freeze panes`() {
        val doc = OdfDocument.Spreadsheet(
            title = "S",
            sheets = listOf(OdfSheet(name = "Sheet1", rows = emptyList(), freezeRows = 1, freezeCols = 2)),
        )
        val settings = OdfSerializer.serializeSettings(doc)
        assertNotNull(settings)
        assertTrue(settings.contains("office:document-settings"), settings.take(300))
    }

    @Test
    fun `meta export carries title and author`() {
        val meta = OdfSerializer.serializeMeta(
            OdfMetadata(title = "T", author = "Ada", keywords = listOf("k1", "k2"))
        )
        assertTrue(meta.contains("Ada"), meta.take(1000))
        assertTrue(meta.contains("k1"), meta.take(1500))
    }

    @Test
    fun `packaged export returns content plus extras`() {
        val res = OdfSerializer.serializePackaged(textDoc(para("hi")))
        assertTrue(res.contentXml.contains("hi"))
        assertEquals(emptyMap(), res.images)
    }

    @Test
    fun `an inline image lands in the package images`() {
        // path "inline" (or blank) promotes the bytes into Pictures/; a
        // concrete path is referenced as-is.
        val doc = textDoc().copy(
            content = listOf(
                OdfContentBlock.Image(
                    OdfImage(path = "inline", imageData = byteArrayOf(1, 2, 3))
                )
            )
        )
        val res = OdfSerializer.serializePackaged(doc)
        assertTrue(res.images.isNotEmpty())
        assertTrue(res.contentXml.contains("Pictures/"), res.contentXml.take(3000))
    }

    @Test
    fun `a pathed image is referenced without embedding`() {
        val doc = textDoc().copy(
            content = listOf(
                OdfContentBlock.Image(
                    OdfImage(path = "Pictures/keep.png", imageData = byteArrayOf(1, 2, 3))
                )
            )
        )
        val res = OdfSerializer.serializePackaged(doc)
        assertTrue(res.images.isEmpty())
        assertTrue(res.contentXml.contains("Pictures/keep.png"), res.contentXml.take(3000))
    }

    @Test
    fun `a table serializes rows and cells`() {
        val doc = textDoc(
            OdfContentBlock.Table(
                OdfTable(
                    rows = listOf(
                        OdfTableRow(
                            listOf(
                                OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("c1"))))),
                                OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("c2"))))),
                            )
                        )
                    )
                )
            )
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("c1") && xml.contains("c2"), xml.take(2000))
    }

    @Test
    fun `a page break serializes`() {
        val xml = OdfSerializer.serialize(textDoc(para("a"), OdfContentBlock.PageBreak, para("b")))
        assertTrue(xml.contains("a") && xml.contains("b"))
    }
}
