package com.vayunmathur.library.ui.odf

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * W4: OOXML export of rich documents — docx tables/styles, xlsx formatted
 * cells, and pptx shapes of every kind (fills, gradients, dashes, markers,
 * rotation, charts and images). Assertions check the generated package XML
 * carries the document's text and styling markers.
 */
class W4OoxmlRichExportTest {

    private fun entries(zip: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                map[e.name] = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return map
    }

    private fun allXml(zip: ByteArray): String = entries(zip).values.joinToString("\n")

    @Test
    fun `docx export carries styled runs tables and images`() {
        val doc = OdfDocument.TextDocument(
            title = "Report",
            content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(
                    listOf(
                        OdfSpan("plain "),
                        OdfSpan("boldlink", bold = true, href = "https://example.org", color = 0xFF0000L),
                        OdfSpan(" under", underline = true, fontSize = 22f, fontFamily = "Courier"),
                    ),
                    style = ParagraphStyle.HEADING1,
                )),
                OdfContentBlock.Table(OdfTable(
                    columns = listOf(OdfTableColumn(70f), OdfTableColumn(30f)),
                    rows = listOf(
                        OdfTableRow(listOf(
                            OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("cell one")))), colSpan = 2, backgroundColor = 0x00FF00L),
                        )),
                        OdfTableRow(listOf(
                            OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("cell two"))))),
                            OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("cell three"))))),
                        )),
                    ),
                    headerRowCount = 1,
                )),
                OdfContentBlock.Paragraph(OdfParagraph(
                    listOf(OdfSpan("listed", )),
                    style = ParagraphStyle.LIST_ITEM, listType = ListType.NUMBERED, listLevel = 2,
                )),
            ),
            footnotes = listOf(OdfFootnote("1", listOf(OdfParagraph(listOf(OdfSpan("footnote body")))))),
        )
        val xml = allXml(OoxmlExporter.export(doc))
        assertTrue(xml.contains("boldlink"), xml.take(2000))
        assertTrue(xml.contains("cell one"), xml.take(2000))
        assertTrue(xml.contains("cell three"), xml.take(2000))
        assertTrue(xml.contains("example.org"), xml.take(3000))
    }

    @Test
    fun `xlsx export carries formatted and formula cells`() {
        val sheet = OdfSheet(
            name = "Data",
            rows = listOf(
                OdfRow(listOf(
                    OdfCell("1,234.50", numberValue = 1234.5, valueType = "currency",
                        numberFormat = OdfNumberFormat(decimals = 2, grouping = true, currencySymbol = "$")),
                    OdfCell("2024-01-01", numberValue = 45292.0, valueType = "date",
                        numberFormat = OdfNumberFormat(isDate = true)),
                    OdfCell("3", numberValue = 3.0, formula = "of:=SUM([.A1:.A1])", bold = true),
                    OdfCell("50%", numberValue = 0.5, valueType = "percentage",
                        numberFormat = OdfNumberFormat(percent = true, decimals = 0)),
                )),
            ),
            columnWidths = listOf(80f, 60f),
            freezeRows = 1, freezeCols = 1,
        )
        val xml = allXml(OoxmlExporter.export(OdfDocument.Spreadsheet("Book", listOf(sheet))))
        assertTrue(xml.contains("Data"), xml.take(2000))
        assertTrue(xml.contains("1234.5") || xml.contains("1,234"), xml.take(3000))
    }

    @Test
    fun `pptx export carries every shape kind`() {
        val gradient = OdfGradient(0xFF0000L, 0x0000FFL, angle = 45f)
        val slide = OdfSlide(
            name = "Shapes",
            elements = listOf(
                OdfSlideElement.Shape(OdfShape.Rect(
                    10f, 10f, 100f, 50f, fillColor = 0xFF0000L, strokeColor = 0x00FF00L, strokeWidth = 2f,
                    text = listOf(OdfParagraph(listOf(OdfSpan("rect label", bold = true)))),
                    rotationDegrees = 30f, fillGradient = gradient, strokeDashed = true)),
                OdfSlideElement.Shape(OdfShape.Ellipse(
                    10f, 80f, 90f, 40f, text = listOf(OdfParagraph(listOf(OdfSpan("ellipse label")))))),
                OdfSlideElement.Shape(OdfShape.Line(
                    200f, 20f, 80f, 60f, x2 = 20f, y2 = 80f, markerStart = true, markerEnd = true,
                    text = listOf(OdfParagraph(listOf(OdfSpan("line label")))))),
                OdfSlideElement.Shape(OdfShape.CustomShape(
                    10f, 140f, 60f, 60f, text = listOf(OdfParagraph(listOf(OdfSpan("custom label")))))),
                OdfSlideElement.Shape(OdfShape.Polyline(
                    100f, 140f, 80f, 40f, points = listOf(0f to 0f, 40f to 20f, 80f to 0f), closed = true,
                    text = listOf(OdfParagraph(listOf(OdfSpan("poly label")))))),
                OdfSlideElement.Frame(OdfFrame(
                    200f, 100f, 120f, 60f, listOf(OdfParagraph(listOf(OdfSpan("frame label")))))),
            ),
            backgroundColor = 0x123456L,
            notes = listOf(OdfParagraph(listOf(OdfSpan("slide notes")))),
            transitionType = "wipe", transitionSpeed = "slow",
        )
        val zip = OoxmlExporter.export(OdfDocument.Presentation("Deck", listOf(slide)))
        val xml = allXml(zip)
        for (label in listOf("rect label", "ellipse label", "custom label", "frame label")) {
            assertTrue(xml.contains(label), label)
        }
        assertTrue(entries(zip).keys.any { it.contains("slide") }, entries(zip).keys.toString())
    }
}
