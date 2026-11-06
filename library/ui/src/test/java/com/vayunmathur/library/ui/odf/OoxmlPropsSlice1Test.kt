package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OOXML third round: headers/footers, paragraph properties, fields,
 * hyperlinks, run properties. Slice-1 coverage for library/ui (odf package).
 */
class OoxmlPropsSlice1Test {

    private fun textDoc(vararg blocks: OdfContentBlock, title: String = "T") =
        OdfDocument.TextDocument(title = title, content = blocks.toList())

    private fun entries(zip: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                out[e.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return out
    }

    @Test
    fun `headers and footers emit parts and references`() {
        val doc = textDoc(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("body")))),
        ).copy(
            headerParagraphs = listOf(OdfParagraph(listOf(OdfSpan("head text")))),
            footerParagraphs = listOf(OdfParagraph(listOf(OdfSpan("foot text")))),
        )
        val parts = entries(OoxmlExporter.export(doc))
        val header = parts["word/header1.xml"] ?: error("no header in ${parts.keys}")
        assertTrue(header.contains("head text"), header.take(1000))
        val footer = parts["word/footer1.xml"] ?: error("no footer in ${parts.keys}")
        assertTrue(footer.contains("foot text"), footer.take(1000))
        val main = parts["word/document.xml"] ?: error("no document")
        assertTrue(main.contains("headerReference"), main.take(3000))
        assertTrue(main.contains("footerReference"), main.take(3000))
    }

    @Test
    fun `paragraph properties emit indents spacing and keep flags`() {
        val doc = textDoc(
            OdfContentBlock.Paragraph(
                OdfParagraph(
                    listOf(OdfSpan("fancy")),
                    alignment = TextAlign.Justify,
                    marginLeft = 24f,
                    marginRight = 12f,
                    textIndent = 18f,
                    marginTop = 6f,
                    marginBottom = 6f,
                    lineHeightPercent = 1.5f,
                    keepWithNext = true,
                    keepTogether = true,
                    breakBeforePage = true,
                    direction = LayoutDirection.Rtl,
                    tabStopDetails = listOf(OdfTabStop(72f, type = "center", leaderChar = ".")),
                )
            )
        )
        val main = entries(OoxmlExporter.export(doc))["word/document.xml"] ?: error("no document")
        assertTrue(main.contains("w:ind"), main)
        assertTrue(main.contains("w:spacing"), main)
        assertTrue(main.contains("keepNext"), main)
        assertTrue(main.contains("keepLines"), main)
        assertTrue(main.contains("pageBreakBefore"), main)
        assertTrue(main.contains("w:bidi"), main)
        assertTrue(main.contains("w:tabs"), main)
        assertTrue(main.contains("w:jc w:val=\"both\""), main)
    }

    @Test
    fun `hanging indent and border color emit`() {
        val doc = textDoc(
            OdfContentBlock.Paragraph(
                OdfParagraph(
                    listOf(OdfSpan("hang")),
                    textIndent = -18f,
                    borderColor = 0xFF0000,
                )
            )
        )
        val main = entries(OoxmlExporter.export(doc))["word/document.xml"] ?: error("no document")
        assertTrue(main.contains("w:hanging"), main)
        assertTrue(main.contains("w:pBdr"), main)
    }

    @Test
    fun `fields and hyperlinks wrap runs`() {
        val doc = textDoc(
            OdfContentBlock.Paragraph(
                OdfParagraph(
                    listOf(
                        OdfSpan("1", field = "page-number"),
                        OdfSpan("x", href = "https://x.test"),
                        OdfSpan("y", href = "#bookmark"),
                    )
                )
            )
        )
        val main = entries(OoxmlExporter.export(doc))["word/document.xml"] ?: error("no document")
        assertTrue(main.contains("fldSimple"), main)
        assertTrue(main.contains("w:hyperlink"), main)
        assertTrue(main.contains("w:anchor=\"bookmark\""), main)
    }

    @Test
    fun `run properties emit size color and highlight`() {
        val doc = textDoc(
            OdfContentBlock.Paragraph(
                OdfParagraph(
                    listOf(
                        OdfSpan("s", fontSize = 20f, color = 0xFF0000FF, backgroundColor = 0xFFFFFF00),
                    )
                )
            )
        )
        val main = entries(OoxmlExporter.export(doc))["word/document.xml"] ?: error("no document")
        assertTrue(main.contains("w:sz"), main)
        assertTrue(main.contains("w:color"), main)
        assertTrue(main.contains("w:highlight") || main.contains("w:shd"), main)
    }
}
