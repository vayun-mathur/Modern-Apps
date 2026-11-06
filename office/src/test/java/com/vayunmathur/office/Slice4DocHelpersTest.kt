package com.vayunmathur.office

import com.vayunmathur.library.ui.odf.OdfBookmark
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfChange
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfMetadata
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.ui.columnLabel
import com.vayunmathur.office.ui.countChars
import com.vayunmathur.office.ui.countWords
import com.vayunmathur.office.ui.extractHeadings
import com.vayunmathur.office.ui.readingTimeMinutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 document-helper tests: pure JVM helpers for rendering/export metadata
 * (OfficeDocumentUtils.documentToHtml/formatFileSize, ui/DocumentViews counters,
 * ui/SpreadsheetValues.columnLabel).
 */
class Slice4DocHelpersTest {

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)), style = style))

    private fun textDoc(vararg blocks: OdfContentBlock, title: String = "Doc") =
        OdfDocument.TextDocument(title, blocks.toList())

    // ---- formatFileSize ----

    @Test fun fileSizeBytes() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1023 B", formatFileSize(1023))
    }

    @Test fun fileSizeKiloAndMega() {
        assertEquals("1 KB", formatFileSize(1024))
        assertEquals("10 KB", formatFileSize(10 * 1024))
        assertEquals("1.0 MB", formatFileSize(1024 * 1024))
        assertEquals("2.5 MB", formatFileSize((2.5 * 1024 * 1024).toLong()))
    }

    // ---- DocumentViews counters ----

    @Test fun countWordsSkipsNonParagraphs() {
        val doc = textDoc(
            para("hello world"),
            OdfContentBlock.Table(OdfTable(rows = listOf(OdfTableRow(listOf(OdfTableCell()))))),
            para("  three   word  line  "),
        )
        assertEquals(5, countWords(doc))
    }

    @Test fun countWordsEmpty() {
        assertEquals(0, countWords(textDoc()))
    }

    @Test fun countCharsSumsSpans() {
        val doc = textDoc(
            OdfContentBlock.Paragraph(
                OdfParagraph(listOf(OdfSpan("ab"), OdfSpan("cde")))))
        assertEquals(5, countChars(doc))
    }

    @Test fun readingTimeMinimumOne() {
        assertEquals(1, readingTimeMinutes(textDoc()))
        assertEquals(1, readingTimeMinutes(textDoc(para("short"))))
        val long = textDoc(para(List(400) { "word" }.joinToString(" ")))
        assertEquals(2, readingTimeMinutes(long))
    }

    @Test fun extractHeadingsOnlyHeadings() {
        val doc = textDoc(
            para("Title", ParagraphStyle.HEADING1),
            para("body"),
            para("Section", ParagraphStyle.HEADING2),
            para("Deep", ParagraphStyle.HEADING4),
        )
        val h = extractHeadings(doc)
        assertEquals(3, h.size)
        assertEquals(1, h[0].level)
        assertEquals("Title", h[0].text)
        assertEquals(0, h[0].contentIndex)
        assertEquals(2, h[1].level)
        assertEquals(2, h[1].contentIndex)
        assertEquals(4, h[2].level)
    }

    @Test fun extractHeadingsJoinsSpans() {
        val doc = textDoc(
            OdfContentBlock.Paragraph(
                OdfParagraph(
                    listOf(OdfSpan("Hel"), OdfSpan("lo")),
                    style = ParagraphStyle.HEADING3)))
        val h = extractHeadings(doc)
        assertEquals(listOf("Hello"), h.map { it.text })
        assertEquals(3, h[0].level)
    }

    // ---- columnLabel ----

    @Test fun columnLabelSingle() {
        assertEquals("A", columnLabel(0))
        assertEquals("B", columnLabel(1))
        assertEquals("Z", columnLabel(25))
    }

    @Test fun columnLabelMulti() {
        assertEquals("AA", columnLabel(26))
        assertEquals("AB", columnLabel(27))
        assertEquals("AZ", columnLabel(51))
        assertEquals("BA", columnLabel(52))
    }

    // ---- documentToHtml: text ----

    @Test fun htmlTextDocumentBasics() {
        val html = documentToHtml(textDoc(para("Hello & <world>")))
        assertTrue(html.contains("<!DOCTYPE html>"), html)
        assertTrue(html.contains("Hello &amp; &lt;world>"), html)
    }

    @Test fun htmlHeadingStyles() {
        val doc = textDoc(
            para("H1", ParagraphStyle.HEADING1),
            para("H2", ParagraphStyle.HEADING2),
            para("H3", ParagraphStyle.HEADING3),
            para("H4", ParagraphStyle.HEADING4),
            para("item", ParagraphStyle.LIST_ITEM),
            para("body"),
        )
        val html = documentToHtml(doc)
        assertTrue(html.contains("<h1>H1</h1>"), html)
        assertTrue(html.contains("<h2>H2</h2>"), html)
        assertTrue(html.contains("<h3>H3</h3>"), html)
        assertTrue(html.contains("<h4>H4</h4>"), html)
        assertTrue(html.contains("<li>item</li>"), html)
        assertTrue(html.contains("<p>body</p>"), html)
    }

    @Test fun htmlSpanFormatting() {
        val doc = textDoc(
            OdfContentBlock.Paragraph(
                OdfParagraph(
                    listOf(
                        OdfSpan("b", bold = true),
                        OdfSpan("i", italic = true),
                        OdfSpan("u", underline = true),
                        OdfSpan("s", strikethrough = true),
                        OdfSpan("link", href = "https://example.com"),
                    ))))
        val html = documentToHtml(doc)
        assertTrue(html.contains("<b>b</b>"), html)
        assertTrue(html.contains("<i>i</i>"), html)
        assertTrue(html.contains("<u>u</u>"), html)
        assertTrue(html.contains("<s>s</s>"), html)
        assertTrue(html.contains("""<a href="https://example.com">link</a>"""), html)
    }

    @Test fun htmlTableAndColspans() {
        val table = OdfTable(
            rows = listOf(
                OdfTableRow(
                    listOf(
                        OdfTableCell(
                            listOf(OdfParagraph(listOf(OdfSpan("a")))),
                            colSpan = 2,
                            rowSpan = 2),
                        OdfTableCell(isCovered = true),
                    ))))
        val html = documentToHtml(textDoc(OdfContentBlock.Table(table)))
        assertTrue(html.contains("<table>"), html)
        assertTrue(html.contains("colspan=\"2\""), html)
        assertTrue(html.contains("rowspan=\"2\""), html)
        // Covered cell contributes no td.
        assertEquals(1, Regex("<td").findAll(html).count())
    }

    @Test fun htmlImageChartFormulaTocBreaks() {
        val doc = textDoc(
            OdfContentBlock.Image(
                com.vayunmathur.library.ui.odf.OdfImage("p", ByteArray(0))),
            OdfContentBlock.Chart(
                com.vayunmathur.library.ui.odf.OdfChart(
                    com.vayunmathur.library.ui.odf.ChartType.BAR,
                    emptyList(),
                    emptyList())),
            OdfContentBlock.Formula("<math><mi>x</mi></math>"),
            OdfContentBlock.PageBreak,
            OdfContentBlock.TableOfContents(
                "Contents",
                listOf(OdfParagraph(listOf(OdfSpan("entry"))))),
            OdfContentBlock.SectionStart("s", columnCount = 2),
            para("col text"),
            OdfContentBlock.SectionEnd,
            OdfContentBlock.SectionStart("plain"),
        )
        val html = documentToHtml(doc)
        assertTrue(html.contains("[Image]"), html)
        assertTrue(html.contains("[Chart]"), html)
        assertTrue(html.contains("page-break-after"), html)
        assertTrue(html.contains("Contents"), html)
        assertTrue(html.contains("column-count:2"), html)
        // Formula text comes from OdfMath parse -> toText.
        assertTrue(html.contains("x"), html)
    }

    @Test fun htmlMetadataHelpers() {
        val meta = OdfMetadata(
            title = "T",
            creationDate = "2024-01-01",
            pageCount = 3,
            wordCount = 100,
            fileSize = 2048)
        assertTrue(meta.title == "T")
        assertEquals(3, meta.pageCount)
        // buildEditedMetadata equivalent: keywords split/trim/filter is exercised via MetadataDialog save path;
        // here pin the pure pieces used by MetaStatRows.
        assertEquals("2 KB", formatFileSize(meta.fileSize!!))
    }

    // ---- documentToHtml: spreadsheet ----

    @Test fun htmlSpreadsheet() {
        val sheet = OdfSheet(
            "Sheet1",
            listOf(
                OdfRow(
                    listOf(
                        OdfCell("a&b", spannedColumns = 2),
                        OdfCell("covered", isCovered = true),
                    ))))
        val doc = OdfDocument.Spreadsheet("Book", listOf(sheet))
        val html = documentToHtml(doc)
        assertTrue(html.contains("Sheet1"), html)
        assertTrue(html.contains("a&amp;b"), html)
        assertTrue(html.contains("colspan=\"2\""), html)
        assertEquals(1, Regex("<td").findAll(html).count())
    }

    // ---- documentToHtml: presentation + drawing ----

    private fun frameDoc(vararg texts: String) =
        texts.map { t ->
            OdfSlideElement.Frame(
                OdfFrame(0f, 0f, 100f, 50f, listOf(OdfParagraph(listOf(OdfSpan(t))))))
        }

    @Test fun htmlPresentation() {
        val slides = listOf(
            OdfSlide("Intro & <Outro>", frameDoc("hello")),
            OdfSlide(
                "Shapes",
                listOf(
                    OdfSlideElement.Shape(
                        OdfShape.Rect(
                            x = 0f,
                            y = 0f,
                            width = 10f,
                            height = 10f,
                            text = listOf(OdfParagraph(listOf(OdfSpan("box")))),
                        ),
                    ),
                ),
            ),
        )
        val html = documentToHtml(OdfDocument.Presentation("Deck", slides))
        assertTrue(html.contains("Intro &amp; &lt;Outro>"), html)
        assertTrue(html.contains("hello"), html)
        assertTrue(html.contains("box"), html)
    }

    @Test fun htmlDrawing() {
        val pages = listOf(OdfSlide("Page 1", frameDoc("drawn")))
        val html = documentToHtml(OdfDocument.Drawing("Art", pages))
        assertTrue(html.contains("Page 1"), html)
        assertTrue(html.contains("drawn"), html)
    }

    @Test fun bookmarksAndChangesModels() {
        val bk = OdfBookmark("ch1", 4)
        assertEquals("ch1", bk.name)
        assertEquals(4, bk.contentIndex)
        val ch = OdfChange("c1", "insertion", author = "me", date = "2024-05-01T10:00:00")
        assertEquals("c1", ch.id)
        assertEquals("2024-05-01", ch.date!!.take(10))
    }
}
