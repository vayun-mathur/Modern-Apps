package com.vayunmathur.library.ui.odf

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OOXML run properties, section properties, number formats, charts and
 * sheet features. Slice-1 coverage for library/ui (odf package).
 */
class OoxmlRunPropsSlice1Test {

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)), style = style))

    private fun textDoc(vararg blocks: OdfContentBlock, title: String = "T") =
        OdfDocument.TextDocument(title = title, content = blocks.toList())

    private fun readEntry(zip: ByteArray, path: String): String {
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

    @Test
    fun `run decorations export to docx run properties`() {
        val zip = OoxmlExporter.export(
            textDoc(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(
                            OdfSpan("u", underline = true, underlineStyle = "double", underlineColor = 0xFFFF0000),
                            OdfSpan("s", strikethrough = true),
                            OdfSpan("sup", superscript = true),
                            OdfSpan("sub", subscript = true),
                            OdfSpan("f", fontFamily = "Arial", color = 0xFF112233, fontSize = 14f),
                        )
                    )
                )
            )
        )
        val doc = readEntry(zip, "word/document.xml")
        assertTrue(doc.contains("<w:u w:val=\"double\""), doc.take(3000))
        assertTrue(doc.contains("w:color=\"FF0000\""), doc)
        assertTrue(doc.contains("<w:strike/>"), doc)
        assertTrue(doc.contains("w:val=\"superscript\""), doc)
        assertTrue(doc.contains("w:val=\"subscript\""), doc)
        assertTrue(doc.contains("w:ascii=\"Arial\""), doc)
        assertTrue(doc.contains("<w:color w:val=\"112233\"/>"), doc)
        assertTrue(doc.contains("<w:sz w:val=\"28\"/>"), doc)
    }

    @Test
    fun `caps spacing shading and language export`() {
        val zip = OoxmlExporter.export(
            textDoc(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(
                            OdfSpan(
                                "x",
                                textTransform = "uppercase",
                                letterSpacing = 2f,
                                backgroundColor = 0xFFFFFF00,
                                language = "en",
                                country = "GB",
                            )
                        )
                    )
                )
            )
        )
        val doc = readEntry(zip, "word/document.xml")
        assertTrue(doc.contains("<w:caps/>"), doc.take(3000))
        assertTrue(doc.contains("<w:spacing w:val=\"40\"/>"), doc)
        assertTrue(doc.contains("w:fill=\"FFFF00\""), doc)
        assertTrue(doc.contains("<w:lang w:val=\"en-GB\"/>"), doc)
    }

    @Test
    fun `underline style variants map`() {
        for ((style, valAttr) in listOf("dotted" to "dotted", "dash" to "dash", "wave" to "wave")) {
            val zip = OoxmlExporter.export(
                textDoc(
                    OdfContentBlock.Paragraph(
                        OdfParagraph(listOf(OdfSpan("x", underline = true, underlineStyle = style)))
                    )
                )
            )
            val doc = readEntry(zip, "word/document.xml")
            assertTrue(doc.contains("<w:u w:val=\"$valAttr\""), "$style: $doc")
        }
    }

    @Test
    fun `page setup and headers land in section properties`() {
        val page = OdfPageSetup(widthPx = 1122.5f, heightPx = 793.7f)
        val zip = OoxmlExporter.export(
            textDoc(para("body")).copy(
                pageSetup = page,
                headerParagraphs = listOf(OdfParagraph(listOf(OdfSpan("hdr")))),
                footerParagraphs = listOf(OdfParagraph(listOf(OdfSpan("ftr")))),
            )
        )
        val names = entries(zip)
        assertTrue(names.contains("word/header1.xml"), names.toString())
        assertTrue(names.contains("word/footer1.xml"), names.toString())
        val doc = readEntry(zip, "word/document.xml")
        assertTrue(doc.contains("w:orient=\"landscape\""), doc.take(4000))
        assertTrue(doc.contains("w:headerReference"), doc)
        assertTrue(doc.contains("w:footerReference"), doc)
        assertTrue(doc.contains("w:pgMar"), doc)
        assertTrue(readEntry(zip, "word/header1.xml").contains("hdr"))
    }

    @Test
    fun `fields and hyperlinks export`() {
        val zip = OoxmlExporter.export(
            textDoc(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(
                            OdfSpan("1", field = "page-number"),
                            OdfSpan("N", field = "page-count"),
                            OdfSpan("D", field = "date"),
                            OdfSpan("link", href = "https://example.com"),
                            OdfSpan("anchor", href = "#top"),
                        )
                    )
                )
            )
        )
        val doc = readEntry(zip, "word/document.xml")
        assertTrue(doc.contains("PAGE"), doc.take(4000))
        assertTrue(doc.contains("NUMPAGES"), doc)
        assertTrue(doc.contains("DATE"), doc)
        assertTrue(doc.contains("w:hyperlink"), doc)
        assertTrue(doc.contains("w:anchor=\"top\""), doc)
    }

    @Test
    fun `paragraph indents spacing bidi keep and tabs export`() {
        val zip = OoxmlExporter.export(
            textDoc(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(OdfSpan("x")),
                        marginLeft = 96f,
                        marginRight = 48f,
                        marginTop = 24f,
                        marginBottom = 12f,
                        textIndent = -36f,
                        lineHeightPercent = 1.5f,
                        direction = androidx.compose.ui.unit.LayoutDirection.Rtl,
                        keepWithNext = true,
                        keepTogether = true,
                        breakBeforePage = true,
                        tabStopDetails = listOf(
                            OdfTabStop(position = 200f, type = "center", leaderChar = "."),
                            OdfTabStop(position = 300f, type = "char", leaderChar = "-"),
                        ),
                    )
                )
            )
        )
        val doc = readEntry(zip, "word/document.xml")
        assertTrue(doc.contains("w:left="), doc.take(5000))
        assertTrue(doc.contains("w:hanging="), doc)
        assertTrue(doc.contains("w:line="), doc)
        assertTrue(doc.contains("<w:bidi/>"), doc)
        assertTrue(doc.contains("<w:keepNext/>"), doc)
        assertTrue(doc.contains("<w:keepLines/>"), doc)
        assertTrue(doc.contains("<w:pageBreakBefore/>"), doc)
        assertTrue(doc.contains("w:val=\"center\""), doc)
        assertTrue(doc.contains("w:leader=\"dot\""), doc)
        assertTrue(doc.contains("w:val=\"decimal\""), doc)
        assertTrue(doc.contains("w:leader=\"hyphen\""), doc)
    }

    @Test
    fun `uniform paragraph borders export all edges`() {
        val zip = OoxmlExporter.export(
            textDoc(
                OdfContentBlock.Paragraph(
                    OdfParagraph(listOf(OdfSpan("x")), borderColor = 0xFF00FF00)
                )
            )
        )
        val doc = readEntry(zip, "word/document.xml")
        assertTrue(doc.contains("w:pBdr"), doc.take(3000))
        assertTrue(doc.contains("w:color=\"00FF00\""), doc)
    }

    @Test
    fun `per-edge paragraph borders export the given edges`() {
        val zip = OoxmlExporter.export(
            textDoc(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(OdfSpan("x")),
                        borders = OdfBorders(top = "0.5pt solid #AABBCC"),
                    )
                )
            )
        )
        val doc = readEntry(zip, "word/document.xml")
        assertTrue(doc.contains("w:top"), doc.take(3000))
        assertTrue(doc.contains("w:color=\"AABBCC\""), doc)
    }

    @Test
    fun `page breaks images formula and sections export`() {
        val zip = OoxmlExporter.export(
            textDoc(
                para("before"),
                OdfContentBlock.PageBreak,
                OdfContentBlock.Image(
                    OdfImage(path = "p.png", imageData = byteArrayOf(1, 2), width = 100f, height = 50f)
                ),
                OdfContentBlock.Formula("<math/>"),
                OdfContentBlock.SectionStart("s"),
                para("after"),
                OdfContentBlock.SectionEnd,
            )
        )
        val doc = readEntry(zip, "word/document.xml")
        assertTrue(doc.contains("w:type=\"page\""), doc.take(2000))
        assertTrue(doc.contains("w:drawing"), doc)
        assertTrue(doc.contains("formula", ignoreCase = true), doc)
        assertTrue(doc.contains("before") && doc.contains("after"), doc)
    }

    @Test
    fun `chart types map to chart tags`() {
        for ((type, tag) in listOf(
            ChartType.PIE to "pieChart",
            ChartType.DONUT to "doughnutChart",
            ChartType.AREA to "areaChart",
            ChartType.SCATTER to "scatterChart",
            ChartType.RADAR to "radarChart",
        )) {
            val zip = OoxmlExporter.export(
                textDoc(
                    OdfContentBlock.Chart(
                        OdfChart(
                            type = type,
                            categories = listOf("A"),
                            series = listOf(OdfChartSeries("S", listOf(1f), color = 0xFF0000)),
                        )
                    )
                )
            )
            val chartPart = entries(zip).firstOrNull { it.endsWith(".xml") && it.contains("chart") }
                ?: error("no chart part for $type in ${entries(zip)}")
            val xml = readEntry(zip, chartPart)
            assertTrue(xml.contains(tag), "$type should emit $tag: ${xml.take(500)}")
        }
    }

    @Test
    fun `a stacked bar chart groups stacked`() {
        val zip = OoxmlExporter.export(
            textDoc(
                OdfContentBlock.Chart(
                    OdfChart(
                        type = ChartType.BAR,
                        categories = listOf("A"),
                        series = listOf(OdfChartSeries("S", listOf(1f))),
                        stacked = true,
                    )
                )
            )
        )
        val chartPart = entries(zip).first { it.endsWith(".xml") && it.contains("chart") }
        assertTrue(readEntry(zip, chartPart).contains("val=\"stacked\""))
    }

    @Test
    fun `spreadsheet number formats land in the styles part`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Spreadsheet(
                title = "S",
                sheets = listOf(
                    OdfSheet(
                        name = "Sheet1",
                        rows = listOf(
                            OdfRow(
                                listOf(
                                    OdfCell("1", numberFormat = OdfNumberFormat(decimals = 2, grouping = true)),
                                    OdfCell("2", numberFormat = OdfNumberFormat(decimals = 1, percent = true)),
                                    OdfCell("3", numberFormat = OdfNumberFormat(currencySymbol = "$")),
                                    OdfCell(
                                        "2024-01-02",
                                        numberFormat = OdfNumberFormat(
                                            isDate = true,
                                            dateTimeTokens = listOf(
                                                OdfNumberToken("year", style = "long"),
                                                OdfNumberToken("month", textual = true),
                                                OdfNumberToken("day"),
                                                OdfNumberToken("text", text = "-"),
                                            ),
                                        ),
                                    ),
                                    OdfCell("4", numberFormat = OdfNumberFormat(isScientific = true)),
                                    OdfCell("5", numberFormat = OdfNumberFormat(isFraction = true)),
                                    OdfCell("6", numberFormat = OdfNumberFormat(isTime = true)),
                                )
                            )
                        ),
                    )
                ),
            )
        )
        val names = entries(zip)
        val styles = readEntry(zip, names.first { it.contains("styles") })
        // Grouped 2-decimal maps to a builtin id, so the custom formats are
        // percent, currency, date tokens, fraction; scientific hits builtin 11.
        assertTrue(styles.contains("0.0%"), styles.take(2000))
        assertTrue(styles.contains("$"), styles)
        assertTrue(styles.contains("yyyy"), styles)
        assertTrue(styles.contains("mmm"), styles)
        assertTrue(styles.contains("# ?/?"), styles)
        // Scientific hits builtin id 11, so assert it on a cell instead.
        val sheet = readEntry(zip, names.first { it.startsWith("xl/worksheets/") })
        assertTrue(sheet.contains("A1"), sheet.take(1000))
    }

    @Test
    fun `sheet features land in the worksheet xml`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Spreadsheet(
                title = "S",
                sheets = listOf(
                    OdfSheet(
                        name = "Sheet1",
                        rows = listOf(
                            OdfRow(
                                listOf(
                                    OdfCell("f", formula = "of:=SUM([.A1:.A2])", numberValue = 3.0),
                                    OdfCell("link", hyperlink = "https://example.com"),
                                    OdfCell("local", hyperlink = "#Sheet1.A1"),
                                    OdfCell("wide", spannedColumns = 2),
                                    OdfCell("", isCovered = true),
                                )
                            )
                        ),
                        columnWidths = listOf(100f, null),
                        hiddenCols = setOf(1),
                        hiddenRows = setOf(0),
                        rowHeights = listOf(30f),
                        freezeRows = 1,
                        freezeCols = 1,
                        tabColor = 0xFFFF0000,
                    )
                ),
            )
        )
        val names = entries(zip)
        val sheet = readEntry(zip, names.first { it.startsWith("xl/worksheets/") })
        assertTrue(sheet.contains("<f>=SUM("), sheet.take(3000))
        assertTrue(sheet.contains("mergeCell"), sheet)
        assertTrue(sheet.contains("hyperlink"), sheet)
        assertTrue(sheet.contains("location=\"Sheet1.A1\""), sheet)
        assertTrue(sheet.contains("frozen"), sheet)
        assertTrue(sheet.contains("hidden=\"1\""), sheet)
        assertTrue(sheet.contains("tabColor"), sheet)
    }

    @Test
    fun `a floating chart lands in the sheet drawing`() {
        val zip = OoxmlExporter.export(
            OdfDocument.Spreadsheet(
                title = "S",
                sheets = listOf(
                    OdfSheet(
                        name = "Sheet1",
                        rows = listOf(OdfRow(listOf(OdfCell("A1")))),
                        floating = listOf(
                            OdfSlideElement.Frame(
                                OdfFrame(
                                    x = 10f, y = 10f, width = 200f, height = 100f,
                                    paragraphs = emptyList(),
                                    chart = OdfChart(
                                        type = ChartType.LINE,
                                        categories = listOf("A"),
                                        series = listOf(OdfChartSeries("S", listOf(1f))),
                                    ),
                                )
                            ),
                            OdfSlideElement.Frame(
                                OdfFrame(
                                    x = 220f, y = 10f, width = 100f, height = 50f,
                                    paragraphs = emptyList(),
                                    image = OdfImage(path = "i.png", imageData = byteArrayOf(9)),
                                )
                            ),
                        ),
                    )
                ),
            )
        )
        val names = entries(zip)
        assertTrue(names.any { it.contains("drawing") }, names.toString())
        assertTrue(names.any { it.contains("chart_") }, names.toString())
    }
}
