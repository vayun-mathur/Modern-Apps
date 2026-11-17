package com.vayunmathur.office

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
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
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableColumn
import com.vayunmathur.library.ui.odf.OdfTableRow
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.platform.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import com.vayunmathur.office.ui.DocumentScreen
import com.vayunmathur.office.ui.OfficeLightTheme
/**
 * W4: [DocumentScreen] with rich documents — tables inside text docs,
 * spreadsheet grids and presentation decks render their content, and the
 * File/View menus open their real items.
 */
@RunWith(AndroidJUnit4::class)
class W4DocumentScreenRichTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun showDoc(document: OdfDocument) {
        rule.setContent {
            OfficeLightTheme {
                DocumentScreen(
                    document = document,
                    viewModel = vm(document),
                    activity = rule.activity,
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
    }

    private fun richTextDoc() = OdfDocument.TextDocument(
        "Rich.odt",
        listOf(
            OdfContentBlock.Paragraph(
                OdfParagraph(listOf(OdfSpan("Rich Title")), style = ParagraphStyle.HEADING1)),
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("intro body")))),
            OdfContentBlock.Table(OdfTable(
                columns = listOf(OdfTableColumn(80f), OdfTableColumn(80f)),
                rows = listOf(
                    OdfTableRow(listOf(
                        OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("cell alpha"))))),
                        OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("cell beta"))))),
                    )),
                    OdfTableRow(listOf(
                        OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("cell gamma"))))),
                        OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("cell delta"))))),
                    )),
                ),
            )),
            OdfContentBlock.Paragraph(
                OdfParagraph(listOf(OdfSpan("bullet one")), style = ParagraphStyle.LIST_ITEM)),
            OdfContentBlock.Paragraph(
                OdfParagraph(listOf(OdfSpan("bullet two")), style = ParagraphStyle.LIST_ITEM)),
        ),
    )

    @Test fun richTextScreenRendersTableAndLists() {
        showDoc(richTextDoc())
        rule.onNodeWithText("Rich.odt").assertIsDisplayed()
        for (cell in listOf("cell alpha", "cell beta", "cell gamma", "cell delta")) {
            rule.onNodeWithText(cell, substring = true).assertExists()
        }
        rule.onNodeWithText("bullet one", substring = true).assertExists()
        rule.onNodeWithText("bullet two", substring = true).assertExists()
    }

    @Test fun fileMenuOpensRealItems() {
        showDoc(richTextDoc())
        rule.onNodeWithText("File").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Export", substring = true).assertExists()
        rule.onNodeWithText("Settings").assertExists()
        // Export opens the format submenu.
        rule.onNodeWithText("Export", substring = true).performClick()
        rule.waitForIdle()
    }

    @Test fun viewMenuOpensRealItems() {
        showDoc(richTextDoc())
        rule.onNodeWithText("View").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Outline", substring = true)[0].assertExists()
    }

    @Test fun screenRecomposesAcrossDocumentAndStateChanges() {
        var currentDoc = androidx.compose.runtime.mutableStateOf<OdfDocument>(richTextDoc())
        lateinit var viewModel: OfficeViewModel
        rule.setContent {
            val doc = currentDoc.value
            viewModel = vm(doc)
            OfficeLightTheme {
                DocumentScreen(
                    document = doc,
                    viewModel = viewModel,
                    activity = rule.activity,
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Rich.odt").assertIsDisplayed()
        // Swap the whole document: every chrome/body restart scope re-runs
        // with changed parameters.
        rule.runOnIdle {
            currentDoc.value = OdfDocument.TextDocument(
                "Other.odt",
                listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("replacement body"))))))
        }
        rule.waitForIdle()
        rule.onNodeWithText("Other.odt").assertIsDisplayed()
        rule.runOnIdle {
            currentDoc.value = richTextDoc()
        }
        rule.waitForIdle()
        rule.onNodeWithText("Rich.odt").assertIsDisplayed()
    }

    @Test fun sheetScreenRendersGrid() {
        val sheet = OdfSheet(
            name = "Data",
            rows = listOf(
                OdfRow(listOf(OdfCell("Name"), OdfCell("Value"))),
                OdfRow(listOf(OdfCell("apples"), OdfCell("42", numberValue = 42.0))),
                OdfRow(listOf(OdfCell("pears"), OdfCell("7", numberValue = 7.0))),
            ),
            freezeRows = 1,
        )
        showDoc(OdfDocument.Spreadsheet("Book.ods", listOf(sheet)))
        rule.onNodeWithText("Book.ods").assertIsDisplayed()
        rule.onNodeWithText("apples", substring = true).assertExists()
        rule.onNodeWithText("pears", substring = true).assertExists()
    }

    @Test fun presentationScreenRendersSlides() {
        val doc = OdfDocument.Presentation(
            "Deck.odp",
            listOf(
                OdfSlide(
                    "S1",
                    listOf(
                        com.vayunmathur.library.ui.odf.OdfSlideElement.Frame(
                            com.vayunmathur.library.ui.odf.OdfFrame(
                                0f, 0f, 200f, 60f,
                                listOf(OdfParagraph(listOf(OdfSpan("deck headline")))))),
                    ),
                ),
                OdfSlide(
                    "S2",
                    listOf(
                        com.vayunmathur.library.ui.odf.OdfSlideElement.Frame(
                            com.vayunmathur.library.ui.odf.OdfFrame(
                                0f, 0f, 200f, 60f,
                                listOf(OdfParagraph(listOf(OdfSpan("second slide")))))),
                    ),
                ),
            ),
        )
        showDoc(doc)
        rule.onNodeWithText("Deck.odp").assertIsDisplayed()
        // Slide canvas + thumbnail strip both render the headline.
        kotlin.test.assertTrue(
            rule.onAllNodesWithText("deck headline", substring = true)
                .fetchSemanticsNodes().isNotEmpty())
    }
}
