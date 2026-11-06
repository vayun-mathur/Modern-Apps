package com.vayunmathur.office

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 [DocumentOverlays] wiring tests (Robolectric + createComposeRule):
 * each dialog flag renders its dialog, and dialog callbacks drive real
 * ViewModel edits. The dialogs themselves are covered in Slice4DialogsTest.
 */
@RunWith(AndroidJUnit4::class)
class Slice4DocOverlaysTest {

    @get:Rule val rule = createComposeRule()

    private fun textDoc() = OdfDocument.TextDocument(
        "Doc",
        listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("body"))))),
    )

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun overlays(
        state: DocumentOverlayState,
        document: OdfDocument,
        viewModel: OfficeViewModel,
        activeCell: Triple<Int, Int, Int>? = null,
        onBack: () -> Unit = {},
    ) {
        rule.setContent {
            DocumentOverlays(
                state = state,
                document = document,
                viewModel = viewModel,
                isPresentation = false,
                isOnline = false,
                focusedPara = 0,
                saveAsName = "Doc.odt",
                activeCell = remember { mutableStateOf(activeCell) },
                activeSlide = remember { mutableStateOf(0) },
                activeSlideEl = remember { mutableStateOf(-1) },
                activeRunStart = remember { mutableStateOf(-1) },
                activeRunEnd = remember { mutableStateOf(-1) },
                selStart = remember { mutableStateOf(0) },
                selEnd = remember { mutableStateOf(0) },
                pendingReplace = remember { mutableStateOf(null) },
                listState = rememberLazyListState(),
                scope = rememberCoroutineScope(),
                launchers = rememberDocumentLaunchers(
                    viewModel,
                    DocumentScreenState(),
                    onPickImageBytes = { _, _ -> }),
                onBecameOnline = {},
                onBack = onBack,
            )
        }
    }

    private fun loadedText(viewModel: OfficeViewModel): OdfDocument.TextDocument =
        (viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument

    @Test fun allHiddenRendersNothing() {
        overlays(DocumentOverlayState(), textDoc(), vm(textDoc()))
        rule.onNodeWithText("Info").assertDoesNotExist()
        rule.onNodeWithText("Tracked", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Comments", substring = true).assertDoesNotExist()
    }

    @Test fun metadataSaveEditsTitle() {
        val state = DocumentOverlayState().apply { showMetadata = true }
        val viewModel = vm(textDoc())
        overlays(state, textDoc(), viewModel)
        rule.onNodeWithText("Info").assertIsDisplayed()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("New Title")
        rule.onNodeWithText("Save").performClick()
        assertEquals("New Title", loadedText(viewModel).metadata.title)
        assertTrue(!state.showMetadata)
    }

    @Test fun metadataCancelDismisses() {
        val state = DocumentOverlayState().apply { showMetadata = true }
        val viewModel = vm(textDoc())
        overlays(state, textDoc(), viewModel)
        rule.onNodeWithText("Cancel", substring = true).performClick()
        assertTrue(!state.showMetadata)
        assertEquals(null, loadedText(viewModel).metadata.title)
    }

    @Test fun unsavedDiscardGoesBack() {
        val state = DocumentOverlayState().apply { showUnsavedDialog = true }
        var back = 0
        overlays(state, textDoc(), vm(textDoc())) { back++ }
        rule.onNodeWithText("Unsaved", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Discard", substring = true).performClick()
        assertEquals(1, back)
        assertTrue(!state.showUnsavedDialog)
    }

    @Test fun unsavedCancelStays() {
        val state = DocumentOverlayState().apply { showUnsavedDialog = true }
        var back = 0
        overlays(state, textDoc(), vm(textDoc())) { back++ }
        rule.onNodeWithText("Cancel", substring = true).performClick()
        assertEquals(0, back)
        assertTrue(!state.showUnsavedDialog)
    }

    @Test fun exportWarningConfirmRunsAction() {
        var warned = false
        val state = DocumentOverlayState().apply { exportWarning = { warned = true } }
        overlays(state, textDoc(), vm(textDoc()))
        rule.onNodeWithText("Export to non-ODF format").assertIsDisplayed()
        rule.onNodeWithText("Export").performClick()
        assertTrue(warned)
        assertEquals(null, state.exportWarning)
    }

    @Test fun exportWarningCancelClears() {
        val state = DocumentOverlayState().apply { exportWarning = {} }
        overlays(state, textDoc(), vm(textDoc()))
        rule.onNodeWithText("Cancel", substring = true).performClick()
        assertEquals(null, state.exportWarning)
    }

    @Test fun enableOnlineDialogDismisses() {
        // NOTE: the enable button is deliberately NOT tapped: enabling mints
        // keys + a device id into the DataStore, which flips later test classes
        // (e.g. OnlineTab's disabled screen) to the enabled path and loads the
        // PQC native library into this classloader, breaking E2E tests.
        val state = DocumentOverlayState().apply { showEnableOnlineDialog = true }
        overlays(state, textDoc(), vm(textDoc()))
        rule.onNodeWithText("Enable online sharing?").assertIsDisplayed()
        rule.onNodeWithText("Cancel", substring = true).performClick()
        assertTrue(!state.showEnableOnlineDialog)
        assertTrue(!state.showShareDialog)
    }

    @Test fun shareDialogClosesViaWiring() {
        val state = DocumentOverlayState().apply { showShareDialog = true }
        val viewModel = vm(textDoc())
        overlays(state, textDoc(), viewModel)
        // Offline owner names the document first.
        rule.onNodeWithText("Document name", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        assertTrue(!state.showShareDialog)
    }

    @Test fun settingsOverlaySaves() {
        val state = DocumentOverlayState().apply { showSettings = true }
        overlays(state, textDoc(), vm(textDoc()))
        rule.onNodeWithText("Settings", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Save", substring = true).performClick()
        assertTrue(!state.showSettings)
    }

    @Test fun colorPickerClearDismissesViaWiring() {
        val state = DocumentOverlayState().apply { showColorPicker = true }
        overlays(state, textDoc(), vm(textDoc()))
        rule.onNodeWithText("∅").performClick()
        assertTrue(!state.showColorPicker)
    }

    @Test fun fontSizePickerSelectsViaWiring() {
        val state = DocumentOverlayState().apply { showFontSizePicker = true }
        overlays(state, textDoc(), vm(textDoc()))
        rule.onNodeWithText("24").performClick()
        assertTrue(!state.showFontSizePicker)
    }

    @Test fun insertTableAddsTable() {
        val state = DocumentOverlayState().apply { showInsertTable = true }
        val viewModel = vm(textDoc())
        overlays(state, textDoc(), viewModel)
        rule.onNodeWithText("Insert").performClick()
        val content = loadedText(viewModel).content
        assertTrue(content.any { it is OdfContentBlock.Table })
        assertTrue(!state.showInsertTable)
    }

    @Test fun insertLinkAddsParagraph() {
        val state = DocumentOverlayState().apply { showInsertLink = true }
        val viewModel = vm(textDoc())
        overlays(state, textDoc(), viewModel)
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Example")
        rule.onNodeWithText("Insert").performClick()
        val content = loadedText(viewModel).content
        assertTrue(content.size == 2)
        assertTrue(!state.showInsertLink)
    }

    @Test fun bookmarkAddsBookmark() {
        val state = DocumentOverlayState().apply { showAddBookmark = true }
        val viewModel = vm(textDoc())
        overlays(state, textDoc(), viewModel)
        rule.onNode(hasSetTextAction()).performTextInput("ch1")
        rule.onNodeWithText("Add").performClick()
        assertEquals(listOf("ch1"), loadedText(viewModel).bookmarks.map { it.name })
        assertTrue(!state.showAddBookmark)
    }

    @Test fun specialCharWithNoRunDismisses() {
        val state = DocumentOverlayState().apply { showSpecialChars = true }
        overlays(state, textDoc(), vm(textDoc()))
        // No active run: picking inserts nothing but still dismisses.
        rule.onNodeWithText("→").performClick()
        assertEquals(1, loadedText(vm(textDoc())).content.size)
        assertTrue(!state.showSpecialChars)
    }

    @Test fun footnoteWithNoRunDismisses() {
        val state = DocumentOverlayState().apply { showFootnote = true }
        overlays(state, textDoc(), vm(textDoc()))
        rule.onNode(hasSetTextAction()).performTextInput("note")
        rule.onNodeWithText("Insert").performClick()
        assertTrue(!state.showFootnote)
    }

    @Test fun commentAddsAnnotation() {
        val state = DocumentOverlayState().apply { showComment = true }
        val viewModel = vm(textDoc())
        overlays(state, textDoc(), viewModel)
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("fix this")
        rule.onNodeWithText("Insert").performClick()
        val spans = (loadedText(viewModel).content[0] as OdfContentBlock.Paragraph)
            .paragraph.spans
        assertTrue(spans.any { it.annotation?.paragraphs?.first()?.spans?.first()?.text == "fix this" })
        assertTrue(!state.showComment)
    }

    @Test fun commentsHostEmptyPlaceholder() {
        val state = DocumentOverlayState().apply { showComments = true }
        overlays(state, textDoc(), vm(textDoc()))
        rule.onNodeWithText("No comments", substring = true).assertIsDisplayed()
    }

    @Test fun changesHostEmptyPlaceholder() {
        val state = DocumentOverlayState().apply { showChanges = true }
        overlays(state, textDoc(), vm(textDoc()))
        rule.onNodeWithText("No tracked", substring = true).assertIsDisplayed()
    }

    @Test fun cellTextColorClearsViaWiring() {
        val doc = OdfDocument.Spreadsheet(
            "Book",
            listOf(
                com.vayunmathur.library.ui.odf.OdfSheet(
                    "S",
                    listOf(
                        com.vayunmathur.library.ui.odf.OdfRow(
                            listOf(
                                com.vayunmathur.library.ui.odf.OdfCell("a1", textColor = 0xFFFF0000L)))))))
        val state = DocumentOverlayState().apply { showCellTextColor = true }
        val viewModel = vm(doc)
        overlays(state, doc, viewModel, activeCell = Triple(0, 0, 0))
        rule.onNodeWithText("Text Color").assertIsDisplayed()
        rule.onNodeWithText("∅").performClick()
        val cell = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0].rows[0].cells[0]
        assertEquals(null, cell.textColor)
        assertTrue(!state.showCellTextColor)
    }

    @Test fun cellOverlaysNullCellRendersNothing() {
        val state = DocumentOverlayState().apply {
            showCellTextColor = true
            showCellComment = true
        }
        overlays(state, textDoc(), vm(textDoc()))
        // Guards return early without an active cell: no dialog appears.
        rule.onNodeWithText("Text Color").assertDoesNotExist()
    }

    @Test fun headerFooterOverlaySaves() {
        val state = DocumentOverlayState().apply { showHeaderFooter = true }
        val viewModel = vm(textDoc())
        overlays(state, textDoc(), viewModel)
        rule.onNodeWithText("Header & Footer").assertIsDisplayed()
        rule.onNodeWithText("Save", substring = true).performClick()
        assertTrue(!state.showHeaderFooter)
    }

    @Test fun pageSetupOverlayRenders() {
        val state = DocumentOverlayState().apply { showPageSetup = true }
        overlays(state, textDoc(), vm(textDoc()))
        rule.onNodeWithText("Page setup", substring = true).assertIsDisplayed()
    }

    @Test fun chartEditorForTextDismisses() {
        val state = DocumentOverlayState().apply {
            showChartEditor = true
            editingChartBlock = 0
        }
        overlays(state, textDoc(), vm(textDoc()))
        rule.onNodeWithText("Insert Chart", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Cancel", substring = true).performClick()
        assertTrue(!state.showChartEditor)
        assertEquals(-1, state.editingChartBlock)
    }
}
