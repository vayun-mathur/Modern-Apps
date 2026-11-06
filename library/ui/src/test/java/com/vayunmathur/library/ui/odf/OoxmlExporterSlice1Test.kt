package com.vayunmathur.library.ui.odf

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OOXML export: package shape per document kind, extension mapping, content.
 * Slice-1 coverage for library/ui (odf package).
 */
class OoxmlExporterSlice1Test {

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)), style = style))

    private fun textDoc(vararg blocks: OdfContentBlock, title: String = "T") =
        OdfDocument.TextDocument(title = title, content = blocks.toList())

    private fun entries(zip: ByteArray): Set<String> {
        val names = LinkedHashSet<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names.add(e.name)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return names
    }

    private fun documentXml(zip: ByteArray, path: String): String {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == path) return zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        error("missing $path")
    }

    @Test
    fun `extensions map per kind`() {
        assertEquals("docx", OoxmlExporter.extensionFor(textDoc(para("a"))))
        assertEquals(
            "xlsx",
            OoxmlExporter.extensionFor(OdfDocument.Spreadsheet(title = "S", sheets = emptyList())),
        )
        assertEquals(
            "pptx",
            OoxmlExporter.extensionFor(OdfDocument.Presentation(title = "P", slides = emptyList())),
        )
        assertEquals(
            "pptx",
            OoxmlExporter.extensionFor(OdfDocument.Drawing(title = "D", pages = emptyList())),
        )
    }

    @Test
    fun `a docx is a package with the text inside`() {
        val zip = OoxmlExporter.export(textDoc(para("hello")))
        val names = entries(zip)
        assertTrue(names.contains("word/document.xml"), names.toString())
        assertTrue(names.contains("[Content_Types].xml"), names.toString())
        val doc = documentXml(zip, "word/document.xml")
        assertTrue(doc.contains("hello"), doc.take(500))
    }

    @Test
    fun `a heading exports with its text`() {
        val zip = OoxmlExporter.export(textDoc(para("Title", ParagraphStyle.HEADING1)))
        val doc = documentXml(zip, "word/document.xml")
        assertTrue(doc.contains("Title"), doc.take(1000))
    }

    @Test
    fun `an xlsx carries the sheet and its values`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Spreadsheet(
                title = "S",
                sheets = listOf(OdfSheet(name = "Sheet1", rows = listOf(OdfRow(listOf(OdfCell("A1")))))),
            )
        )
        val names = entries(zip)
        assertTrue(names.any { it.startsWith("xl/worksheets/") }, names.toString())
        val sheet = documentXml(zip, names.first { it.startsWith("xl/worksheets/") })
        assertTrue(sheet.contains("A1"), sheet.take(1000))
    }

    @Test
    fun `a pptx carries its slides`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Presentation(title = "P", slides = listOf(OdfSlide(name = "S1")))
        )
        val names = entries(zip)
        assertTrue(names.any { it.startsWith("ppt/slides/") }, names.toString())
    }
}
