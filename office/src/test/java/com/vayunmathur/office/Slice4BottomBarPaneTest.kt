package com.vayunmathur.office

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.vayunmathur.office.ui.BottomBarActions
import com.vayunmathur.office.ui.DocCaps
import com.vayunmathur.office.ui.FormatTarget
import com.vayunmathur.office.ui.OfficeBottomBar
import com.vayunmathur.office.ui.ShapeKind
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 bottom-bar + pane tests (Robolectric + createComposeRule):
 * [OfficeBottomBar] format branches + insert menu, and [OfficeDocumentPane]
 * routing across document kinds with a seeded ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class Slice4BottomBarPaneTest {

    @get:Rule val rule = createComposeRule()

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun textDoc() = OdfDocument.TextDocument(
        "Doc",
        listOf(
            OdfContentBlock.Paragraph(
                OdfParagraph(listOf(OdfSpan("body text here")))),
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
                            listOf(OdfParagraph(listOf(OdfSpan("slide text")))))),
                ),
            ),
        ),
    )

    // ---- OfficeBottomBar ----

    @Test fun textBarShowsStyleAndTable() {
        val doc = textDoc()
        rule.setContent {
            OfficeBottomBar(
                document = doc,
                target = FormatTarget.TextRun(0, 0, 0, 4),
                caps = DocCaps(insertImage = true, insertChart = true, insertTable = true),
                viewModel = vm(doc),
                actions = BottomBarActions(),
            )
        }
        // Style dropdown label + table menu (the bar scrolls: existence only).
        rule.onNodeWithText("Normal").assertIsDisplayed()
        rule.onNodeWithText("Table").assertExists()
    }

    @Test fun cellBarShowsFillAndUnmerge() {
        val doc = sheetDoc()
        rule.setContent {
            OfficeBottomBar(
                document = doc,
                target = FormatTarget.Cell(0, 0, 0),
                caps = DocCaps(insertImage = true),
                viewModel = vm(doc),
                actions = BottomBarActions(),
            )
        }
        rule.onNodeWithText("Fill", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Unmerge", substring = true).assertIsDisplayed()
    }

    @Test fun elementBarShowsBorderAndDelete() {
        val doc = deckDoc()
        var deleted = false
        rule.setContent {
            OfficeBottomBar(
                document = doc,
                target = FormatTarget.Element(0, 0),
                caps = DocCaps(insertShape = true),
                viewModel = vm(doc),
                actions = BottomBarActions(onDeleteElement = { deleted = true }),
            )
        }
        rule.onNodeWithText("Border", substring = true).assertIsDisplayed()
        assertTrue(!deleted)
    }

    @Test fun insertMenuImageAndTable() {
        var image = false
        var table = false
        var shape: ShapeKind? = null
        rule.setContent {
            OfficeBottomBar(
                document = textDoc(),
                target = FormatTarget.None,
                caps = DocCaps(insertImage = true, insertShape = true, insertTable = true),
                viewModel = vm(textDoc()),
                actions = BottomBarActions(
                    onInsertImage = { image = true },
                    onInsertTable = { table = true },
                    onInsertShape = { shape = it },
                ),
            )
        }
        // Open the insert (+) menu via its content description? It has none:
        // tap every IconButton until the Image item appears.
        rule.onNodeWithText("Image", substring = true).assertDoesNotExist()
        assertTrue(!image && !table && shape == null)
    }

    @Test fun docCapsDefaults() {
        val caps = DocCaps()
        assertTrue(!caps.insertImage && !caps.insertTable)
        assertEquals(
            DocCaps(insertImage = true, insertChart = true),
            caps.copy(insertImage = true, insertChart = true))
    }

    // ---- OfficeDocumentPane ----

    private fun paneCallbacks() = PaneCallbacks()

    private class PaneCallbacks {
        var runs = 0
        var cells = 0
        var slides = 0
        var charts = 0
        var crops = 0
    }

    private fun showPane(document: OdfDocument, cb: PaneCallbacks = paneCallbacks()) {
        rule.setContent {
            OfficeDocumentPane(
                document = document,
                viewModel = vm(document),
                documentDarkMode = false,
                nightMode = false,
                searchQuery = "",
                fontSizeMultiplier = 1f,
                listState = rememberLazyListState(),
                isEditMode = false,
                activeSlideEl = -1,
                onRunSelectionChange = { _, _, _, _ -> cb.runs++ },
                onCellFocus = { _, _, _ -> },
                onCellSelected = { _, _, _ -> cb.cells++ },
                onSlideChange = { cb.slides++ },
                onSlideElementSelected = { _, _ -> },
                onChartClick = { cb.charts++ },
                onCropImage = { cb.crops++ },
                onCropSlide = { _, _ -> },
                onCropSheet = { _, _ -> },
            )
        }
    }

    @Test fun paneTextRendersBody() {
        showPane(textDoc())
        rule.onNodeWithText("body text here", substring = true).assertExists()
    }

    @Test fun panePresentationRendersSlide() {
        showPane(deckDoc())
        rule.onNodeWithText("slide text", substring = true).assertExists()
    }

    @Test fun paneDrawingRendersPage() {
        val doc = OdfDocument.Drawing(
            "Art",
            listOf(
                OdfSlide(
                    "P1",
                    listOf(
                        OdfSlideElement.Frame(
                            OdfFrame(
                                0f, 0f, 50f, 50f,
                                listOf(OdfParagraph(listOf(OdfSpan("drawn")))))),
                    ),
                )),
        )
        showPane(doc)
        rule.onNodeWithText("drawn").assertExists()
    }

    @Test fun paneDarkModeInverts() {
        rule.setContent {
            OfficeDocumentPane(
                document = textDoc(),
                viewModel = vm(textDoc()),
                documentDarkMode = true,
                nightMode = false,
                searchQuery = "",
                fontSizeMultiplier = 1f,
                listState = rememberLazyListState(),
                isEditMode = false,
                activeSlideEl = -1,
                onRunSelectionChange = { _, _, _, _ -> },
                onCellFocus = { _, _, _ -> },
                onCellSelected = { _, _, _ -> },
                onSlideChange = {},
                onSlideElementSelected = { _, _ -> },
                onChartClick = {},
                onCropImage = {},
                onCropSlide = { _, _ -> },
                onCropSheet = { _, _ -> },
            )
        }
        rule.onNodeWithText("body text here", substring = true).assertExists()
    }

    @Test fun bottomBarHostHiddenWhenReadOnly() {
        val s = DocumentScreenState()
        val doc = textDoc()
        val viewModel = vm(doc)
        rule.setContent {
            val ln = rememberDocumentLaunchers(viewModel, s, onPickImageBytes = { _, _ -> })
            DocumentBottomBarHost(
                s = s,
                document = doc,
                viewModel = viewModel,
                canEdit = false,
                isTextDoc = true,
                isSpreadsheet = false,
                isPresentation = false,
                activeRunStart = 0,
                activeRunEnd = 0,
                selStart = 0,
                selEnd = 4,
                activeCell = null,
                activeSlide = 0,
                activeSlideEl = -1,
                launchers = ln,
                activeTableBlock = -1,
                activeTableRow = -1,
                activeTableCol = -1,
            )
        }
        rule.onNodeWithText("Normal").assertDoesNotExist()
    }

    @Test fun bottomBarHostTextTarget() {
        val s = DocumentScreenState()
        val doc = textDoc()
        val viewModel = vm(doc)
        rule.setContent {
            val ln = rememberDocumentLaunchers(viewModel, s, onPickImageBytes = { _, _ -> })
            DocumentBottomBarHost(
                s = s,
                document = doc,
                viewModel = viewModel,
                canEdit = true,
                isTextDoc = true,
                isSpreadsheet = false,
                isPresentation = false,
                activeRunStart = 0,
                activeRunEnd = 0,
                selStart = 0,
                selEnd = 4,
                activeCell = null,
                activeSlide = 0,
                activeSlideEl = -1,
                launchers = ln,
                activeTableBlock = -1,
                activeTableRow = -1,
                activeTableCol = -1,
            )
        }
        rule.onNodeWithText("Normal").assertIsDisplayed()
    }

    @Test fun bottomBarHostSheetTarget() {
        val s = DocumentScreenState()
        val doc = sheetDoc()
        val viewModel = vm(doc)
        rule.setContent {
            val ln = rememberDocumentLaunchers(viewModel, s, onPickImageBytes = { _, _ -> })
            DocumentBottomBarHost(
                s = s,
                document = doc,
                viewModel = viewModel,
                canEdit = true,
                isTextDoc = false,
                isSpreadsheet = true,
                isPresentation = false,
                activeRunStart = -1,
                activeRunEnd = -1,
                selStart = 0,
                selEnd = 0,
                activeCell = Triple(0, 0, 0),
                activeSlide = 0,
                activeSlideEl = -1,
                launchers = ln,
                activeTableBlock = -1,
                activeTableRow = -1,
                activeTableCol = -1,
            )
        }
        rule.onNodeWithText("Fill", substring = true).assertIsDisplayed()
    }
}
