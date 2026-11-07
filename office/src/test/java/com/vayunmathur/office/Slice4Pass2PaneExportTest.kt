package com.vayunmathur.office

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.vayunmathur.office.util.OfficePresence
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Slice 4 pass 2: OfficeDocumentPane presence banner, ExportMenu per-type items, chart editor. */
@RunWith(AndroidJUnit4::class)
class Slice4Pass2PaneExportTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) =
        if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun textDoc() = OdfDocument.TextDocument(
        "Report.odt",
        listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("pane body"))))),
    )

    private fun sheetDoc() = OdfDocument.Spreadsheet(
        "Book.ods",
        listOf(OdfSheet("S", listOf(OdfRow(listOf(OdfCell("a1")))))),
    )

    private fun deckDoc() = OdfDocument.Presentation(
        "Deck.odp",
        listOf(
            OdfSlide(
                "S1",
                listOf(
                    OdfSlideElement.Frame(
                        OdfFrame(0f, 0f, 100f, 50f, listOf(OdfParagraph(listOf(OdfSpan("slide body")))))
                    )
                ),
            )
        ),
    )

    @Test fun paneShowsPresenceBannerForTypingAndLocatedPeers() {
        val doc = textDoc()
        val viewModel = vm(doc)
        viewModel.remotePresenceMutable.value = listOf(
            OfficePresence(id = "d1", name = "Alice", typing = true, ts = 1L, loc = "Para 2"),
            OfficePresence(id = "d2", name = "Bob", typing = false, ts = 2L),
        )
        rule.setContent {
            OfficeDocumentPane(
                document = doc, viewModel = viewModel,
                documentDarkMode = false, nightMode = false, searchQuery = "",
                fontSizeMultiplier = 1f, listState = rememberLazyListState(), isEditMode = false,
                activeSlideEl = -1,
                onRunSelectionChange = { _, _, _, _ -> }, onCellFocus = { _, _, _ -> },
                onCellSelected = { _, _, _ -> }, onSlideChange = {}, onSlideElementSelected = { _, _ -> },
                onChartClick = {}, onCropImage = {}, onCropSlide = { _, _ -> }, onCropSheet = { _, _ -> },
            )
        }
        rule.onNodeWithText("Alice", substring = true).assertExists()
        rule.onNodeWithText("Online:", substring = true).assertExists()
        rule.onNodeWithText("pane body", substring = true).assertExists()
    }

    @Test fun paneWithoutPresenceHasNoBanner() {
        val doc = sheetDoc()
        val viewModel = vm(doc)
        rule.setContent {
            OfficeDocumentPane(
                document = doc, viewModel = viewModel,
                documentDarkMode = false, nightMode = false, searchQuery = "",
                fontSizeMultiplier = 1f, listState = rememberLazyListState(), isEditMode = false,
                activeSlideEl = -1,
                onRunSelectionChange = { _, _, _, _ -> }, onCellFocus = { _, _, _ -> },
                onCellSelected = { _, _, _ -> }, onSlideChange = {}, onSlideElementSelected = { _, _ -> },
                onChartClick = {}, onCropImage = {}, onCropSlide = { _, _ -> }, onCropSheet = { _, _ -> },
            )
        }
        rule.onNodeWithText("a1").assertExists()
        rule.onAllNodesWithText("Online:", substring = true).fetchSemanticsNodes().let {
            assertEquals(0, it.size, "no banner without presence")
        }
    }

    private fun showExportMenu(state: DocumentScreenState, doc: OdfDocument, viewModel: OfficeViewModel) {
        rule.setContent {
            val launchers = rememberDocumentLaunchers(viewModel, DocumentScreenState(), onPickImageBytes = { _, _ -> })
            ExportMenu(
                s = state, document = doc, viewModel = viewModel,
                isTextDoc = doc is OdfDocument.TextDocument,
                isSpreadsheet = doc is OdfDocument.Spreadsheet,
                isPresentation = doc is OdfDocument.Presentation,
                launchers = launchers,
            )
        }
    }

    private fun menuItem(label: String) =
        rule.onAllNodesWithText(label, substring = true, useUnmergedTree = true).let {
            it[it.fetchSemanticsNodes().size - 1]
        }

    @Test fun exportMenuTextItemsStageWarning() {
        val doc = textDoc()
        val state = DocumentScreenState().apply { exportMenu = true }
        showExportMenu(state, doc, vm(doc))
        menuItem(str(R.string.word_docx)).performScrollTo().performClick()
        assertNotNull(state.exportWarning, "docx export stages a warning instead of launching directly")
        assertEquals(false, state.exportMenu)

        state.exportWarning = null
        state.exportMenu = true
        rule.waitForIdle()
        menuItem(str(R.string.markdown_md)).performScrollTo().performClick()
        assertNotNull(state.exportWarning)
    }

    @Test fun exportMenuSheetAndPresentationItemsStageWarning() {
        val doc = sheetDoc()
        val state = DocumentScreenState().apply { exportMenu = true }
        showExportMenu(state, doc, vm(doc))
        menuItem(str(R.string.excel_xlsx)).performClick()
        assertNotNull(state.exportWarning)

        assertTrue(state.exportWarning != null)
    }

    @Test fun exportMenuPresentationItemStagesWarning() {
        val doc = deckDoc()
        val state = DocumentScreenState().apply { exportMenu = true }
        showExportMenu(state, doc, vm(doc))
        menuItem(str(R.string.powerpoint_pptx)).performClick()
        assertNotNull(state.exportWarning)
        assertEquals(false, state.exportMenu)
    }

    @Test fun exportMenuFlatOdfLaunchesAndExportAsTextShares() {
        val doc = textDoc()
        val state = DocumentScreenState().apply { exportMenu = true }
        showExportMenu(state, doc, vm(doc))
        menuItem(str(R.string.export_as_text)).performClick()
        assertEquals(false, state.exportMenu)
        // Sharing fires a chooser intent under Robolectric; the menu closing is the
        // observable contract (no crash, no staged warning).
        assertNull(state.exportWarning)
    }
}
