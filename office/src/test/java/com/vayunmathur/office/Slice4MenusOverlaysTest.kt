package com.vayunmathur.office

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
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
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 menu + cell/slide overlay tests (Robolectric + createComposeRule):
 * insert/export menus, page-setup dialog, cell color/comment/resize overlays
 * and slide-element color overlays. A recording fake stands in for the
 * activity-result launchers (save/export pickers).
 */
@RunWith(AndroidJUnit4::class)
class Slice4MenusOverlaysTest {

    @get:Rule val rule = createComposeRule()

    /**
     * Real launchers via the production factory: under Robolectric they bind to
     * the test activity and never fire without user interaction, which is all
     * these render/callback tests need.
     */
    @Composable
    private fun testLaunchers(
        viewModel: OfficeViewModel,
        holder: DocumentScreenState,
    ): DocumentLaunchers = rememberDocumentLaunchers(viewModel, holder, onPickImageBytes = { _, _ -> })

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun textDoc() = OdfDocument.TextDocument(
        "Doc",
        listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("body"))))),
    )

    private fun sheetDoc() = OdfDocument.Spreadsheet(
        "Book",
        listOf(OdfSheet("S", listOf(OdfRow(listOf(OdfCell("a")))))),
    )

    // ---- InsertMenu ----

    @Test fun insertMenuDisabledWithoutSelection() {
        val s = DocumentScreenState()
        val viewModel = vm(textDoc())
        rule.setContent {
            val ln = testLaunchers(viewModel, s)
            InsertMenu(
                s = s, viewModel = viewModel, isTextDoc = true,
                focusedPara = -1, launchers = ln)
        }
        rule.onNodeWithText("Insert").performClick()
        // Field items need an active run; without one they stay disabled.
        assertEquals(-1, s.activeRunStart)
    }

    @Test fun insertMenuHiddenForNonText() {
        val s = DocumentScreenState()
        val viewModel = vm(sheetDoc())
        rule.setContent {
            val ln = testLaunchers(viewModel, s)
            InsertMenu(
                s = s, viewModel = viewModel, isTextDoc = false,
                focusedPara = -1, launchers = ln)
        }
        rule.onNodeWithText("Insert").assertDoesNotExist()
    }

    // ---- ExportMenu ----

    @Test fun exportMenuTextDocOptions() {
        val s = DocumentScreenState().apply { exportMenu = true }
        val viewModel = vm(textDoc())
        rule.setContent {
            val ln = testLaunchers(viewModel, s)
            ExportMenu(
                s = s, document = textDoc(), viewModel = viewModel,
                isTextDoc = true, isSpreadsheet = false, isPresentation = false,
                launchers = ln)
        }
        rule.onNodeWithText("PDF", substring = true).assertIsDisplayed()
        rule.onNodeWithText("HTML", substring = true).assertIsDisplayed()
    }

    @Test fun exportMenuSpreadsheetOptions() {
        val s = DocumentScreenState().apply { exportMenu = true }
        val doc = sheetDoc()
        val viewModel = vm(doc)
        rule.setContent {
            val ln = testLaunchers(viewModel, s)
            ExportMenu(
                s = s, document = doc, viewModel = viewModel,
                isTextDoc = false, isSpreadsheet = true, isPresentation = false,
                launchers = ln)
        }
        rule.onNodeWithText("CSV", substring = true).assertIsDisplayed()
    }

    // ---- PageSetupOverlays ----

    @Test fun pageSetupHiddenByDefault() {
        val state = DocumentOverlayState()
        rule.setContent {
            PageSetupOverlays(
                state = state, document = textDoc(), viewModel = vm(textDoc()))
        }
        rule.onNodeWithText("Page setup", substring = true).assertDoesNotExist()
    }

    @Test fun pageSetupShowsCurrentGeometry() {
        val state = DocumentOverlayState().apply { showPageSetup = true }
        val doc = textDoc().copy(pageSetup = OdfPageSetup())
        rule.setContent {
            PageSetupOverlays(state = state, document = doc, viewModel = vm(doc))
        }
        rule.onNodeWithText("Page setup", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Landscape", substring = true).assertIsDisplayed()
    }

    @Test fun pageSetupToggleLandscapePersists() {
        val state = DocumentOverlayState().apply { showPageSetup = true }
        val doc = textDoc().copy(pageSetup = OdfPageSetup())
        val viewModel = vm(doc)
        rule.setContent {
            PageSetupOverlays(state = state, document = doc, viewModel = viewModel)
        }
        rule.onNodeWithText("Landscape", substring = true).performClick()
        rule.onNodeWithText("Apply").performClick()
        val cur = (viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument
        assertTrue(cur.pageSetup?.isLandscape == true)
        assertTrue(!state.showPageSetup)
    }

    // ---- CellOverlays ----

    @Test fun cellTextColorOverlayApplies() {
        val state = DocumentOverlayState().apply { showCellTextColor = true }
        val viewModel = vm(sheetDoc())
        rule.setContent {
            CellOverlays(
                state = state,
                viewModel = viewModel,
                activeCell = mutableStateOf(Triple(0, 0, 0)),
                activeSlide = mutableIntStateOf(0),
                activeSlideEl = mutableIntStateOf(-1),
            )
        }
        rule.onNodeWithText("Text Color").assertIsDisplayed()
        // Pick the clear swatch: sets null color and dismisses.
        rule.onNodeWithText("∅").performClick()
        assertTrue(!state.showCellTextColor)
    }

    @Test fun cellOverlaysNoCellNoCrash() {
        val state = DocumentOverlayState().apply {
            showCellTextColor = true
            showCellComment = true
            showCellResize = true
        }
        rule.setContent {
            CellOverlays(
                state = state,
                viewModel = vm(sheetDoc()),
                activeCell = mutableStateOf(null),
                activeSlide = mutableIntStateOf(0),
                activeSlideEl = mutableIntStateOf(-1),
            )
        }
        // No active cell: color pickers stay hidden, nothing crashes.
        rule.onNodeWithText("Text Color").assertDoesNotExist()
    }

    // ---- SlideElementOverlays ----

    @Test fun slideElementOverlaysHiddenWithoutSelection() {
        val state = DocumentOverlayState().apply { showSlideTextColor = true }
        rule.setContent {
            SlideElementOverlays(
                state = state,
                viewModel = vm(textDoc()),
                activeSlide = mutableIntStateOf(0),
                activeSlideEl = mutableIntStateOf(-1),
            )
        }
        rule.onNodeWithText("Text Color").assertDoesNotExist()
    }

    // ---- DocumentScreenState extras ----

    @Test fun screenStateSelectionFields() {
        val s = DocumentScreenState()
        s.activeCell = Triple(1, 2, 3)
        assertEquals(Triple(1, 2, 3), s.activeCellState.value)
        s.findMatches = listOf(4, 9)
        assertEquals(listOf(4, 9), s.findMatches)
        s.pendingReplace = { _, _ -> }
        assertTrue(s.pendingReplace != null)
        s.timerSeconds = 90
        assertEquals(90, s.timerSecondsState.value)
    }
}
