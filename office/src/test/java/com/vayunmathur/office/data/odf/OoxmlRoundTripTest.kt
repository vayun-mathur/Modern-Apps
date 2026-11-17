package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfNamedRange
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import com.vayunmathur.library.ui.odf.OoxmlExporter
import com.vayunmathur.library.ui.odf.ParagraphStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip tests: build an ODF model, export to OOXML via [OoxmlExporter], re-import via
 * [OoxmlImporter], and assert the key formatting survived. Validates that the exporter emits
 * structurally valid packages the importer can read back.
 */
class OoxmlRoundTripTest {

    @Test fun docxRoundTrip() {
        val doc = OdfDocument.TextDocument(
            title = "d",
            content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("Heading")), style = ParagraphStyle.HEADING1)),
                OdfContentBlock.Paragraph(OdfParagraph(listOf(
                    OdfSpan("bold", bold = true),
                    OdfSpan(" red", color = 0xFFFF0000)))),
                OdfContentBlock.Paragraph(OdfParagraph(
                    listOf(OdfSpan("item")),
                    style = ParagraphStyle.LIST_ITEM,
                    listType = ListType.NUMBERED)),
                OdfContentBlock.Table(OdfTable(rows = listOf(
                    OdfTableRow(listOf(
                        OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("A"))))),
                        OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("B")))))))
                ))),
                OdfContentBlock.Image(OdfImage("media/x.png", byteArrayOf(1, 2, 3, 4), width = 100f, height = 80f))
            ),
            pageSetup = OdfPageSetup()
        )
        val bytes = OoxmlExporter.export(doc)
        val back = OoxmlImporter.import(bytes, "d.docx") as OdfDocument.TextDocument

        val paras = back.content.filterIsInstance<OdfContentBlock.Paragraph>()
        assertEquals(ParagraphStyle.HEADING1, paras[0].paragraph.style)
        val boldPara = paras.first { it.paragraph.spans.any { s -> s.text == "bold" } }
        assertTrue(boldPara.paragraph.spans.first { it.text == "bold" }.bold)
        assertEquals(0xFFFF0000L, boldPara.paragraph.spans.first { it.text.trim() == "red" }.color)
        assertTrue(paras.any { it.paragraph.listType == ListType.NUMBERED && it.paragraph.spans.any { s -> s.text == "item" } })
        assertTrue(back.content.any { it is OdfContentBlock.Table })
        assertTrue(back.content.any { it is OdfContentBlock.Image })
        assertTrue(back.images.isNotEmpty())
    }

    @Test fun xlsxRoundTrip() {
        val doc = OdfDocument.Spreadsheet(
            title = "s",
            sheets = listOf(OdfSheet(
                name = "Data",
                rows = listOf(
                    OdfRow(listOf(
                        OdfCell(text = "Name", bold = true, backgroundColor = 0xFFFFFF00),
                        OdfCell(
                            text = "",
                            numberValue = 3.14,
                            valueType = "float",
                            numberFormat = OdfNumberFormat(decimals = 2))
                    )),
                    OdfRow(listOf(
                        OdfCell(text = "", numberValue = 5.0, valueType = "float"),
                        OdfCell(text = "", formula = "of:=[.A2]*2", numberValue = 10.0, valueType = "float")
                    ))
                ),
                columnWidths = listOf(120f, 80f),
                freezeRows = 1,
                tabColor = 0xFF00FF00
            )),
            namedRanges = listOf(OdfNamedRange("MyRange", "Data.A1:Data.B2"))
        )
        val bytes = OoxmlExporter.export(doc)
        val back = OoxmlImporter.import(bytes, "s.xlsx") as OdfDocument.Spreadsheet
        val sheet = back.sheets[0]
        assertEquals("Data", sheet.name)
        assertEquals("Name", sheet.rows[0].cells[0].text)
        assertTrue(sheet.rows[0].cells[0].bold)
        assertEquals(0xFFFFFF00L, sheet.rows[0].cells[0].backgroundColor)
        assertEquals(3.14, sheet.rows[0].cells[1].numberValue!!, 0.0001)
        assertTrue(sheet.rows[1].cells[1].formula != null)
        assertEquals(1, sheet.freezeRows)
        assertEquals(0xFF00FF00L, sheet.tabColor)
        assertTrue(back.namedRanges.any { it.name == "MyRange" })
    }

    @Test fun pptxRoundTrip() {
        val doc = OdfDocument.Presentation(
            title = "p",
            slides = listOf(OdfSlide(
                name = "First",
                elements = listOf(
                    OdfSlideElement.Frame(OdfFrame(
                        50f,
                        50f,
                        300f,
                        100f,
                        listOf(OdfParagraph(listOf(OdfSpan("Title", bold = true, fontSize = 28f)))))),
                    OdfSlideElement.Shape(OdfShape.Ellipse(400f, 50f, 120f, 120f, fillColor = 0xFF4472C4))
                ),
                backgroundColor = 0xFFEEEEEE,
                notes = listOf(OdfParagraph(listOf(OdfSpan("speaker note"))))
            ))
        )
        val bytes = OoxmlExporter.export(doc)
        val back = OoxmlImporter.import(bytes, "p.pptx") as OdfDocument.Presentation
        val slide = back.slides[0]
        val textFrame = slide.elements.filterIsInstance<OdfSlideElement.Frame>().first { it.frame.paragraphs.any { p -> p.spans.any { s -> s.text == "Title" } } }
        assertTrue(textFrame.frame.paragraphs[0].spans[0].bold)
        assertEquals(0xFFEEEEEEL, slide.backgroundColor)
        assertTrue(slide.notes.any { it.spans.any { s -> s.text.contains("speaker note") } })
    }
}
