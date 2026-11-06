package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Slice 3/4, round 4c: plain-text export branches on [OfficeViewModel] — every
 * [OdfContentBlock] kind, multi-sheet spreadsheets with covered cells, and
 * frame-vs-shape slide/drawing elements.
 *
 * `exportAsPlainText`/`appendTextBlockPlain`/`appendSheetPlain`/
 * `appendSlideElementsPlain` carry the largest missed-instruction counts in the
 * non-sync slice files, and all branches run with pure in-memory documents.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfficeViewModelExportBranchesSlice3Test : OfficeViewModelSlice3Harness() {

    private fun loaded(doc: OdfDocument): OfficeViewModel {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        return vm
    }

    private fun para(text: String) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text))))

    @Test
    fun `plain text covers every text block kind`() = runTest {
        val chart = com.vayunmathur.library.ui.odf.OdfChart(
            type = com.vayunmathur.library.ui.odf.ChartType.BAR,
            categories = listOf("a"),
            series = listOf(com.vayunmathur.library.ui.odf.OdfChartSeries("s", listOf(1f))),
        )
        val doc = OdfDocument.TextDocument(
            title = "T",
            content = listOf(
                para("para one"),
                OdfContentBlock.Table(OdfTable("T", rows = listOf(
                    OdfTableRow(listOf(
                        OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("c00"))))),
                        OdfTableCell(
                            listOf(OdfParagraph(listOf(OdfSpan("hidden")))),
                            isCovered = true),
                        OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("c02"))))),
                    )),
                ))),
                OdfContentBlock.Image(OdfImage(path = "p", imageData = byteArrayOf())),
                OdfContentBlock.Chart(chart),
                OdfContentBlock.Formula(
                    "<math xmlns=\"http://www.w3.org/1998/Math/MathML\">" +
                        "<mrow><mi>x</mi></mrow></math>"),
                OdfContentBlock.TableOfContents(
                    "Contents",
                    listOf(OdfParagraph(listOf(OdfSpan("entry one"))))),
                OdfContentBlock.SectionStart("S1"),
                OdfContentBlock.SectionEnd,
                OdfContentBlock.PageBreak,
            ),
        )
        val plain = loaded(doc).exportAsPlainText()
        assertTrue(plain.contains("para one"), plain)
        // Table row skips the covered cell but keeps the visible ones.
        assertTrue(plain.contains("c00"), plain)
        assertTrue(plain.contains("c02"), plain)
        assertTrue(!plain.contains("hidden"), plain)
        assertTrue(plain.contains("[Image]"), plain)
        assertTrue(plain.contains("[Chart]"), plain)
        assertTrue(plain.contains("[Formula]"), plain)
        assertTrue(plain.contains("Contents"), plain)
        assertTrue(plain.contains("entry one"), plain)
        assertTrue(plain.contains("---"), plain)
    }

    @Test
    fun `plain text covers multi-sheet spreadsheets`() = runTest {
        fun row(vararg texts: String) = OdfRow(texts.map { OdfCell(it) })
        val doc = OdfDocument.Spreadsheet(
            title = "B",
            sheets = listOf(
                OdfSheet("First", listOf(row("a1", "b1"))),
                OdfSheet(
                    "Second",
                    listOf(OdfRow(listOf(
                        OdfCell("v00"),
                        OdfCell("hidden", isCovered = true),
                        OdfCell("v02"),
                    ))),
                ),
            ),
        )
        val plain = loaded(doc).exportAsPlainText()
        assertTrue(plain.contains("=== First ==="), plain)
        assertTrue(plain.contains("=== Second ==="), plain)
        assertTrue(plain.contains("a1"), plain)
        assertTrue(plain.contains("v00"), plain)
        assertTrue(!plain.contains("hidden"), plain)
    }

    @Test
    fun `plain text covers frame and shape slide elements`() = runTest {
        val frameEl = OdfSlideElement.Frame(OdfFrame(
            x = 0f, y = 0f, width = 10f, height = 10f,
            paragraphs = listOf(OdfParagraph(listOf(OdfSpan("frame text"))))))
        val shapeEl = OdfSlideElement.Shape(com.vayunmathur.library.ui.odf.OdfShape.Rect(
            0f, 0f, 10f, 10f,
            text = listOf(OdfParagraph(listOf(OdfSpan("shape text"))))))
        val pres = OdfDocument.Presentation(
            title = "P",
            slides = listOf(OdfSlide(name = "S1", elements = listOf(frameEl, shapeEl))),
        )
        val plain = loaded(pres).exportAsPlainText()
        assertTrue(plain.contains("=== S1 ==="), plain)
        assertTrue(plain.contains("frame text"), plain)
        assertTrue(plain.contains("shape text"), plain)
        val drawing = OdfDocument.Drawing(
            title = "G",
            pages = listOf(OdfSlide(name = "Pg", elements = listOf(shapeEl))),
        )
        val dplain = loaded(drawing).exportAsPlainText()
        assertTrue(dplain.contains("=== Pg ==="), dplain)
        assertTrue(dplain.contains("shape text"), dplain)
    }

    @Test
    fun `plain text table with multi-paragraph cells`() = runTest {
        val cell = OdfTableCell(listOf(
            OdfParagraph(listOf(OdfSpan("l1"))),
            OdfParagraph(listOf(OdfSpan("l2"))),
        ))
        val doc = OdfDocument.TextDocument(
            title = "T",
            content = listOf(
                OdfContentBlock.Table(OdfTable("T", rows = listOf(OdfTableRow(listOf(cell))))),
            ),
        )
        val plain = loaded(doc).exportAsPlainText()
        assertTrue(plain.contains("l1 l2"), plain)
    }
}
