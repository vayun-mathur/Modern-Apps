package com.vayunmathur.office.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.platform.OfficeViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * W4: [OfficeBottomBar] for all three document kinds — format buttons
 * drive the real ViewModel, insert/menu actions fire the host callbacks.
 */
@RunWith(AndroidJUnit4::class)
class W4BottomBarTest {

    @get:Rule val rule = createComposeRule()

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun clickDesc(desc: String) {
        rule.onNodeWithContentDescription(desc).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun clickText(text: String) {
        rule.onNodeWithText(text, substring = true).performClick()
        rule.waitForIdle()
    }

    @Test fun textBottomBarFormatsAndInserts() {
        val doc = OdfDocument.TextDocument(
            "T",
            listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("format me"))))))
        val viewModel = vm(doc)
        val fired = mutableListOf<String>()
        rule.setContent {
            OfficeBottomBar(
                document = doc,
                target = FormatTarget.TextRun(0, 0, 0, 6),
                caps = DocCaps(insertImage = true, insertShape = true, insertChart = true, insertTable = true),
                viewModel = viewModel,
                actions = BottomBarActions(
                    onTextColor = { fired.add("textColor") },
                    onFontSize = { fired.add("fontSize") },
                    onInsertImage = { fired.add("image") },
                    onInsertShape = { fired.add("shape:$it") },
                    onInsertChart = { fired.add("chart") },
                    onInsertTable = { fired.add("table") },
                ),
            )
        }
        clickDesc("Add")
        clickText("Image")
        assertEquals(listOf("image"), fired)
        clickDesc("Bold")
        val updated = (viewModel.state.value as OfficeViewModel.ViewState.Loaded).document
            as OdfDocument.TextDocument
        val span = (updated.content[0] as OdfContentBlock.Paragraph).paragraph.spans.first()
        assertTrue(span.bold, "bold should apply to the run: $span")
        clickDesc("Text color")
        assertEquals(listOf("image", "textColor"), fired)
    }

    @Test fun cellBottomBarFiresCellActions() {
        val sheet = OdfSheet(
            "Data",
            listOf(OdfRow(listOf(OdfCell("v1"), OdfCell("v2")))))
        val doc = OdfDocument.Spreadsheet("Book", listOf(sheet))
        val viewModel = vm(doc)
        val fired = mutableListOf<String>()
        rule.setContent {
            OfficeBottomBar(
                document = doc,
                target = FormatTarget.Cell(0, 0, 1),
                caps = DocCaps(insertChart = true, insertTable = true),
                viewModel = viewModel,
                actions = BottomBarActions(
                    onCellTextColor = { fired.add("cellTextColor") },
                    onCellBgColor = { fired.add("cellBg") },
                    onCellBorder = { fired.add("border") },
                    onCellComment = { fired.add("comment") },
                    onCellResize = { fired.add("resize") },
                ),
            )
        }
        clickDesc("Text color")
        clickText("Fill")
        assertEquals(listOf("cellTextColor", "cellBg"), fired)
    }

    @Test fun elementBottomBarFiresSlideActions() {
        val slide = OdfSlide(
            "S1",
            listOf(
                OdfSlideElement.Frame(
                    OdfFrame(0f, 0f, 100f, 50f, listOf(OdfParagraph(listOf(OdfSpan("el"))))))))
        val doc = OdfDocument.Presentation("Deck", listOf(slide))
        val viewModel = vm(doc)
        val fired = mutableListOf<String>()
        rule.setContent {
            OfficeBottomBar(
                document = doc,
                target = FormatTarget.Element(0, 0),
                caps = DocCaps(insertImage = true, insertShape = true),
                viewModel = viewModel,
                actions = BottomBarActions(
                    onSlideTextColor = { fired.add("slideTextColor") },
                    onSlideFill = { fired.add("slideFill") },
                    onSlideStroke = { fired.add("slideStroke") },
                    onDeleteElement = { fired.add("delete") },
                    onInsertShape = { fired.add("shape:$it") },
                ),
            )
        }
        clickDesc("Text color")
        clickText("Fill")
        clickText("Border")
        assertTrue(fired.containsAll(listOf("slideTextColor", "slideFill", "slideStroke")), fired.toString())
    }
}
