package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 formatter wiring tests: [TextRunFormatter], [CellFormatter] and
 * [SlideFormatter] callbacks drive real ViewModel edits through the
 * format-control composables (icon buttons are located by content description).
 */
@RunWith(AndroidJUnit4::class)
class Slice4FormatterWiringTest {

    @get:Rule val rule = createComposeRule()

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun textDoc() = OdfDocument.TextDocument(
        "Doc",
        listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("styled text"))))),
    )

    private fun sheetDoc() = OdfDocument.Spreadsheet(
        "Book",
        listOf(OdfSheet("S", listOf(OdfRow(listOf(OdfCell("a1")))))),
    )

    private fun deckDoc() = OdfDocument.Presentation(
        "Deck",
        listOf(
            OdfSlide(
                "S1",
                listOf(
                    OdfSlideElement.Frame(
                        OdfFrame(
                            0f, 0f, 100f, 50f,
                            listOf(OdfParagraph(listOf(OdfSpan("el")))))),
                ),
            ),
        ),
    )

    private fun cell(doc: OdfDocument.Spreadsheet): OdfCell =
        doc.sheets[0].rows[0].cells[0]

    private fun loadedSheet(viewModel: OfficeViewModel): OdfDocument.Spreadsheet =
        (viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet

    // ---- TextRunFormatter via TextFormatControls ----

    @Test fun textBoldToggle() {
        val doc = textDoc()
        val viewModel = vm(doc)
        rule.setContent {
            Column {
                TextFormatControls(
                    doc = doc,
                    target = FormatTarget.TextRun(0, 0, 0, 6),
                    viewModel = viewModel,
                    activeTableBlock = -1, activeTableRow = -1, activeTableCol = -1,
                    actions = BottomBarActions(),
                )
            }
        }
        rule.onNodeWithContentDescription("Bold").performClick()
        val spans = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument)
            .content.filterIsInstance<OdfContentBlock.Paragraph>()
            .flatMap { it.paragraph.spans }
        assertTrue(spans.any { it.bold })
    }

    @Test fun textItalicAndStrikeToggle() {
        val doc = textDoc()
        val viewModel = vm(doc)
        rule.setContent {
            Column {
                TextFormatControls(
                    doc = doc,
                    target = FormatTarget.TextRun(0, 0, 0, 6),
                    viewModel = viewModel,
                    activeTableBlock = -1, activeTableRow = -1, activeTableCol = -1,
                    actions = BottomBarActions(),
                )
            }
        }
        rule.onNodeWithContentDescription("Italic").performClick()
        rule.onNodeWithContentDescription("Strikethrough").performClick()
        val spans = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument)
            .content.filterIsInstance<OdfContentBlock.Paragraph>()
            .flatMap { it.paragraph.spans }
        assertTrue(spans.any { it.italic })
        assertTrue(spans.any { it.strikethrough })
    }

    @Test fun textAlignCenterViaMenu() {
        val doc = textDoc()
        val viewModel = vm(doc)
        rule.setContent {
            Column {
                TextFormatControls(
                    doc = doc,
                    target = FormatTarget.TextRun(0, 0, 0, 6),
                    viewModel = viewModel,
                    activeTableBlock = -1, activeTableRow = -1, activeTableCol = -1,
                    actions = BottomBarActions(),
                )
            }
        }
        rule.onNodeWithContentDescription("Align left").performClick()
        rule.onNodeWithText("Center", substring = true, useUnmergedTree = true).performClick()
        val paras = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument)
            .content.filterIsInstance<OdfContentBlock.Paragraph>()
        assertTrue(paras.any { it.paragraph.alignment == androidx.compose.ui.text.style.TextAlign.Center })
    }

    @Test fun textBulletToggle() {
        val doc = textDoc()
        val viewModel = vm(doc)
        rule.setContent {
            Column {
                TextFormatControls(
                    doc = doc,
                    target = FormatTarget.TextRun(0, 0, 0, 6),
                    viewModel = viewModel,
                    activeTableBlock = -1, activeTableRow = -1, activeTableCol = -1,
                    actions = BottomBarActions(),
                )
            }
        }
        rule.onNodeWithContentDescription("Bulleted list", substring = true).performClick()
        val paras = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument)
            .content.filterIsInstance<OdfContentBlock.Paragraph>()
        assertTrue(paras.any { it.paragraph.style == ParagraphStyle.LIST_ITEM })
    }

    // ---- CellFormatter via CellFormatControls ----

    @Test fun cellBoldItalicToggle() {
        val doc = sheetDoc()
        val viewModel = vm(doc)
        rule.setContent {
            Column {
                CellFormatControls(
                    target = FormatTarget.Cell(0, 0, 0),
                    viewModel = viewModel,
                    actions = BottomBarActions(),
                )
            }
        }
        rule.onNodeWithContentDescription("Bold").performClick()
        assertTrue(loadedSheet(viewModel).let { cell(it).bold })
        rule.onNodeWithContentDescription("Italic").performClick()
        assertTrue(loadedSheet(viewModel).let { cell(it).italic })
    }

    @Test fun cellAlignRightViaMenu() {
        val doc = sheetDoc()
        val viewModel = vm(doc)
        rule.setContent {
            Column {
                CellFormatControls(
                    target = FormatTarget.Cell(0, 0, 0),
                    viewModel = viewModel,
                    actions = BottomBarActions(),
                )
            }
        }
        rule.onNodeWithContentDescription("Align left").performClick()
        rule.onNodeWithText("Right", substring = true, useUnmergedTree = true).performClick()
        assertEquals(
            androidx.compose.ui.text.style.TextAlign.End,
            loadedSheet(viewModel).let { cell(it).alignment })
    }

    @Test fun cellUnmergeAndFillDown() {
        val doc = sheetDoc()
        val viewModel = vm(doc)
        rule.setContent {
            Column {
                CellFormatControls(
                    target = FormatTarget.Cell(0, 0, 0),
                    viewModel = viewModel,
                    actions = BottomBarActions(),
                )
            }
        }
        // Unmerge on a plain cell is a no-op but exercises the handler.
        rule.onNodeWithText("Unmerge", substring = true).performClick()
        assertEquals("a1", loadedSheet(viewModel).let { cell(it).text })
        // More-menu -> Fill down copies the value to the rows below (single row: no-op).
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Fill down", substring = true, useUnmergedTree = true).performClick()
        assertEquals("a1", loadedSheet(viewModel).let { cell(it).text })
    }

    @Test fun cellNumberFormatPercent() {
        val doc = sheetDoc()
        val viewModel = vm(doc)
        rule.setContent {
            Column {
                CellFormatControls(
                    target = FormatTarget.Cell(0, 0, 0),
                    viewModel = viewModel,
                    actions = BottomBarActions(),
                )
            }
        }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Number format", substring = true, useUnmergedTree = true).performClick()
        rule.onNodeWithText("Percent", substring = true, useUnmergedTree = true).performClick()
        val c = loadedSheet(viewModel).let { cell(it) }
        assertTrue(c.numberFormat?.percent == true)
        assertEquals("percentage", c.valueType)
    }

    // ---- SlideFormatter via ElementFormatControls ----

    @Test fun slideBoldUnderlineToggle() {
        val doc = deckDoc()
        val viewModel = vm(doc)
        rule.setContent {
            Column {
                ElementFormatControls(
                    target = FormatTarget.Element(0, 0),
                    viewModel = viewModel,
                    actions = BottomBarActions(),
                )
            }
        }
        rule.onNodeWithContentDescription("Bold").performClick()
        rule.onNodeWithContentDescription("Underline").performClick()
        val els = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Presentation).slides[0].elements
        val spans = (els[0] as OdfSlideElement.Frame).frame.paragraphs.flatMap { it.spans }
        assertTrue(spans.any { it.bold })
        assertTrue(spans.any { it.underline })
    }

    @Test fun slideFillCallback() {
        var filled = false
        rule.setContent {
            Column {
                ElementFormatControls(
                    target = FormatTarget.Element(0, 0),
                    viewModel = vm(deckDoc()),
                    actions = BottomBarActions().copy(onSlideFill = { filled = true }),
                )
            }
        }
        rule.onNodeWithText("Fill", substring = true).performClick()
        assertTrue(filled)
    }
}
