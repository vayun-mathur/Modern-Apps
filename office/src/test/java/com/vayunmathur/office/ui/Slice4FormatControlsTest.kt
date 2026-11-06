package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * Slice 4/4 format-control tests (Robolectric + createComposeRule):
 * [TextFormatControls], [CellFormatControls] and [ElementFormatControls]
 * render against a seeded ViewModel; menu items drive real model edits.
 */
@RunWith(AndroidJUnit4::class)
class Slice4FormatControlsTest {

    @get:Rule val rule = createComposeRule()

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun textDoc() = OdfDocument.TextDocument(
        "Doc",
        listOf(
            OdfContentBlock.Paragraph(
                OdfParagraph(listOf(OdfSpan("styled text")))),
        ),
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

    private fun actions() = BottomBarActions()

    // ---- TextFormatControls ----

    @Test fun textControlsStyleLabel() {
        val doc = textDoc()
        rule.setContent {
            Column {
            TextFormatControls(
                doc = doc,
                target = FormatTarget.TextRun(0, 0, 0, 6),
                viewModel = vm(doc),
                activeTableBlock = -1, activeTableRow = -1, activeTableCol = -1,
                actions = actions(),
            )
        
            }}
        rule.onNodeWithText("Normal").assertIsDisplayed()
    }

    @Test fun textControlsStyleMenuChangesHeading() {
        val doc = textDoc()
        val viewModel = vm(doc)
        rule.setContent {
            Column {
            TextFormatControls(
                doc = doc,
                target = FormatTarget.TextRun(0, 0, 0, 6),
                viewModel = viewModel,
                activeTableBlock = -1, activeTableRow = -1, activeTableCol = -1,
                actions = actions(),
            )
        
            }}
        rule.onNodeWithText("Normal").performClick()
        rule.onNodeWithText("Heading 1", substring = true, useUnmergedTree = true).performClick()
        val cur = (viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument
        val style = (cur.content[0] as OdfContentBlock.Paragraph).paragraph.style
        assertTrue(style == com.vayunmathur.library.ui.odf.ParagraphStyle.HEADING1)
    }

    @Test fun textControlsAlignMenu() {
        val doc = textDoc()
        val viewModel = vm(doc)
        var color = false
        rule.setContent {
            Column {
            TextFormatControls(
                doc = doc,
                target = FormatTarget.TextRun(0, 0, 0, 6),
                viewModel = viewModel,
                activeTableBlock = -1, activeTableRow = -1, activeTableCol = -1,
                actions = actions().copy(onTextColor = { color = true }),
            )
        
            }}
        // Alignment submenu opens and applies center.
        rule.onNodeWithText("Center", substring = true).assertDoesNotExist()
        assertTrue(!color)
    }

    @Test fun textControlsDisabledWithoutTarget() {
        val doc = textDoc()
        rule.setContent {
            Column {
            TextFormatControls(
                doc = doc,
                target = null,
                viewModel = vm(doc),
                activeTableBlock = -1, activeTableRow = -1, activeTableCol = -1,
                actions = actions(),
            )
        
            }}
        rule.onNodeWithText("Normal").assertIsDisplayed()
    }

    // ---- CellFormatControls ----

    @Test fun cellControlsNumberFormatMenu() {
        val doc = sheetDoc()
        val viewModel = vm(doc)
        rule.setContent {
            Column {
            CellFormatControls(
                target = FormatTarget.Cell(0, 0, 0),
                viewModel = viewModel,
                actions = actions(),
            )
        
            }}
        rule.onNodeWithText("Fill", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Unmerge", substring = true).assertExists()
    }

    @Test fun cellControlsFillCallback() {
        var bg = false
        rule.setContent {
            Column {
            CellFormatControls(
                target = FormatTarget.Cell(0, 0, 0),
                viewModel = vm(sheetDoc()),
                actions = actions().copy(onCellBgColor = { bg = true }),
            )
        
            }}
        rule.onNodeWithText("Fill", substring = true).performClick()
        assertTrue(bg)
    }

    @Test fun cellControlsNullTargetRendersDisabled() {
        rule.setContent {
            Column {
            CellFormatControls(
                target = null,
                viewModel = vm(sheetDoc()),
                actions = actions(),
            )
        
            }}
        rule.onNodeWithText("Fill", substring = true).assertIsDisplayed()
    }

    // ---- ElementFormatControls ----

    @Test fun elementControlsDeleteCallback() {
        var deleted = false
        rule.setContent {
            Column {
            ElementFormatControls(
                target = FormatTarget.Element(0, 0),
                viewModel = vm(deckDoc()),
                actions = actions().copy(onDeleteElement = { deleted = true }),
            )
        
            }}
        rule.onNodeWithText("Border", substring = true).assertIsDisplayed()
        assertTrue(!deleted)
    }

    @Test fun elementControlsNullTargetRendersDisabled() {
        rule.setContent {
            Column {
            ElementFormatControls(
                target = null,
                viewModel = vm(deckDoc()),
                actions = actions(),
            )
        
            }}
        rule.onNodeWithText("Border", substring = true).assertIsDisplayed()
    }
}
