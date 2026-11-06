package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Small exporters: PDF bytes, LaTeX source, EPUB package.
 * Slice-1 coverage for library/ui (odf package).
 */
class SmallExportersSlice1Test {

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)), style = style))

    private fun docOf(vararg blocks: OdfContentBlock, title: String = "T") =
        OdfDocument.TextDocument(title = title, content = blocks.toList())

    @Test
    fun `pdf bytes open with a header and carry the text`() {
        val pdf = PdfExporter.export(docOf(para("hello"), para("Title", ParagraphStyle.HEADING1)))
        assertTrue(pdf.toString(Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
        assertTrue(pdf.toString(Charsets.ISO_8859_1).contains("hello"))
    }

    @Test
    fun `pdf renders a list and a table`() {
        val pdf = PdfExporter.export(
            docOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(OdfSpan("item")),
                        style = ParagraphStyle.LIST_ITEM,
                        listLevel = 1,
                    )
                ),
                OdfContentBlock.Table(
                    OdfTable(
                        rows = listOf(
                            OdfTableRow(
                                listOf(
                                    OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("c1"))))),
                                )
                            )
                        )
                    )
                ),
            )
        )
        val text = pdf.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("item"), text.take(500))
        assertTrue(text.contains("c1"), text.take(2000))
    }

    @Test
    fun `latex source is an article with sections`() {
        val tex = LatexExporter.export(
            docOf(para("Body"), para("Title", ParagraphStyle.HEADING1))
        )
        assertTrue(tex.contains("\\documentclass{article}"), tex.take(200))
        assertTrue(tex.contains("\\section{Title}"), tex.take(600))
        assertTrue(tex.contains("Body"), tex.take(600))
        assertTrue(tex.contains("\\end{document}"), tex.take(200))
    }

    @Test
    fun `latex escapes specials`() {
        val tex = LatexExporter.export(docOf(para("100% & done")))
        assertTrue(tex.contains("\\%"), tex.take(600))
        assertTrue(tex.contains("\\&"), tex.take(600))
    }

    @Test
    fun `epub is a zip with the content document`() {
        val epub = EpubExporter.export(docOf(para("hello")))
        // ZIP magic + the content document entry inside (entries are
        // deflated, so unzip to inspect).
        assertTrue(epub[0] == 0x50.toByte() && epub[1] == 0x4B.toByte())
        val names = LinkedHashSet<String>()
        var body = ""
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(epub)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names.add(e.name)
                if (e.name == "OEBPS/content.xhtml") body = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        assertTrue(names.contains("OEBPS/content.xhtml"), names.toString())
        assertTrue(body.contains("hello"), body.take(500))
    }

    @Test
    fun `epub falls back to a default title`() {
        val epub = EpubExporter.export(docOf(para("hi"), title = ""))
        var opf = ""
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(epub)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "OEBPS/content.opf") opf = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        assertTrue(opf.contains("Document"), opf.take(800))
    }
}
