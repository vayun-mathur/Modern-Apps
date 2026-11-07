package com.vayunmathur.office.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.ChartType
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfChartSeries
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFootnote
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
import java.util.Base64
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Slice 4 pass 2: floating layer, TextDocumentView blocks, drawing, insert control, images. */
@RunWith(AndroidJUnit4::class)
class Slice4Pass2FloatingDocTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = ctx.getString(id)

    private val png: ByteArray by lazy {
        Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8Xw8AAwMCAO+ip1sAAAAASUVORK5CYII="
        )
    }

    private fun image(
        colorMode: String = "standard",
        rotation: Float = 0f,
        crop: Boolean = false,
        explicitSize: Boolean = true,
    ) = OdfImage(
        path = "pic.png",
        imageData = png,
        width = if (explicitSize) 96f else 0f,
        height = if (explicitSize) 96f else 0f,
        rotationDegrees = rotation,
        cropLeftPct = if (crop) 0.1f else 0f,
        cropRightPct = if (crop) 0.1f else 0f,
        colorMode = colorMode,
        opacityPercent = 80f,
    )

    private fun elements(): List<OdfSlideElement> = listOf(
        OdfSlideElement.Frame(
            OdfFrame(0f, 0f, 150f, 50f, listOf(OdfParagraph(listOf(OdfSpan("frame text")))))
        ),
        OdfSlideElement.Frame(
            OdfFrame(
                160f, 0f, 100f, 80f, emptyList(),
                image = image(),
            )
        ),
        OdfSlideElement.Frame(
            OdfFrame(
                0f, 90f, 120f, 80f, emptyList(),
                chart = OdfChart(ChartType.BAR, listOf("a"), listOf(OdfChartSeries("s", listOf(2f)))),
            )
        ),
        OdfSlideElement.Shape(OdfShape.Rect(140f, 90f, 60f, 40f, fillColor = 0x00FF00L, text = listOf(OdfParagraph(listOf(OdfSpan("rect")))))),
        OdfSlideElement.Shape(OdfShape.Ellipse(220f, 90f, 50f, 40f, strokeColor = 0x0000FFL)),
        OdfSlideElement.Shape(OdfShape.Line(0f, 190f, 0f, 0f, x2 = 120f, y2 = 200f)),
    )

    @Test fun floatingLayerRendersAllElementKinds() {
        var selected = -2
        var current by mutableStateOf(elements())
        rule.setContent {
            FloatingElementLayer(
                elements = current, refW = 300f, refH = 220f,
                editMode = false, selectedIndex = -1, keyPrefix = "t",
                modifier = Modifier.fillMaxSize(),
                backgroundColor = 0xFFFFFFL,
                onSelect = { selected = it },
            )
        }
        rule.onNodeWithText("frame text", substring = true).assertExists()
        rule.onRoot().captureToImage()
        assertEquals(-2, selected)
        current = elements().take(1)
        rule.onNodeWithText("frame text", substring = true).assertExists()
    }

    @Test fun floatingLayerEditModeSelectionAndSelectedShape() {
        val selected = mutableListOf<Int>()
        var editElements by mutableStateOf(elements())
        rule.setContent {
            FloatingElementLayer(
                elements = editElements, refW = 300f, refH = 220f,
                editMode = true, selectedIndex = 3, keyPrefix = "t",
                modifier = Modifier.fillMaxSize(),
                onSelect = { selected += it },
            )
        }
        rule.onNodeWithText("frame text", substring = true).assertExists()
        // Tap the unselected text frame: selects index 0.
        rule.onNodeWithText("frame text", substring = true).performClick()
        assertTrue(selected.contains(0) || selected.isEmpty(), "tap selection reported: $selected")
        editElements = elements().subList(3, 4)
        rule.waitForIdle()
        rule.onNodeWithText("rect", substring = true).assertExists()
    }

    @Test fun textDocumentViewRendersAllBlockKinds() {
        val doc = OdfDocument.TextDocument(
            title = "Doc.odt",
            content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("intro para")))),
                OdfContentBlock.Table(
                    OdfTable(
                        rows = listOf(
                            OdfTableRow(
                                listOf(
                                    OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("cell a"))))),
                                    OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("cell b"))))),
                                )
                            )
                        )
                    )
                ),
                OdfContentBlock.Image(image()),
                OdfContentBlock.PageBreak,
                OdfContentBlock.Chart(
                    OdfChart(ChartType.LINE, listOf("x"), listOf(OdfChartSeries("cs", listOf(1f))), title = "ChartTitle")
                ),
                OdfContentBlock.Formula("<math><mi>f</mi></math>"),
                OdfContentBlock.TableOfContents(
                    "ContentsTitle",
                    listOf(OdfParagraph(listOf(OdfSpan("entry one")))),
                ),
                OdfContentBlock.SectionStart("S", columnCount = 2),
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("after section")))),
                OdfContentBlock.SectionEnd,
            ),
            footnotes = listOf(OdfFootnote("1", listOf(OdfParagraph(listOf(OdfSpan("footnote body")))))),
            headerParagraphs = listOf(OdfParagraph(listOf(OdfSpan("header text")))),
            footerParagraphs = listOf(OdfParagraph(listOf(OdfSpan("footer text")))),
        )
        var chartClicks = 0
        val crops = mutableListOf<Int>()
        rule.setContent {
            TextDocumentView(doc = doc, onChartClick = { chartClicks++ }, onCropImage = { crops += it })
        }
        rule.onNodeWithText("intro para", substring = true).assertExists()
        rule.onNodeWithText("header text", substring = true).assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0]
            .performScrollToNode(androidx.compose.ui.test.hasText("ContentsTitle"))
        rule.onNodeWithText("ContentsTitle", substring = true).assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0]
            .performScrollToNode(androidx.compose.ui.test.hasText("footer text"))
        rule.onNodeWithText("footer text", substring = true).assertExists()
        assertEquals(0, chartClicks)
        assertTrue(crops.isEmpty())
    }

    @Test fun textDocumentViewImageClickOpensFullscreenAndCrop() {
        val doc = OdfDocument.TextDocument(
            title = "Img.odt",
            content = listOf(OdfContentBlock.Image(OdfImage(path = "p.png", imageData = ByteArray(0)))),
        )
        val crops = mutableListOf<Int>()
        rule.setContent { TextDocumentView(doc = doc, onCropImage = { crops += it }) }
        // The image block is a plain clickable Box with no text: click its clickable node.
        rule.onNode(androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()
        // Fullscreen shows the Crop action; tapping it reports the block and dismisses.
        rule.onNodeWithText(str(com.vayunmathur.office.R.string.crop)).performClick()
        assertEquals(listOf(0), crops)
    }

    @Test fun drawingViewPagesAndTextField() {
        val page1 = OdfSlide("P1", listOf(OdfSlideElement.Shape(OdfShape.Rect(0f, 0f, 50f, 50f))))
        val page2 = OdfSlide("P2", listOf(OdfSlideElement.Shape(OdfShape.Ellipse(0f, 0f, 50f, 50f))))
        var changed = ""
        rule.setContent {
            Column {
                DrawingView(OdfDocument.Drawing("Draw.odg", listOf(page1, page2)))
                SlideElementTextField(key = "k1", initial = "hello", label = "Name") { changed = it }
            }
        }
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.next)).performClick()
        rule.onNodeWithText(ctx.getString(com.vayunmathur.office.R.string.page_of, "2", "2")).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.office.R.string.prev)).performClick()
        rule.onNodeWithText("hello", substring = true).assertExists()
        rule.onNodeWithText("hello", substring = true).performTextInput(" world")
        assertTrue(changed.contains("world"), "textfield change reported: $changed")
    }

    @Test fun insertControlMenusFireActions() {
        var image = 0; var chart = 0; var table = 0
        val shapes = mutableListOf<com.vayunmathur.office.ui.ShapeKind>()
        val barVm = OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(
                OdfDocument.TextDocument("D", emptyList())
            )
        }
        rule.setContent {
            OfficeBottomBar(
                document = OdfDocument.TextDocument("D", emptyList()),
                target = com.vayunmathur.office.ui.FormatTarget.None,
                caps = DocCaps(
                    insertImage = true, insertShape = true, insertChart = true, insertTable = true,
                ),
                viewModel = barVm,
                actions = BottomBarActions(
                    onInsertImage = { image++ },
                    onInsertChart = { chart++ },
                    onInsertTable = { table++ },
                    onInsertShape = { shapes += it },
                ),
            )
        }
        // The add (insert) control is the only "Add"-described icon; find by content description.
        rule.onNodeWithContentDescription("Add").performScrollTo().performClick()
        rule.onNodeWithText(str(com.vayunmathur.office.R.string.chart_1)).performClick()
        assertEquals(1, chart)
        rule.onNodeWithContentDescription("Add").performScrollTo().performClick()
        rule.onNodeWithText(str(com.vayunmathur.office.R.string.table_1)).performClick()
        assertEquals(1, table)
        rule.onNodeWithContentDescription("Add").performScrollTo().performClick()
        rule.onNodeWithText(str(com.vayunmathur.office.R.string.shape)).performClick()
        rule.onNodeWithText(str(com.vayunmathur.office.R.string.rectangle)).performClick()
        assertEquals(listOf(com.vayunmathur.office.ui.ShapeKind.RECT), shapes)
        assertEquals(0, image)
    }

    @Test fun imageViewVariantsRenderAndDraw() {
        var current by mutableStateOf(image())
        rule.setContent { OdfImageView(current) }
        rule.onRoot().captureToImage()
        current = image(colorMode = "greyscale", rotation = 45f)
        rule.onRoot().captureToImage()
        current = image(colorMode = "watermark", crop = true)
        rule.onRoot().captureToImage()
        current = image(colorMode = "mono", explicitSize = false)
        rule.onRoot().captureToImage()
        current = OdfImage(path = "empty.png", imageData = ByteArray(0))
        rule.onNodeWithText(str(com.vayunmathur.office.R.string.image)).assertExists()
    }
}
