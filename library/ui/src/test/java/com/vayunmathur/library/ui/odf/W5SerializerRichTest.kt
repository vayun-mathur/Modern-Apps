package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W5: maximal-construct serializer/exporter coverage — paragraphs and spans
 * using every extended property (so the serializer's style-def tables are
 * built in all shapes), presentation/spreadsheet serialization, OOXML export
 * of slides with every element kind, and the multi-paragraph run edits.
 * Assertions pin the emitted XML/text, not mere non-null results.
 */
class W5SerializerRichTest {

    private fun richParagraph(): OdfParagraph = OdfParagraph(
        spans = listOf(
            OdfSpan(
                text = "rich", bold = true, italic = true, underline = true,
                strikethrough = true, color = 0xFF0000L, backgroundColor = 0x00FF00L,
                fontSize = 18f, fontFamily = "Liberation Serif",
                underlineStyle = "double", underlineColor = 0x0000FFL,
                letterSpacing = 1.5f, textTransform = "uppercase",
                language = "en", country = "US",
            ),
            OdfSpan(text = " super", superscript = true),
            OdfSpan(text = " sub", subscript = true),
            OdfSpan(text = " link", href = "https://example.org"),
        ),
        alignment = TextAlign.Center, marginLeft = 12f, marginTop = 6f,
        marginBottom = 8f, marginRight = 4f, textIndent = 10f,
        backgroundColor = 0xFFFF00L, lineHeightPercent = 1.5f,
        borderColor = 0x123456L, breakBeforePage = true,
        keepWithNext = true, keepTogether = true, widows = 2, orphans = 3,
        tabStops = listOf(48f, 96f),
        tabStopDetails = listOf(
            OdfTabStop(48f, "right", "."),
            OdfTabStop(96f, "center", null),
            OdfTabStop(144f, "char", ","),
        ),
        dropCapLines = 2, dropCapLength = 3, padding = 5f,
    )

    private fun richDoc(): OdfDocument.TextDocument = OdfDocument.TextDocument(
        title = "Rich.odt",
        content = listOf(
            OdfContentBlock.Paragraph(richParagraph()),
            OdfContentBlock.Paragraph(OdfParagraph(
                spans = listOf(OdfSpan("heading")),
                style = ParagraphStyle.HEADING1,
            )),
            OdfContentBlock.Paragraph(OdfParagraph(
                spans = listOf(OdfSpan("item")),
                style = ParagraphStyle.LIST_ITEM, listType = ListType.NUMBERED,
                listItemIndex = 3, listLevel = 2, listNumberFormat = "A",
                listNumberPrefix = "(", listNumberSuffix = ")",
            )),
            OdfContentBlock.Paragraph(OdfParagraph(
                spans = listOf(OdfSpan("checked")),
                style = ParagraphStyle.LIST_ITEM, listType = ListType.CHECKBOX,
                listChecked = true,
            )),
            OdfContentBlock.Table(OdfTable(
                rows = listOf(OdfTableRow(listOf(
                    OdfTableCell(paragraphs = listOf(richParagraph()), backgroundColor = 0xABCDEFL),
                    OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan("c2")))), colSpan = 2),
                ))),
            )),
        ),
    )

    @Test fun richTextSerializesEveryConstruct() {
        val xml = OdfSerializer.serialize(richDoc())
        assertTrue(xml.contains("rich"), xml.take(2000))
        assertTrue(xml.contains("heading"), xml)
        assertTrue(xml.contains("item"), xml)
        assertTrue(xml.contains("checked"), xml)
        assertTrue(xml.contains("c2"), xml)
        assertTrue(xml.contains("https://example.org"), xml)
        // The packaged form splits content/styles/meta but keeps the text.
        val packaged = OdfSerializer.serializePackaged(richDoc())
        assertTrue(packaged.contentXml.contains("rich"), packaged.contentXml.take(2000))
        val styles = OdfSerializer.serializeStyles(richDoc())
        assertTrue(styles.contains("office:document-styles"), styles.take(500))
        // Serializing twice is deterministic (style tables are rebuilt per call).
        assertEquals(xml, OdfSerializer.serialize(richDoc()))
    }

    @Test fun flatAndPackagedAgreeOnText() {
        val flat = OdfSerializer.serializeFlat(richDoc())
        assertTrue(flat.contains("<office:document"), flat.take(300))
        assertTrue(flat.contains("rich"), flat.take(2000))
    }

    private fun slideDoc(): OdfDocument.Presentation = OdfDocument.Presentation(
        title = "Deck.odp",
        slides = listOf(
            OdfSlide(
                name = "S1",
                elements = listOf(
                    OdfSlideElement.Shape(OdfShape.Rect(
                        0f, 0f, 100f, 50f, fillColor = 0xFF0000L, strokeColor = 0x00FF00L,
                        strokeWidth = 2f, strokeDashed = true,
                        fillGradient = OdfGradient(0xFF0000L, 0x0000FFL, angle = 90f),
                        text = listOf(OdfParagraph(listOf(OdfSpan("rect text")))))),
                    OdfSlideElement.Shape(OdfShape.Ellipse(120f, 0f, 60f, 40f, fillColor = 0xFFFF00L)),
                    OdfSlideElement.Shape(OdfShape.Line(0f, 80f, 50f, 20f, x2 = 90f, y2 = 60f, markerEnd = true)),
                    OdfSlideElement.Shape(OdfShape.Polyline(
                        0f, 120f, 80f, 40f, points = listOf(0f to 120f, 40f to 160f, 80f to 120f), closed = true)),
                    OdfSlideElement.Shape(OdfShape.CustomShape(100f, 120f, 40f, 40f, fillColor = 0x00FFFFL)),
                    OdfSlideElement.Frame(OdfFrame(
                        160f, 100f, 120f, 60f,
                        listOf(OdfParagraph(listOf(OdfSpan("frame text")))),
                        fillColor = 0xFF00FFL, strokeColor = 0x000000L)),
                ),
                backgroundColor = 0xEEEEEE,
            ),
            OdfSlide(name = "S2", elements = emptyList()),
        ),
    )

    @Test fun presentationSerializesShapesAndExportsPptx() {
        val xml = OdfSerializer.serialize(slideDoc())
        assertTrue(xml.contains("rect text"), xml.take(3000))
        assertTrue(xml.contains("frame text"), xml)
        val pptx = OoxmlExporter.export(slideDoc())
        assertTrue(pptx.isNotEmpty())
        // A PPTX is a zip package.
        assertEquals('P'.code.toByte(), pptx[0])
        assertEquals('K'.code.toByte(), pptx[1])
        val docx = OoxmlExporter.export(richDoc())
        assertEquals('P'.code.toByte(), docx[0])
        assertEquals('K'.code.toByte(), docx[1])
    }

    @Test fun spreadsheetSerializesAndExportsXlsx() {
        val sheet = OdfSheet(
            name = "Data",
            rows = listOf(
                OdfRow(listOf(
                    OdfCell(text = "h1", bold = true, backgroundColor = 0xFF0000L),
                    OdfCell(text = "42", numberValue = 42.0, formula = "=SUM(A1:A2)"),
                    OdfCell(text = "merged", spannedColumns = 2, hyperlink = "https://example.org"),
                    OdfCell(text = "covered", isCovered = true),
                )),
                OdfRow(listOf(OdfCell(text = "x"))),
            ),
        )
        val doc = OdfDocument.Spreadsheet(title = "Book.ods", sheets = listOf(sheet))
        val xml = OdfSerializer.serialize(doc)
        assertTrue(xml.contains("Data"), xml.take(1000))
        assertTrue(xml.contains("42"), xml)
        val xlsx = OoxmlExporter.export(doc)
        assertEquals('P'.code.toByte(), xlsx[0])
        assertEquals('K'.code.toByte(), xlsx[1])
    }

    // ---- run edits ----

    private fun runDoc(): OdfDocument.TextDocument = OdfDocument.TextDocument(
        title = "Run.odt",
        content = listOf("alpha", "bravo", "charlie", "delta").map {
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(it))))
        },
    )

    private fun texts(doc: OdfDocument.TextDocument): List<String> =
        doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .map { it.paragraph.spans.joinToString("") { s -> s.text } }

    @Test fun updateParagraphRunWithinAndAcrossParagraphs() {
        val doc = runDoc()
        assertEquals(listOf("alpha", "bravo", "charlie", "delta"), texts(doc))
        // Same text is a no-op.
        assertNull(doc.updateParagraphRun(0, 1, "alpha\nbravo"))
        // Invalid ranges are a no-op.
        assertNull(doc.updateParagraphRun(9, 12, "nope"))
        // Edit inside one paragraph of the run.
        val one = doc.updateParagraphRun(0, 0, "ALPHa")
        assertNotNull(one)
        assertEquals(listOf("ALPHa", "bravo", "charlie", "delta"), texts(one))
        // Replace a span crossing a paragraph boundary with a single line:
        // the tail of the end paragraph is preserved.
        val joined = doc.updateParagraphRun(0, 1, "alpha\nbraXo")
        assertNotNull(joined)
        assertEquals("braXo", texts(joined)[1])
        // Replace across paragraphs with MORE lines than the original run:
        // new paragraphs are created, surrounding paragraphs untouched.
        val grown = doc.updateParagraphRun(1, 2, "bravo\nnew middle\ncharlie")
        assertNotNull(grown)
        assertEquals(listOf("alpha", "bravo", "new middle", "charlie", "delta"), texts(grown))
        // Replace across paragraphs with FEWER lines.
        val shrunk = doc.updateParagraphRun(1, 2, "merged")
        assertNotNull(shrunk)
        assertEquals(listOf("alpha", "mergedlie", "delta").size, texts(shrunk).size)
        assertEquals("alpha", texts(shrunk)[0])
        assertEquals("delta", texts(shrunk).last())
    }

    @Test fun runSpanStylesAndLinksApplyAcrossParagraphs() {
        val doc = runDoc()
        val bolded = doc.applyRunSpanStyle(0, 1, 0, 7) { it.copy(bold = true) }
        assertNotNull(bolded)
        val paras = bolded.content.filterIsInstance<OdfContentBlock.Paragraph>()
        assertTrue(paras[0].paragraph.spans.all { it.bold })
        // A caret (empty range) styles the whole containing paragraph run char set.
        val caret = doc.applyRunSpanStyle(0, 1, 2, 2) { it.copy(italic = true) }
        assertNotNull(caret)
        val linked = doc.setLinkInRun(0, 1, 0, 5, "alpha", "https://example.org")
        assertNotNull(linked)
        val first = linked.content.filterIsInstance<OdfContentBlock.Paragraph>()[0].paragraph
        assertTrue(first.spans.any { it.href == "https://example.org" }, first.spans.toString())
    }

    @Test fun listNumberFormats() {
        assertEquals("1", formatListNumber(1, "1"))
        assertEquals("42", formatListNumber(42, "1"))
        assertEquals("A", formatListNumber(1, "A"))
        assertEquals("Z", formatListNumber(26, "A"))
        assertEquals("AA", formatListNumber(27, "A"))
        assertEquals("a", formatListNumber(1, "a"))
        assertEquals("I", formatListNumber(1, "I"))
        assertEquals("IV", formatListNumber(4, "I"))
        assertEquals("IX", formatListNumber(9, "i").uppercase())
        assertEquals("XL", formatListNumber(40, "I"))
        assertEquals("CM", formatListNumber(900, "I"))
        assertEquals("MMXXIV", formatListNumber(2024, "I"))
        assertEquals("4000", formatListNumber(4000, "I")) // beyond Roman range -> decimal
        assertEquals("1", formatListNumber(0, "1")) // coerced to 1-based
        assertEquals("1", formatListNumber(-5, "1"))
    }
}
