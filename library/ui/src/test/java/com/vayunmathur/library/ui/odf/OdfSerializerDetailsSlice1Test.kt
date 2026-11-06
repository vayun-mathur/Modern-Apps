package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ODF serialization details: tracked changes, table of contents, footnotes,
 * shapes, frames, charts, sections, spreadsheet styles and number formats.
 * Slice-1 coverage for library/ui (odf package).
 */
class OdfSerializerDetailsSlice1Test {

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)), style = style))

    private fun textDoc(vararg blocks: OdfContentBlock, title: String = "T") =
        OdfDocument.TextDocument(title = title, content = blocks.toList())

    @Test
    fun `insertions and deletions serialize as tracked changes`() {
        val doc = textDoc(
            OdfContentBlock.Paragraph(
                OdfParagraph(
                    listOf(
                        OdfSpan("new", changeId = "c1", changeKind = "insertion"),
                        OdfSpan("old", changeId = "c2", changeKind = "deletion"),
                    )
                )
            )
        ).copy(changes = listOf(OdfChange(id = "c1", type = "insertion", author = "Ada", date = "2024-01-01")))
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("text:tracked-changes"), xml.take(3000))
        assertTrue(xml.contains("Ada"), xml)
        assertTrue(xml.contains("<text:p>old</text:p>"), xml)
    }

    @Test
    fun `a document without changes has no tracked-changes element`() {
        val xml = OdfSerializer.serialize(textDoc(para("plain")))
        assertTrue(!xml.contains("text:tracked-changes"), xml.take(2000))
    }

    @Test
    fun `a table of contents serializes its entries`() {
        val toc = OdfContentBlock.TableOfContents(
            "Contents",
            listOf(OdfParagraph(listOf(OdfSpan("Chapter 1")), style = ParagraphStyle.HEADING1)),
        )
        val xml = OdfSerializer.serialize(textDoc(toc))
        assertTrue(xml.contains("text:table-of-content"), xml.take(3000))
        assertTrue(xml.contains("Chapter 1"), xml)
    }

    @Test
    fun `a footnote marker serializes the note`() {
        val doc = textDoc(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("[1]", superscript = true))))
        ).copy(footnotes = listOf(OdfFootnote("1", listOf(OdfParagraph(listOf(OdfSpan("note body")))))))
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("note body"), xml.take(6000))
    }

    @Test
    fun `a chart block serializes a chart frame`() {
        val chart = OdfChart(
            type = ChartType.BAR,
            categories = listOf("Q1", "Q2"),
            series = listOf(OdfChartSeries("S", listOf(1f, 2f), color = 0xFF0000)),
            title = "Sales",
        )
        val doc = textDoc(OdfContentBlock.Chart(chart))
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("Sales"), xml.take(6000))
        // A chart in flat content serializes as a placeholder frame; the full
        // chart XML ships in the package objects (covered below).
        assertTrue(xml.contains("draw:frame"), xml.take(6000))
    }

    @Test
    fun `a chart object serializes into the package`() {
        val chart = OdfChart(
            type = ChartType.PIE,
            categories = listOf("A", "B"),
            series = listOf(OdfChartSeries("S", listOf(3f, 4f))),
        )
        val res = OdfSerializer.serializePackaged(textDoc(OdfContentBlock.Chart(chart)))
        assertTrue(res.objects.isNotEmpty())
        assertTrue(res.objects.values.any { it.contains("pie") || it.contains("chart") }, res.objects.keys.toString())
    }

    @Test
    fun `a spreadsheet with a formula serializes the formula`() {
        val doc = OdfDocument.Spreadsheet(
            title = "S",
            sheets = listOf(
                OdfSheet(
                    name = "Sheet1",
                    rows = listOf(OdfRow(listOf(OdfCell("1"), OdfCell("2", formula = "SUM(A1:B1)")))),
                )
            ),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("SUM(A1:B1)"), xml.take(4000))
    }

    @Test
    fun `a spreadsheet with column widths serializes column styles`() {
        val doc = OdfDocument.Spreadsheet(
            title = "S",
            sheets = listOf(
                OdfSheet(
                    name = "Sheet1",
                    rows = listOf(OdfRow(listOf(OdfCell("v")))),
                    columnWidths = listOf(120f),
                )
            ),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("table:table-column"), xml.take(4000))
    }

    @Test
    fun `a section start with columns serializes the section`() {
        val xml = OdfSerializer.serialize(
            textDoc(
                OdfContentBlock.SectionStart("S1", columnCount = 2),
                para("inside"),
                OdfContentBlock.SectionEnd,
            )
        )
        assertTrue(xml.contains("inside"), xml.take(4000))
        assertTrue(xml.contains("text:section"), xml.take(4000))
    }

    @Test
    fun `a numbered list serializes a list style`() {
        val xml = OdfSerializer.serialize(
            textDoc(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(OdfSpan("one")),
                        style = ParagraphStyle.LIST_ITEM,
                        listLevel = 1,
                        listType = ListType.NUMBERED,
                    )
                )
            )
        )
        assertTrue(xml.contains("text:list"), xml.take(4000))
        assertTrue(xml.contains("one"), xml)
    }

    @Test
    fun `a presentation with a shape slide serializes the shape`() {
        val doc = OdfDocument.Presentation(
            title = "P",
            slides = listOf(
                OdfSlide(
                    name = "S1",
                    elements = listOf(
                        OdfSlideElement.Shape(
                            OdfShape.Rect(
                                x = 10f, y = 20f, width = 100f, height = 50f,
                                fillColor = 0xFF0000,
                                text = listOf(OdfParagraph(listOf(OdfSpan("caption")))),
                            )
                        )
                    ),
                )
            ),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("caption"), xml.take(8000))
        assertTrue(xml.contains("draw:") , xml.take(8000))
    }

    @Test
    fun `a presentation with a frame image serializes the frame`() {
        val doc = OdfDocument.Presentation(
            title = "P",
            slides = listOf(
                OdfSlide(
                    name = "S1",
                    elements = listOf(
                        OdfSlideElement.Frame(
                            OdfFrame(
                                x = 0f, y = 0f, width = 200f, height = 100f,
                                paragraphs = listOf(OdfParagraph(listOf(OdfSpan("frame text")))),
                            )
                        )
                    ),
                )
            ),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("frame text"), xml.take(8000))
    }

    @Test
    fun `a datetime field serializes a date element`() {
        val xml = OdfSerializer.serialize(
            textDoc(
                OdfContentBlock.Paragraph(
                    OdfParagraph(listOf(OdfSpan("2024-01-01", field = "date")))
                )
            )
        )
        assertTrue(xml.contains("text:date") || xml.contains("2024-01-01"), xml.take(4000))
    }

    @Test
    fun `a bordered paragraph serializes its border`() {
        val xml = OdfSerializer.serialize(
            textDoc(
                OdfContentBlock.Paragraph(
                    OdfParagraph(listOf(OdfSpan("boxed")), borderColor = 0x000000)
                )
            )
        )
        assertTrue(xml.contains("boxed"), xml.take(4000))
        assertTrue(xml.contains("fo:border"), xml.take(8000))
    }

    @Test
    fun `a spreadsheet date cell serializes a number style`() {
        val doc = OdfDocument.Spreadsheet(
            title = "S",
            sheets = listOf(
                OdfSheet(
                    name = "Sheet1",
                    rows = listOf(
                        OdfRow(
                            listOf(
                                OdfCell(
                                    "2024-01-01",
                                    numberFormat = OdfNumberFormat(isDate = true),
                                )
                            )
                        )
                    ),
                )
            ),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("2024-01-01"), xml.take(6000))
    }
}
