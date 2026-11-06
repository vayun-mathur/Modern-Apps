package com.vayunmathur.library.ui.odf

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OOXML export details: tables, images, charts, footnotes, spreadsheet
 * formulas/number formats, presentation shapes. Slice-1 coverage for
 * library/ui (odf package).
 */
class OoxmlDetailsSlice1Test {

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)), style = style))

    private fun textDoc(vararg blocks: OdfContentBlock, title: String = "T") =
        OdfDocument.TextDocument(title = title, content = blocks.toList())

    private fun readEntry(zip: ByteArray, path: String): String? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == path) return zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return null
    }

    @Test
    fun `a table exports rows and merged cells`() {
        val zip = OoxmlExporter.export(
            textDoc(
                OdfContentBlock.Table(
                    OdfTable(
                        name = "T1",
                        rows = listOf(
                            OdfTableRow(
                                listOf(
                                    OdfTableCell(
                                        listOf(OdfParagraph(listOf(OdfSpan("a")))),
                                        colSpan = 2,
                                        backgroundColor = 0xFFFF00,
                                    ),
                                    OdfTableCell(isCovered = true),
                                )
                            ),
                            OdfTableRow(
                                listOf(
                                    OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("b"))))),
                                    OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("c"))))),
                                )
                            ),
                        ),
                        headerRowCount = 1,
                    )
                )
            )
        )
        val doc = readEntry(zip, "word/document.xml") ?: error("no document.xml")
        assertTrue(doc.contains("w:tbl"), doc.take(1000))
        assertTrue(doc.contains("a"), doc)
        assertTrue(doc.contains("gridSpan"), doc)
        assertTrue(doc.contains("tblHeader"), doc)
    }

    @Test
    fun `a bordered cell exports cell borders`() {
        val zip = OoxmlExporter.export(
            textDoc(
                OdfContentBlock.Table(
                    OdfTable(
                        rows = listOf(
                            OdfTableRow(
                                listOf(
                                    OdfTableCell(
                                        listOf(OdfParagraph(listOf(OdfSpan("x")))),
                                        borders = OdfBorders(top = "0.5pt solid #000000"),
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        val doc = readEntry(zip, "word/document.xml") ?: error("no document.xml")
        assertTrue(doc.contains("tcBorders"), doc)
    }

    @Test
    fun `an image paragraph embeds the drawing`() {
        val zip = OoxmlExporter.export(
            textDoc(
                OdfContentBlock.Image(
                    OdfImage(path = "inline", imageData = byteArrayOf(1, 2, 3), width = 100f, height = 50f)
                )
            )
        )
        val doc = readEntry(zip, "word/document.xml") ?: error("no document.xml")
        assertTrue(doc.contains("w:drawing"), doc.take(2000))
        assertTrue(doc.contains("pic:pic"), doc)
    }

    @Test
    fun `a chart paragraph embeds chart xml`() {
        val zip = OoxmlExporter.export(
            textDoc(
                OdfContentBlock.Chart(
                    OdfChart(
                        type = ChartType.LINE,
                        categories = listOf("Jan", "Feb"),
                        series = listOf(OdfChartSeries("S", listOf(1f, 2f))),
                        title = "Trend",
                    )
                )
            )
        )
        val doc = readEntry(zip, "word/document.xml") ?: error("no document.xml")
        assertTrue(doc.contains("c:chart"), doc.take(2000))
        val names = LinkedHashSet<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { names.add(e.name); zis.closeEntry(); e = zis.nextEntry }
        }
        val chartPart = names.firstOrNull { it.startsWith("word/charts/") } ?: error("no chart part in $names")
        val chartXml = readEntry(zip, chartPart) ?: error("no $chartPart")
        assertTrue(chartXml.contains("Trend"), chartXml.take(2000))
    }

    @Test
    fun `footnotes export to the footnotes part`() {
        val doc = textDoc(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("[1]", superscript = true))))
        ).copy(footnotes = listOf(OdfFootnote("1", listOf(OdfParagraph(listOf(OdfSpan("note one")))))))
        val zip = OoxmlExporter.export(doc)
        val notes = readEntry(zip, "word/footnotes.xml") ?: error("no footnotes.xml")
        assertTrue(notes.contains("note one"), notes.take(2000))
    }

    @Test
    fun `a formula cell converts to excel syntax`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Spreadsheet(
                title = "S",
                sheets = listOf(
                    OdfSheet(
                        name = "Sheet1",
                        rows = listOf(
                            OdfRow(
                                listOf(
                                    OdfCell("2", numberValue = 2.0, valueType = "float"),
                                    OdfCell("", formula = "of:=SUM([.A1:.A1])"),
                                )
                            )
                        ),
                    )
                ),
            )
        )
        val names = LinkedHashSet<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { names.add(e.name); zis.closeEntry(); e = zis.nextEntry }
        }
        val sheetPart = names.firstOrNull { it.startsWith("xl/worksheets/") } ?: error("no sheet in $names")
        val sheet = readEntry(zip, sheetPart) ?: error("no $sheetPart")
        assertTrue(sheet.contains("SUM"), sheet.take(3000))
        assertTrue(sheet.contains("<f>"), sheet.take(3000))
    }

    @Test
    fun `percent and date formats map to number formats`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Spreadsheet(
                title = "S",
                sheets = listOf(
                    OdfSheet(
                        name = "Sheet1",
                        rows = listOf(
                            OdfRow(
                                listOf(
                                    OdfCell("50%", numberFormat = OdfNumberFormat(percent = true)),
                                    OdfCell("2024-01-01", numberFormat = OdfNumberFormat(isDate = true)),
                                )
                            )
                        ),
                    )
                ),
            )
        )
        val names = LinkedHashSet<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { names.add(e.name); zis.closeEntry(); e = zis.nextEntry }
        }
        val styles = readEntry(zip, "xl/styles.xml") ?: error("no styles in $names")
        assertTrue(styles.contains("numFmt"), styles.take(3000))
    }

    @Test
    fun `a presentation with shapes and notes exports`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Presentation(
                title = "P",
                slides = listOf(
                    OdfSlide(
                        name = "S1",
                        elements = listOf(
                            OdfSlideElement.Shape(
                                OdfShape.Rect(
                                    x = 10f, y = 10f, width = 200f, height = 100f,
                                    fillColor = 0xFF0000,
                                    text = listOf(OdfParagraph(listOf(OdfSpan("shape text")))),
                                )
                            ),
                            OdfSlideElement.Shape(
                                OdfShape.Line(x = 0f, y = 0f, width = 50f, height = 50f, x2 = 50f, y2 = 50f)
                            ),
                        ),
                        notes = listOf(OdfParagraph(listOf(OdfSpan("speaker note")))),
                        transitionType = "fade",
                    )
                ),
            )
        )
        val names = LinkedHashSet<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { names.add(e.name); zis.closeEntry(); e = zis.nextEntry }
        }
        val slidePart = names.firstOrNull { it.startsWith("ppt/slides/slide") } ?: error("no slide in $names")
        val slide = readEntry(zip, slidePart) ?: error("no $slidePart")
        assertTrue(slide.contains("shape text"), slide.take(4000))
        assertTrue(names.any { it.startsWith("ppt/notesSlides/") }, names.toString())
    }

    @Test
    fun `a pie chart exports chart xml in a presentation`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Presentation(
                title = "P",
                slides = listOf(
                    OdfSlide(
                        name = "S1",
                        elements = listOf(
                            OdfSlideElement.Frame(
                                OdfFrame(
                                    x = 0f, y = 0f, width = 300f, height = 200f,
                                    paragraphs = emptyList(),
                                    chart = OdfChart(
                                        type = ChartType.PIE,
                                        categories = listOf("A", "B"),
                                        series = listOf(OdfChartSeries("S", listOf(1f, 2f))),
                                    ),
                                )
                            )
                        ),
                    )
                ),
            )
        )
        val names = LinkedHashSet<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { names.add(e.name); zis.closeEntry(); e = zis.nextEntry }
        }
        assertTrue(names.any { it.startsWith("ppt/charts/") }, names.toString())
    }
}
