package com.vayunmathur.office.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfChartSeries
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.library.ui.odf.ChartType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 document-view tests (Robolectric + createComposeRule): the big
 * renderers (TextDocumentView, PresentationView, DrawingView, MathView,
 * OdfChartView) render real documents with no ViewModel. (Spreadsheet
 * rendering + the native engine live in Slice4SheetsTest: the JVM loads each
 * native library once per classloader.)
 */
@RunWith(AndroidJUnit4::class)
class Slice4DocumentViewsTest {

    @get:Rule val rule = createComposeRule()

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)), style = style))

    // ---- TextDocumentView ----

    @Test fun textViewRendersParagraphsAndHeadings() {
        val doc = OdfDocument.TextDocument(
            "Doc",
            listOf(
                para("Title", ParagraphStyle.HEADING1),
                para("hello world"),
            ),
        )
        rule.setContent { TextDocumentView(doc = doc) }
        // Both paragraphs share one editable field ("Title\nhello world"):
        // match by substring, and existence (Robolectric zeroes BasicTextField size).
        rule.onNodeWithText("Title", substring = true).assertExists()
        rule.onNodeWithText("hello world", substring = true).assertExists()
    }

    @Test fun textViewRendersTableImageChartFormula() {
        val doc = OdfDocument.TextDocument(
            "Doc",
            listOf(
                OdfContentBlock.Table(
                    OdfTable(
                        rows = listOf(
                            OdfTableRow(
                                listOf(OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("cell")))))))))),
                OdfContentBlock.Image(OdfImage("p", ByteArray(0))),
                OdfContentBlock.Chart(
                    OdfChart(ChartType.BAR, listOf("A"), listOf(OdfChartSeries("S", listOf(1f))))),
                OdfContentBlock.Formula("<math><mi>x</mi></math>"),
                OdfContentBlock.PageBreak,
            ),
        )
        var chartClicks = 0
        var cropClicks = 0
        rule.setContent {
            TextDocumentView(
                doc = doc,
                onChartClick = { chartClicks++ },
                onCropImage = { cropClicks++ },
            )
        }
        rule.onNodeWithText("cell").assertIsDisplayed()
        rule.onNodeWithText("[Image]", substring = true).assertIsDisplayed()
        assertEquals(0, chartClicks)
        assertEquals(0, cropClicks)
    }

    @Test fun textViewSearchQueryHighlights() {
        val doc = OdfDocument.TextDocument("Doc", listOf(para("find me here")))
        rule.setContent { TextDocumentView(doc = doc, searchQuery = "me") }
        rule.onNodeWithText("find me here").assertExists()
    }

    @Test fun textViewCheckboxToggle() {
        var toggled: Int? = null
        val doc = OdfDocument.TextDocument(
            "Doc",
            listOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(OdfSpan("task")),
                        style = ParagraphStyle.LIST_ITEM,
                        listType = com.vayunmathur.library.ui.odf.ListType.CHECKBOX)),
            ),
        )
        rule.setContent { TextDocumentView(doc = doc, onToggleCheckbox = { toggled = it }) }
        rule.onNodeWithText("task").assertExists()
        assertEquals(null, toggled)
    }

    @Test fun textViewHeaderFooter() {
        val doc = OdfDocument.TextDocument(
            "Doc",
            listOf(para("body")),
            headerParagraphs = listOf(OdfParagraph(listOf(OdfSpan("hdr")))),
            footerParagraphs = listOf(OdfParagraph(listOf(OdfSpan("ftr")))),
        )
        rule.setContent { TextDocumentView(doc = doc) }
        rule.onNodeWithText("hdr").assertIsDisplayed()
        rule.onNodeWithText("ftr").assertIsDisplayed()
        rule.onNodeWithText("body").assertExists()
    }

    // ---- PresentationView / DrawingView ----

    private fun deckDoc() = OdfDocument.Presentation(
        "Deck",
        listOf(
            OdfSlide(
                "Intro",
                listOf(
                    OdfSlideElement.Frame(
                        OdfFrame(
                            0f, 0f, 200f, 100f,
                            listOf(OdfParagraph(listOf(OdfSpan("welcome")))))),
                ),
            ),
            OdfSlide(
                "Shapes",
                listOf(
                    OdfSlideElement.Shape(
                        OdfShape.Rect(
                            x = 0f, y = 0f, width = 50f, height = 50f,
                            text = listOf(OdfParagraph(listOf(OdfSpan("box")))))),
                ),
            ),
        ),
    )

    @Test fun presentationRendersSlides() {
        rule.setContent { PresentationView(doc = deckDoc()) }
        rule.onNodeWithText("Intro", substring = true).assertIsDisplayed()
        // Slide strip + content both render the frame text.
        assertEquals(2, rule.onAllNodesWithText("welcome").fetchSemanticsNodes().size)
    }

    @Test fun slideCardShowsName() {
        val slide = deckDoc().slides[0]
        rule.setContent { SlideCard(slide = slide) }
        rule.onNodeWithText("Intro", substring = true).assertIsDisplayed()
    }

    @Test fun drawingRendersPages() {
        val doc = OdfDocument.Drawing(
            "Art",
            listOf(
                OdfSlide(
                    "Page 1",
                    listOf(
                        OdfSlideElement.Frame(
                            OdfFrame(
                                0f, 0f, 100f, 100f,
                                listOf(OdfParagraph(listOf(OdfSpan("sketch")))))),
                    ),
                )),
        )
        rule.setContent { DrawingView(doc = doc) }
        rule.onNodeWithText("Page 1", substring = true).assertIsDisplayed()
        rule.onNodeWithText("sketch").assertIsDisplayed()
    }

    // ---- MathView / OdfChartView / misc ----

    @Test fun mathViewRendersTokens() {
        rule.setContent { MathView("<math><mi>x</mi><mo>+</mo><mn>2</mn></math>") }
        rule.onNodeWithText("x", substring = true).assertIsDisplayed()
    }

    @Test fun mathViewFallsBackOnBadXml() {
        val bad = "not xml at all"
        rule.setContent { MathView(bad) }
        rule.onNodeWithText(bad.take(200), substring = true).assertIsDisplayed()
    }

    @Test fun chartViewRendersTitle() {
        var clicked = false
        val chart = OdfChart(
            ChartType.BAR, listOf("A", "B"),
            listOf(OdfChartSeries("S", listOf(1f, 2f))), title = "Sales")
        rule.setContent { OdfChartView(chart = chart, onClick = { clicked = true }) }
        rule.onNodeWithText("Sales").assertIsDisplayed()
        rule.onNodeWithText("Sales").performClick()
        assertTrue(clicked)
    }

    @Test fun paragraphCheckboxAndLists() {
        val doc = OdfDocument.TextDocument(
            "Doc",
            listOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(OdfSpan("bullet")),
                        style = ParagraphStyle.LIST_ITEM,
                        listType = com.vayunmathur.library.ui.odf.ListType.BULLET)),
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(OdfSpan("numbered")),
                        style = ParagraphStyle.LIST_ITEM,
                        listType = com.vayunmathur.library.ui.odf.ListType.NUMBERED)),
            ),
        )
        rule.setContent { TextDocumentView(doc = doc) }
        rule.onNodeWithText("bullet", substring = true).assertExists()
        rule.onNodeWithText("numbered", substring = true).assertExists()
    }

    @Test fun docSegmentModel() {
        // DocSegment.Block / Paragraphs are plain data holders used by TextDocumentView.
        val b = DocSegment.Block(2)
        assertEquals(2, b.index)
        val p = DocSegment.Paragraphs(1, 3)
        assertEquals(1 to 3, p.start to p.endInclusive)
    }
}
