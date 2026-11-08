package com.vayunmathur.office

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfBookmark
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.ui.HeadingItem
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 shell tests (Robolectric + createComposeRule): error body,
 * selection/chrome holders, the overlays host fan-out, and the full
 * scaffold shell for a text document.
 */
@RunWith(AndroidJUnit4::class)
class Slice4ShellTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun textDoc() = OdfDocument.TextDocument(
        "Notes.odt",
        listOf(
            OdfContentBlock.Paragraph(
                OdfParagraph(
                    listOf(OdfSpan("Title")),
                    style = ParagraphStyle.HEADING1)),
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("body")))),
        ),
        bookmarks = listOf(OdfBookmark("ch1", 1)),
    )

    // ---- ErrorBody ----

    @Test fun errorBodyShowsMessageAndOpens() {
        var opened = false
        rule.setContent { ErrorBody(message = "boom", onOpenDocument = { opened = true }) }
        rule.onNodeWithText("boom").assertIsDisplayed()
        rule.onNodeWithText("Open Document").performClick()
        assertTrue(opened)
    }

    // ---- Holders ----

    @Test fun selectionHolderMirrorsState() {
        val s = DocumentScreenState()
        val h = ScreenSelectionHolder(s)
        s.activeRunStart = 3
        s.activeCell = Triple(0, 1, 2)
        s.showSearch = true
        assertEquals(3, h.activeRunStart)
        assertEquals(Triple(0, 1, 2), h.activeCell)
        assertTrue(h.showSearch)
        h.activeSlide = 4
        assertEquals(4, s.activeSlide)
    }

    @Test fun screenSelectionSnapshot() {
        val sel = ScreenSelection(
            activeRunStart = 0, activeRunEnd = 1,
            activeTableBlock = -1, activeTableRow = -1, activeTableCol = -1,
            selStart = 0, selEnd = 2,
            activeCell = null, activeSlide = 0, activeSlideEl = -1,
        )
        assertEquals(0, sel.activeRunStart)
        assertEquals(-1, sel.activeTableBlock)
    }

    @Test fun chromeCollects() {
        val doc = textDoc()
        var chrome: DocumentChrome? = null
        rule.setContent {
            chrome = rememberDocumentChrome(viewModel = vm(doc), document = doc)
        }
        rule.waitForIdle()
        val c = chrome
        assertTrue(c != null)
        assertTrue(c!!.isTextDoc)
        assertTrue(!c.isSpreadsheet && !c.isPresentation)
        assertTrue(c.canEdit)
        assertEquals("Notes.odt", c.saveAsName)
        assertEquals(1, c.headings.size)
        assertEquals("Title", c.headings[0].text)
        assertEquals(1, c.bookmarks.size)
    }

    // ---- DocumentOverlays host ----

    @Test fun overlaysHostSilentWithFlagsOff() {
        val doc = textDoc()
        val viewModel = vm(doc)
        val state = DocumentOverlayState()
        var becameOnline: String? = null
        var backed = false
        rule.setContent {
            DocumentOverlays(
                state = state,
                document = doc,
                viewModel = viewModel,
                isPresentation = false,
                isOnline = false,
                focusedPara = -1,
                saveAsName = "Notes.odt",
                activeCell = remember { mutableStateOf(null) },
                activeSlide = remember { mutableIntStateOf(0) },
                activeSlideEl = remember { mutableIntStateOf(-1) },
                activeRunStart = remember { mutableIntStateOf(-1) },
                activeRunEnd = remember { mutableIntStateOf(-1) },
                selStart = remember { mutableIntStateOf(0) },
                selEnd = remember { mutableIntStateOf(0) },
                pendingReplace = remember { mutableStateOf(null) },
                listState = rememberLazyListState(),
                scope = rememberCoroutineScope(),
                launchers = rememberDocumentLaunchers(
                    viewModel,
                    DocumentScreenState(),
                    onPickImageBytes = { _, _ -> }),
                onBecameOnline = { becameOnline = it },
                onBack = { backed = true },
            )
        }
        rule.waitForIdle()
        assertEquals(null, becameOnline)
        assertTrue(!backed)
    }

    @Test fun overlaysHostMetadataDialog() {
        val doc = textDoc()
        val viewModel = vm(doc)
        val state = DocumentOverlayState().apply { showMetadata = true }
        rule.setContent {
            DocumentOverlays(
                state = state,
                document = doc,
                viewModel = viewModel,
                isPresentation = false,
                isOnline = false,
                focusedPara = -1,
                saveAsName = "Notes.odt",
                activeCell = remember { mutableStateOf(null) },
                activeSlide = remember { mutableIntStateOf(0) },
                activeSlideEl = remember { mutableIntStateOf(-1) },
                activeRunStart = remember { mutableIntStateOf(-1) },
                activeRunEnd = remember { mutableIntStateOf(-1) },
                selStart = remember { mutableIntStateOf(0) },
                selEnd = remember { mutableIntStateOf(0) },
                pendingReplace = remember { mutableStateOf(null) },
                listState = rememberLazyListState(),
                scope = rememberCoroutineScope(),
                launchers = rememberDocumentLaunchers(
                    viewModel,
                    DocumentScreenState(),
                    onPickImageBytes = { _, _ -> }),
                onBecameOnline = {},
                onBack = {},
            )
        }
        // Metadata dialog from the host (title "Info"; fields start empty since
        // metadata.title is null even though the document file is "Notes.odt").
        rule.onNodeWithText("Info").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
    }

    // ---- DocumentScaffoldShell ----

    @Test fun scaffoldShellTextDoc() {
        val doc = textDoc()
        val viewModel = vm(doc)
        val s = DocumentScreenState()
        val activity = rule.activity
        rule.setContent {
            val chrome = rememberDocumentChrome(viewModel = viewModel, document = doc)
            DocumentScaffoldShell(
                s = s,
                document = doc,
                viewModel = viewModel,
                activity = activity,
                chrome = chrome,
                launchers = rememberDocumentLaunchers(
                    viewModel,
                    s,
                    onPickImageBytes = { _, _ -> }),
                scope = rememberCoroutineScope(),
                drawerState = com.vayunmathur.library.ui.rememberDrawerState(
                    DrawerValue.Closed),
                listState = rememberLazyListState(),
                onToggleSearch = { s.showSearch = !s.showSearch },
                onShowUnsavedDialog = { s.showUnsavedDialog = true },
                sel = ScreenSelection(
                    activeRunStart = -1, activeRunEnd = -1,
                    activeTableBlock = -1, activeTableRow = -1, activeTableCol = -1,
                    selStart = 0, selEnd = 0,
                    activeCell = null, activeSlide = 0, activeSlideEl = -1,
                ),
                onRunSelectionChange = { _, _, _, _ -> },
                onCellFocus = { _, _, _ -> },
                onCellSelected = { _, _, _ -> },
                onSlideChange = {},
                onSlideElementSelected = { _, _ -> },
                onBack = {},
            )
        }
        rule.waitForIdle()
        // App-bar title + document body render.
        rule.onNodeWithText("Notes.odt").assertIsDisplayed()
        rule.onNodeWithText("body", substring = true).assertExists()
    }

    @Test fun headingItemModel() {
        val h = HeadingItem("T", 2, 7)
        assertEquals("T", h.text)
        assertEquals(2, h.level)
        assertEquals(7, h.contentIndex)
    }
}
