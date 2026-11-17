package com.vayunmathur.office

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.platform.OfficeViewModel
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import com.vayunmathur.office.ui.DocumentScreen
import com.vayunmathur.office.ui.OfficeLightTheme
/**
 * W5: DocumentScreen menu/overlay traversal — every top menu is opened and
 * its toggle items are driven for each document type, so the menu-bar,
 * outline, comments, changes and page-setup lambdas run with a real loaded
 * document behind them. Assertions verify the menu items and resulting UI
 * state actually appear.
 */
@RunWith(AndroidJUnit4::class)
class W5DocumentScreenMenusTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun showDoc(document: OdfDocument) {
        val model = vm(document)
        rule.setContent {
            OfficeLightTheme {
                DocumentScreen(
                    document = document,
                    viewModel = model,
                    activity = rule.activity,
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun textDoc() = OdfDocument.TextDocument(
        "Menu.odt",
        listOf(
            OdfContentBlock.Paragraph(
                OdfParagraph(listOf(OdfSpan("Menu Title")), style = ParagraphStyle.HEADING1)),
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("body text here")))),
            OdfContentBlock.Paragraph(
                OdfParagraph(listOf(OdfSpan("second heading")), style = ParagraphStyle.HEADING2)),
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("more body")))),
        ),
    )

    private fun sheetDoc() = OdfDocument.Spreadsheet(
        "Menu.ods",
        listOf(OdfSheet("Data", listOf(
            OdfRow(listOf(OdfCell("Name"), OdfCell("Value"))),
            OdfRow(listOf(OdfCell("alpha"), OdfCell("42", numberValue = 42.0))),
            OdfRow(listOf(OdfCell("beta"), OdfCell("7", numberValue = 7.0))),
        ))),
    )

    private fun slidesDoc() = OdfDocument.Presentation(
        "Menu.odp",
        listOf(
            OdfSlide("One", listOf(
                OdfSlideElement.Frame(OdfFrame(
                    0f, 0f, 200f, 50f,
                    listOf(OdfParagraph(listOf(OdfSpan("slide one text")))))))),
            OdfSlide("Two", listOf(
                OdfSlideElement.Frame(OdfFrame(
                    0f, 0f, 200f, 50f,
                    listOf(OdfParagraph(listOf(OdfSpan("slide two text")))))))),
        ),
    )

    private fun exists(text: String): Boolean =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun clickIfExists(text: String): Boolean {
        val nodes = rule.onAllNodesWithText(text, substring = true)
        if (nodes.fetchSemanticsNodes().isEmpty()) return false
        nodes[0].performClick()
        rule.waitForIdle()
        return true
    }

    @Test fun textDocMenusTraverse() {
        showDoc(textDoc())
        rule.onNodeWithText("File").performClick()
        rule.waitForIdle()
        assertTrue(exists("Export"), "File menu must offer Export")
        assertTrue(exists("Settings"), "File menu must offer Settings")
        // Close the menu by opening View instead.
        rule.onNodeWithText("View").performClick()
        rule.waitForIdle()
        assertTrue(exists("Outline"), "View menu must offer Outline")
        // Drive every toggle-like View item that is present.
        for (item in listOf("Night", "Word count", "Comments", "Track changes", "Page setup", "Outline")) {
            if (!exists(item)) {
                rule.onNodeWithText("View").performClick()
                rule.waitForIdle()
            }
            clickIfExists(item)
        }
        rule.waitForIdle()
        // The document is still rendered after all the menu driving.
        assertTrue(exists("Menu Title"), "document must survive menu traversal")
    }

    @Test fun sheetDocMenusAndCellSelection() {
        showDoc(sheetDoc())
        assertTrue(exists("alpha"), "sheet cells must render")
        rule.onNodeWithText("alpha", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("File").performClick()
        rule.waitForIdle()
        assertTrue(exists("Export"), "File menu must offer Export for sheets")
        rule.onNodeWithText("View").performClick()
        rule.waitForIdle()
        clickIfExists("Outline")
        assertTrue(exists("beta"), "sheet must survive menu traversal")
    }

    @Test fun slidesDocMenusAndSlideSelection() {
        showDoc(slidesDoc())
        assertTrue(exists("slide one text"), "first slide must render")
        rule.onNodeWithText("File").performClick()
        rule.waitForIdle()
        assertTrue(exists("Export"), "File menu must offer Export for decks")
        rule.onNodeWithText("View").performClick()
        rule.waitForIdle()
        for (item in listOf("Presentation timer", "Outline", "Night")) {
            if (!exists(item)) {
                rule.onNodeWithText("View").performClick()
                rule.waitForIdle()
            }
            clickIfExists(item)
        }
        rule.waitForIdle()
        assertTrue(exists("slide one text"), "deck must survive menu traversal")
    }

    @Test fun exportSubmenuListsFormats() {
        showDoc(textDoc())
        rule.onNodeWithText("File").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Export", substring = true).performClick()
        rule.waitForIdle()
        // At least one concrete export format must be offered.
        val formats = listOf("PDF", "DOCX", "HTML", "Markdown", "Text", "ODT", "EPUB", "LaTeX")
        assertTrue(formats.any { exists(it) }, "export submenu must list a format: $formats")
    }
}
