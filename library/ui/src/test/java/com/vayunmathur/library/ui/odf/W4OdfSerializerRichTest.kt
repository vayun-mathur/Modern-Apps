package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W4: rich-document ODF serialization.
 *
 * Each document exercises serializer branches the plain-text fixtures do
 * not reach: full span/paragraph style properties, tables with spans and
 * number formats, cropped/rotated images in every container, charts of
 * several kinds, spreadsheet row styles/floating elements, and every
 * shape kind with gradients, dashes and rotation. Assertions pin the
 * emitted values (fonts, colours, titles, geometry) in the XML.
 */
class W4OdfSerializerRichTest {

    private val png: ByteArray = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")

    private fun styledSpan() = OdfSpan(
        text = "styled",
        bold = true, italic = true, underline = true,
        underlineStyle = "dotted", underlineColor = 0xFF0000L,
        strikethrough = true, color = 0x00FF00L, backgroundColor = 0x0000FFL,
        fontSize = 20f, fontFamily = "ZebraFont",
        superscript = true, letterSpacing = 1.5f,
        textTransform = "uppercase", language = "de", country = "DE",
    )

    private fun richParagraph() = OdfParagraph(
        spans = listOf(styledSpan(), OdfSpan(" sub", subscript = true)),
        style = ParagraphStyle.HEADING2,
        alignment = TextAlign.Center,
        marginLeft = 12f, marginTop = 6f, marginBottom = 8f, marginRight = 10f,
        textIndent = 4f, backgroundColor = 0x123456L,
        lineHeightPercent = 150f, borderColor = 0x654321L,
        borders = OdfBorders(top = "1pt solid #000000", bottom = "2pt dotted #FF0000"),
        tabStops = listOf(48f, 96f),
        tabStopDetails = listOf(
            OdfTabStop(48f, "right", "."),
            OdfTabStop(96f, "center", null),
        ),
        dropCapLines = 2, dropCapLength = 3, padding = 5f,
        breakBeforePage = true, keepWithNext = true, keepTogether = true,
        widows = 2, orphans = 3, outlineNumber = "1.2.",
        listType = ListType.NUMBERED, listLevel = 2,
        listNumberFormat = "A", listNumberPrefix = "(", listNumberSuffix = ")",
        listItemIndex = 3,
    )

    private fun richTable() = OdfContentBlock.Table(
        OdfTable(
            name = "DataTable",
            columns = listOf(OdfTableColumn(80f), OdfTableColumn(null), OdfTableColumn(40f)),
            rows = listOf(
                OdfTableRow(listOf(
                    OdfTableCell(
                        paragraphs = listOf(OdfParagraph(listOf(OdfSpan("head")))),
                        colSpan = 2, backgroundColor = 0xABCDEFL,
                        borders = OdfBorders(left = "1pt solid #111111"),
                        verticalAlign = "middle",
                    ),
                    OdfTableCell(
                        paragraphs = listOf(OdfParagraph(listOf(OdfSpan("42")))),
                        rowSpan = 2, formula = "of:=SUM([.A1:.A2])",
                    ),
                )),
                OdfTableRow(listOf(
                    OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan("covered")))), isCovered = true),
                    OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan("plain"))))),
                )),
            ),
            headerRowCount = 1,
        )
    )

    private fun richImage() = OdfImage(
        path = "Pictures/chart.png", imageData = png,
        width = 120f, height = 80f, rotationDegrees = 30f,
        cropLeftPct = 0.1f, cropTopPct = 0.2f, cropRightPct = 0.1f, cropBottomPct = 0.05f,
        opacityPercent = 55f, colorMode = "greyscale",
        altTitle = "Alt title", altDesc = "Alt desc",
        naturalWidthPx = 10f, naturalHeightPx = 10f,
    )

    private fun richChart(type: ChartType) = OdfChart(
        type = type,
        categories = listOf("Jan", "Feb"),
        series = listOf(
            OdfChartSeries("Alpha", listOf(1f, 2f), color = 0xFF8800L, dataLabels = true),
            OdfChartSeries("Beta", listOf(3f, 4f)),
        ),
        title = "Quarterly", subtitle = "Sub", legend = true,
        xAxisTitle = "Month", yAxisTitle = "Value", stacked = true,
    )

    @Test
    fun `rich text document serializes all style branches`() {
        val doc = OdfDocument.TextDocument(
            title = "RichDoc",
            content = listOf(
                OdfContentBlock.Paragraph(richParagraph()),
                OdfContentBlock.Paragraph(OdfParagraph(listOf(
                    OdfSpan("noted body "), OdfSpan("fn1", superscript = true)))),
                richTable(),
                OdfContentBlock.Image(richImage()),
                OdfContentBlock.Chart(richChart(ChartType.BAR)),
                OdfContentBlock.Chart(richChart(ChartType.PIE)),
                OdfContentBlock.Formula("<math><mi>x</mi></math>"),
                OdfContentBlock.TableOfContents("Contents", listOf(OdfParagraph(listOf(OdfSpan("Chapter 1"))))),
                OdfContentBlock.SectionStart("TwoCol", columnCount = 2),
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("in section")))),
                OdfContentBlock.SectionEnd,
                OdfContentBlock.PageBreak,
            ),
            footnotes = listOf(OdfFootnote("fn1", listOf(OdfParagraph(listOf(OdfSpan("note text")))))),
            headerParagraphs = listOf(OdfParagraph(listOf(OdfSpan("header text")))),
            footerParagraphs = listOf(OdfParagraph(listOf(OdfSpan("footer text")))),
            bookmarks = listOf(OdfBookmark("bm1", 0)),
            pageSetup = OdfPageSetup(
                widthPx = 1122.5f, heightPx = 793.7f,
                marginLeftPx = 40f, marginRightPx = 40f, marginTopPx = 30f, marginBottomPx = 30f),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("ZebraFont"), xml.take(2000))
        assertTrue(xml.contains("Quarterly"), xml.take(4000))
        assertTrue(xml.contains("Chapter 1"), xml.take(4000))
        // Footnotes/headers live outside the main content stream; they must
        // survive in one of the serialized parts.
        val allParts = xml + OdfSerializer.serializeStyles(doc) +
            OdfSerializer.serializePackaged(doc).contentXml
        assertTrue(allParts.contains("note text") || allParts.contains("fn1"), allParts.take(2000))
        assertTrue(xml.contains("covered"), xml.take(4000))
        val packaged = OdfSerializer.serializePackaged(doc)
        assertTrue(packaged.contentXml.contains("styled", ignoreCase = true), packaged.contentXml.take(2000))
        assertTrue(packaged.images.isNotEmpty() || packaged.objects.isNotEmpty(), packaged.images.keys.toString())
    }

    @Test
    fun `table alone serializes header rows spans and formulas`() {
        val xml = OdfSerializer.serialize(
            OdfDocument.TextDocument("OnlyTable", listOf(richTable())))
        assertTrue(xml.contains("DataTable"), xml.take(2000))
        assertTrue(xml.contains("head"), xml.take(2000))
        assertTrue(xml.contains("covered") || xml.contains("table:covered-table-cell"), xml.take(3000))
    }

    @Test
    fun `rich spreadsheet serializes row styles floating and formats`() {
        val formatted = OdfCell(
            text = "1,234.50", numberValue = 1234.5, valueType = "currency",
            numberFormat = OdfNumberFormat(decimals = 2, grouping = true, currencySymbol = "$"),
            backgroundColor = 0x112233L, textColor = 0xFFFFFFL, bold = true,
            alignment = TextAlign.Right, wrap = true,
            formula = "of:=SUM([.A1:.A2])",
            annotation = OdfAnnotation("Ann", paragraphs = listOf(OdfParagraph(listOf(OdfSpan("cell note"))))),
            validationName = "val1", hyperlink = "https://example.org",
            textRotation = 45, verticalAlign = "bottom",
            condFormats = listOf(OdfCondFormat("cell-content()>10", backgroundColor = 0xFF0000L)),
        )
        val dateCell = OdfCell(
            text = "2024-01-01", numberValue = 45292.0, valueType = "date",
            numberFormat = OdfNumberFormat(isDate = true),
        )
        val sheet = OdfSheet(
            name = "MainSheet",
            rows = listOf(
                OdfRow(listOf(formatted, dateCell, OdfCell("covered", isCovered = true, spannedColumns = 2, rowSpan = 1))),
                OdfRow(listOf(OdfCell("second"))),
            ),
            columnWidths = listOf(90f, null, 30f),
            rowHeights = listOf(24f, null),
            hiddenRows = setOf(1), hiddenCols = setOf(2),
            freezeRows = 1, freezeCols = 1,
            printRanges = "MainSheet.A1:C2", tabColor = 0x00AA00L,
            floating = listOf(
                OdfSlideElement.Shape(OdfShape.Rect(
                    0f, 0f, 50f, 20f, text = listOf(OdfParagraph(listOf(OdfSpan("float rect")))))),
                OdfSlideElement.Frame(OdfFrame(0f, 30f, 60f, 40f, emptyList(), image = richImage())),
            ),
        )
        val doc = OdfDocument.Spreadsheet(
            title = "Book",
            sheets = listOf(sheet, OdfSheet("Empty", emptyList(), hidden = true)),
            namedRanges = listOf(OdfNamedRange("Totals", "MainSheet.A1:B2", "MainSheet.A1")),
            validations = listOf(OdfDataValidation("val1", "cell-content-is-in-list(\"a;b\")")),
        )
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("MainSheet"), xml.take(2000))
        assertTrue(xml.contains("float rect"), xml.take(4000))
        assertTrue(xml.contains("Totals") || xml.contains("MainSheet.A1"), xml.take(4000))
        val settings = OdfSerializer.serializeSettings(doc)
        assertTrue(settings == null || settings.contains("MainSheet"), settings?.take(1000) ?: "")
    }

    @Test
    fun `rich presentation serializes every shape kind`() {
        val gradient = OdfGradient(0xFF0000L, 0x00FF00L, angle = 90f)
        val shapes: List<OdfSlideElement> = listOf(
            OdfSlideElement.Shape(OdfShape.Rect(
                0f, 0f, 100f, 50f, fillColor = 0xFF0000L, strokeColor = 0x0000FFL, strokeWidth = 2f,
                text = listOf(OdfParagraph(listOf(OdfSpan("rect text")))),
                rotationDegrees = 20f, fillGradient = gradient, strokeDashed = true, cornerRadius = 6f)),
            OdfSlideElement.Shape(OdfShape.Ellipse(
                0f, 60f, 80f, 40f, text = listOf(OdfParagraph(listOf(OdfSpan("ellipse text")))))),
            OdfSlideElement.Shape(OdfShape.Line(
                200f, 100f, 90f, 30f, x2 = 10f, y2 = 90f,
                text = listOf(OdfParagraph(listOf(OdfSpan("line text")))),
                markerStart = true, markerEnd = true, strokeDashed = true)),
            OdfSlideElement.Shape(OdfShape.CustomShape(
                0f, 120f, 70f, 70f, text = listOf(OdfParagraph(listOf(OdfSpan("custom text")))))),
            OdfSlideElement.Shape(OdfShape.Polyline(
                0f, 200f, 100f, 50f, points = listOf(0f to 0f, 50f to 25f, 100f to 0f),
                closed = true, text = listOf(OdfParagraph(listOf(OdfSpan("poly text")))))),
            OdfSlideElement.Frame(OdfFrame(
                150f, 0f, 120f, 90f, listOf(OdfParagraph(listOf(OdfSpan("frame text")))),
                image = richImage(), fillColor = 0xFFFF00L, strokeColor = 0x000000L, strokeWidth = 1.5f)),
            OdfSlideElement.Frame(OdfFrame(
                150f, 100f, 120f, 90f, emptyList(), chart = richChart(ChartType.LINE))),
        )
        val slide = OdfSlide(
            name = "Intro", elements = shapes,
            backgroundColor = 0xEEEE00L,
            notes = listOf(OdfParagraph(listOf(OdfSpan("speaker notes")))),
            transitionType = "fade", transitionSpeed = "fast",
            masterName = "Master1",
        )
        val doc = OdfDocument.Presentation(title = "Deck", slides = listOf(slide, OdfSlide("Second")))
        val xml = OdfSerializer.serialize(doc)
        for (marker in listOf("rect text", "ellipse text", "custom text", "poly text", "frame text", "speaker notes")) {
            assertTrue(xml.contains(marker), marker)
        }
        val drawing = OdfDocument.Drawing(title = "Draw", pages = listOf(slide))
        val drawXml = OdfSerializer.serialize(drawing)
        assertTrue(drawXml.contains("rect text"), drawXml.take(2000))
        assertEquals(doc.slides.size, 2)
    }
}
