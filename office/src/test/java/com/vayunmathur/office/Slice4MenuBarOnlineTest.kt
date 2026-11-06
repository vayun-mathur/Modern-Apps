package com.vayunmathur.office

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.util.OfficeDocMeta
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 menu-bar + online-tab tests (Robolectric):
 * [DocumentMenuBar] file/view menus and the [OnlineTab] disabled screen.
 * The activity comes from the android compose rule; print/export launchers
 * are the production factory (never fired).
 */
@RunWith(AndroidJUnit4::class)
class Slice4MenuBarOnlineTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun textDoc() = OdfDocument.TextDocument(
        "Notes.odt",
        listOf(
            OdfContentBlock.Paragraph(
                OdfParagraph(listOf(OdfSpan("Title")), style = ParagraphStyle.HEADING1)),
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("body")))),
        ),
    )

    private fun showMenuBar(
        s: DocumentScreenState,
        document: OdfDocument = textDoc(),
        isOnline: Boolean = false,
        onlineEnabled: Boolean = false,
    ) {
        val viewModel = vm(document)
        rule.setContent {
            DocumentMenuBar(
                s = s,
                document = document,
                viewModel = viewModel,
                activity = rule.activity,
                isTextDoc = document is OdfDocument.TextDocument,
                isSpreadsheet = document is OdfDocument.Spreadsheet,
                isPresentation = document is OdfDocument.Presentation,
                isOnline = isOnline,
                onlineEnabled = onlineEnabled,
                hasUnsavedChanges = false,
                saveAsName = "Notes.odt",
                wordCount = 2,
                charCount = 10,
                readingTime = 1,
                nightMode = false,
                documentDarkMode = false,
                launchers = rememberDocumentLaunchers(
                    viewModel,
                    s,
                    onPickImageBytes = { _, _ -> }),
                scope = rememberCoroutineScope(),
                drawerState = com.vayunmathur.library.ui.rememberDrawerState(
                    DrawerValue.Closed),
            )
        }
    }

    @Test fun menuBarShowsFileInsertView() {
        showMenuBar(DocumentScreenState())
        rule.onNodeWithText("File").assertIsDisplayed()
        rule.onNodeWithText("Insert").assertIsDisplayed()
        rule.onNodeWithText("View").assertIsDisplayed()
    }

    @Test fun fileMenuSaveAndShare() {
        val s = DocumentScreenState()
        showMenuBar(s)
        rule.onNodeWithText("File").performClick()
        // Exact "Save" (substring also matches "Save As…").
        rule.onNodeWithText("Save").assertIsDisplayed()
        rule.onNodeWithText("Share", substring = true).assertIsDisplayed()
    }

    @Test fun fileMenuOnlineShowsSynced() {
        val s = DocumentScreenState()
        showMenuBar(s, isOnline = true, onlineEnabled = true)
        rule.onNodeWithText("File").performClick()
        rule.onNodeWithText("Synced", substring = true).assertIsDisplayed()
    }

    @Test fun viewMenuZoomAndOutline() {
        val s = DocumentScreenState()
        showMenuBar(s)
        rule.onNodeWithText("View").performClick()
        rule.onNodeWithText("Zoom", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Outline", substring = true).assertIsDisplayed()
    }

    @Test fun viewMenuZoomTogglesFontControl() {
        val s = DocumentScreenState()
        showMenuBar(s)
        rule.onNodeWithText("View").performClick()
        rule.onNodeWithText("Zoom", substring = true).performClick()
        assertTrue(s.showFontControl)
        assertTrue(!s.viewMenu)
    }

    @Test fun viewMenuTimerForPresentation() {
        val s = DocumentScreenState()
        val deck = OdfDocument.Presentation("D", emptyList())
        val viewModel = vm(deck)
        rule.setContent {
            DocumentMenuBar(
                s = s,
                document = deck,
                viewModel = viewModel,
                activity = rule.activity,
                isTextDoc = false,
                isSpreadsheet = false,
                isPresentation = true,
                isOnline = false,
                onlineEnabled = false,
                hasUnsavedChanges = false,
                saveAsName = "D.odp",
                wordCount = 0,
                charCount = 0,
                readingTime = 1,
                nightMode = false,
                documentDarkMode = false,
                launchers = rememberDocumentLaunchers(
                    viewModel,
                    s,
                    onPickImageBytes = { _, _ -> }),
                scope = rememberCoroutineScope(),
                drawerState = com.vayunmathur.library.ui.rememberDrawerState(
                    DrawerValue.Closed),
            )
        }
        rule.onNodeWithText("View").performClick()
        rule.onNodeWithText("Presentation timer").performClick()
        assertTrue(s.showTimer)
    }

    // ---- OnlineTab ----

    @Test fun onlineTabDisabledScreen() {
        // Fresh ViewModel: no online identity -> opt-in prompt, no network touched.
        // NOTE: the enable button is deliberately NOT tapped here: enabling mints
        // PQC keys into the DataStore, which the ViewModel init reads back —
        // flipping later tests in this class to the enabled path.
        val viewModel = vm(textDoc())
        var opened: OfficeDocMeta? = null
        rule.setContent {
            OnlineTab(viewModel = viewModel, onOpenDoc = { opened = it })
        }
        rule.onNodeWithText("Online sharing is off").assertIsDisplayed()
        rule.onNodeWithText("Enable online sharing").assertIsDisplayed()
        assertEquals(null, opened)
    }

    @Test fun onlineTabDisabledHasEnableButton() {
        val viewModel = vm(textDoc())
        rule.setContent {
            OnlineTab(viewModel = viewModel, onOpenDoc = {})
        }
        // Long explainer sits below spacers: existence only.
        rule.onNodeWithText("Turn on online sharing", substring = true).assertExists()
    }
}
